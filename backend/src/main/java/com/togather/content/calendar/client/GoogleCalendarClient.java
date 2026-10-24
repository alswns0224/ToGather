package com.togather.content.calendar.client;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.domain.DateRange;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google Calendar API v3. 시간은 서울 시간(LocalDateTime)으로 주고받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCalendarClient {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String BASE = "https://www.googleapis.com/calendar/v3";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient externalRestClient;

    public record CalendarEvent(String id, String title, String description, LocalDateTime startAt,
                                LocalDateTime endAt, boolean allDay, List<String> attendeeEmails,
                                String htmlLink, String meetLink, LocalDateTime createdAt) {
    }

    /**
     * 기간 안의 일정.
     *
     * @param cancelledIds 기간 안에서 삭제(취소)된 일정 ID. 저장된 사본을 원본 삭제로 표시하는 데 쓴다.
     */
    public record EventList(List<CalendarEvent> events, List<String> cancelledIds) {
    }

    /** 생성·수정 요청. null인 값은 수정 시 바꾸지 않는다. */
    public record EventWrite(String title, String description, LocalDateTime startAt, LocalDateTime endAt,
                             List<String> attendeeEmails) {
    }

    public EventList listEvents(String token, String calendarId, LocalDateTime from, LocalDateTime to) {
        List<CalendarEvent> events = new ArrayList<>();
        List<String> cancelledIds = new ArrayList<>();
        String pageToken = null;
        do {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE + "/calendars/{calendarId}/events")
                    .queryParam("timeMin", rfc3339(from))
                    .queryParam("timeMax", rfc3339(to))
                    .queryParam("singleEvents", "true")
                    .queryParam("orderBy", "startTime")
                    // 지운 일정도 status=cancelled로 받아서, 저장된 사본을 원본 삭제로 표시한다.
                    .queryParam("showDeleted", "true")
                    .queryParam("maxResults", 250)
                    .queryParam("timeZone", ZONE.getId());
            if (pageToken != null) {
                builder.queryParam("pageToken", pageToken);
            }
            Map<String, Object> json = call(() -> externalRestClient.get()
                    .uri(builder.encode().buildAndExpand(calendarId).toUri())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve().body(JSON));
            for (Map<String, Object> item : list(json, "items")) {
                if ("cancelled".equals(str(item, "status"))) {
                    cancelledIds.add(str(item, "id"));
                } else {
                    events.add(toEvent(item));
                }
            }
            pageToken = str(json, "nextPageToken");
        } while (pageToken != null && events.size() < 2000);
        return new EventList(events, cancelledIds);
    }

    /** @throws BusinessException CALENDAR_EVENT_NOT_FOUND 원본이 삭제된 경우 */
    public CalendarEvent getEvent(String token, String calendarId, String eventId) {
        Map<String, Object> json = call(() -> externalRestClient.get()
                .uri(eventUri(calendarId, eventId, null))
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JSON));
        if ("cancelled".equals(str(json, "status"))) {
            throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
        }
        return toEvent(json);
    }

    /** @param withMeet true면 Google Meet 화상 회의 링크를 함께 만든다. */
    public CalendarEvent insertEvent(String token, String calendarId, EventWrite write, boolean withMeet) {
        Map<String, Object> body = toBody(write);
        if (withMeet) {
            body.put("conferenceData", Map.of("createRequest", Map.of(
                    "requestId", UUID.randomUUID().toString(),
                    "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
        }
        String uri = UriComponentsBuilder.fromUriString(BASE + "/calendars/{calendarId}/events")
                .queryParam("sendUpdates", "none")
                .queryParam("conferenceDataVersion", withMeet ? 1 : 0)
                .encode().buildAndExpand(calendarId).toUriString();
        return toEvent(call(() -> externalRestClient.post()
                .uri(uri)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JSON)));
    }

    public CalendarEvent patchEvent(String token, String calendarId, String eventId, EventWrite write) {
        return toEvent(call(() -> externalRestClient.patch()
                .uri(eventUri(calendarId, eventId, "none"))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(toBody(write))
                .retrieve().body(JSON)));
    }

    /** 이미 삭제된 일정이면 조용히 성공으로 본다. */
    public void deleteEvent(String token, String calendarId, String eventId) {
        try {
            call(() -> {
                externalRestClient.delete()
                        .uri(eventUri(calendarId, eventId, "none"))
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve().toBodilessEntity();
                return Map.of();
            });
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.CALENDAR_EVENT_NOT_FOUND) {
                throw e;
            }
        }
    }

    /** 기본 캘린더(primary)에서 일정이 있는 시간대 */
    public List<DateRange> getBusyTimes(String token, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> body = Map.of(
                "timeMin", rfc3339(from),
                "timeMax", rfc3339(to),
                "timeZone", ZONE.getId(),
                "items", List.of(Map.of("id", "primary")));
        Map<String, Object> json = call(() -> externalRestClient.post()
                .uri(BASE + "/freeBusy")
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JSON));
        return list(obj(obj(json, "calendars"), "primary"), "busy").stream()
                .map(b -> new DateRange(toLocal(str(b, "start")), toLocal(str(b, "end"))))
                .toList();
    }

    // ===================== 변환 =====================

    private static CalendarEvent toEvent(Map<String, Object> json) {
        Map<String, Object> start = obj(json, "start");
        Map<String, Object> end = obj(json, "end");
        boolean allDay = str(start, "dateTime") == null;
        LocalDateTime startAt = allDay ? LocalDate.parse(str(start, "date")).atStartOfDay() : toLocal(str(start, "dateTime"));
        LocalDateTime endAt = allDay ? LocalDate.parse(str(end, "date")).atStartOfDay() : toLocal(str(end, "dateTime"));
        List<String> attendees = list(json, "attendees").stream().map(a -> str(a, "email")).toList();
        String created = str(json, "created");
        return new CalendarEvent(str(json, "id"), str(json, "summary"), str(json, "description"), startAt, endAt,
                allDay, attendees, str(json, "htmlLink"), str(json, "hangoutLink"),
                created == null ? null : toLocal(created));
    }

    private static Map<String, Object> toBody(EventWrite write) {
        Map<String, Object> body = new HashMap<>();
        if (write.title() != null) {
            body.put("summary", write.title());
        }
        if (write.description() != null) {
            body.put("description", write.description());
        }
        if (write.startAt() != null) {
            body.put("start", Map.of("dateTime", LOCAL_FORMAT.format(write.startAt()), "timeZone", ZONE.getId()));
        }
        if (write.endAt() != null) {
            body.put("end", Map.of("dateTime", LOCAL_FORMAT.format(write.endAt()), "timeZone", ZONE.getId()));
        }
        if (write.attendeeEmails() != null) {
            body.put("attendees", write.attendeeEmails().stream().map(email -> Map.of("email", email)).toList());
        }
        return body;
    }

    private static LocalDateTime toLocal(String rfc3339) {
        return OffsetDateTime.parse(rfc3339).atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /**
     * Google은 초까지 있는 RFC3339만 받는다. (toString()은 초가 0이면 초를 생략해서 400 오류가 난다)
     * 쿼리 문자열에서 '+09:00'의 '+'는 공백으로 해석되므로 UTC('Z')로 보낸다.
     */
    static String rfc3339(LocalDateTime time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.atZone(ZONE).toInstant());
    }

    private static java.net.URI eventUri(String calendarId, String eventId, String sendUpdates) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE + "/calendars/{calendarId}/events/{eventId}");
        if (sendUpdates != null) {
            builder.queryParam("sendUpdates", sendUpdates);
        }
        // encode() 후 buildAndExpand하면 캘린더 ID의 '@', '#' 같은 문자도 안전하게 인코딩된다.
        return builder.encode().buildAndExpand(calendarId, eventId).toUri();
    }

    private static Map<String, Object> call(java.util.function.Supplier<Map<String, Object>> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.GONE) {
                throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
            }
            // 응답 본문은 Google의 오류 설명(토큰·일정 내용 없음)이라 원인 파악을 위해 남긴다.
            log.warn("Google Calendar API 오류: status={}, body={}", e.getStatusCode().value(),
                    e.getResponseBodyAsString().replaceAll("\\s+", " "));
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (RestClientException e) {
            log.warn("Google Calendar API 호출 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
