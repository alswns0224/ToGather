package com.togather.content.calendar.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventWrite;
import com.togather.content.calendar.domain.CalendarEventCopy;
import com.togather.content.calendar.repository.CalendarEventCopyRepository;
import com.togather.integration.service.IntegrationService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 팀 일정을 참여자 각자의 Google Calendar에 넣는다 (명세서 SD-08-05 "참석자 캘린더 등록", SD-10-09).
 *
 * <p>팀 일정의 초대만으로는 참여자 계정 설정("아는 사람이 보낸 초대만 추가")에 따라 참여자 캘린더에 일정이
 * 생기지 않는다. 그러면 참여자 캘린더에서 회의가 안 보이고, 다음 회의 조율 때 바쁜 시간에도 잡히지 않는다.
 * 그래서 Calendar를 연동한 참여자에게는 본인 토큰으로 본인 캘린더에 사본을 직접 넣는다.
 * 사본은 팀 일정과 같은 iCalUID를 써서, 나중에 초대를 수락해도 같은 회의가 두 개로 생기지 않는다.</p>
 *
 * <p>사본은 참여자 편의 기능이므로, 한 참여자에게 실패해도 팀 일정 등록·수정·삭제는 그대로 진행한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarCopyService {

    static final String PRIMARY = "primary";

    private final GoogleCalendarClient calendarClient;
    private final IntegrationService integrationService;
    private final UserService userService;
    private final CalendarEventCopyRepository copyRepository;

    /**
     * 팀 일정을 참여자 캘린더에 넣는다.
     *
     * @param sourceCalendarId 팀 일정이 들어간 캘린더. 이 캘린더가 곧 본인 기본 캘린더인 참여자는 건너뛴다.
     * @param sourceOwnerUserId 개인 캘린더에 만든 일정이면 그 주인. 팀 캘린더면 null
     */
    public void copyToParticipants(Long workspaceId, Long contentRefId, String sourceCalendarId,
                                   Long sourceOwnerUserId, CalendarEvent event, Collection<Long> participantIds) {
        if (event.iCalUid() == null || participantIds == null || participantIds.isEmpty()) {
            return;
        }
        Map<Long, UserSummary> users = users(participantIds);
        Set<Long> existing = copyRepository.findAllByContentRefId(contentRefId).stream()
                .map(CalendarEventCopy::getUserId).collect(Collectors.toSet());
        for (Long userId : new LinkedHashSet<>(participantIds)) {
            if (existing.contains(userId) || ownsSourceCalendar(userId, users.get(userId), sourceCalendarId,
                    sourceOwnerUserId)) {
                continue;
            }
            insertCopy(workspaceId, contentRefId, userId, event);
        }
    }

    /**
     * 팀 일정을 고친 뒤 사본을 맞춘다. participantIds가 null이면 참여자는 그대로 두고 내용만 고친다.
     * 빠진 참여자의 사본은 지우고, 새 참여자에게는 사본을 넣는다.
     */
    public void syncCopies(Long workspaceId, Long contentRefId, String sourceCalendarId, Long sourceOwnerUserId,
                           CalendarEvent event, Collection<Long> participantIds) {
        List<CalendarEventCopy> copies = copyRepository.findAllByContentRefId(contentRefId);
        Set<Long> keep = participantIds == null ? null : new LinkedHashSet<>(participantIds);
        for (CalendarEventCopy copy : copies) {
            if (keep != null && !keep.contains(copy.getUserId())) {
                deleteCopy(workspaceId, copy);
                continue;
            }
            token(workspaceId, copy.getUserId()).ifPresent(token -> {
                try {
                    calendarClient.patchCopy(token, copy.getCalendarId(), copy.getEventId(), write(event));
                } catch (BusinessException e) {
                    log.warn("참여자 캘린더 사본 수정 실패: contentRefId={}, userId={}, code={}",
                            contentRefId, copy.getUserId(), e.getErrorCode());
                }
            });
        }
        if (keep != null) {
            copyToParticipants(workspaceId, contentRefId, sourceCalendarId, sourceOwnerUserId, event, keep);
        }
    }

    /** 팀 일정을 지울 때 사본도 지운다. */
    public void deleteCopies(Long workspaceId, Long contentRefId) {
        copyRepository.findAllByContentRefId(contentRefId).forEach(copy -> deleteCopy(workspaceId, copy));
    }

    // ===================== 내부 =====================

    private void insertCopy(Long workspaceId, Long contentRefId, Long userId, CalendarEvent event) {
        Optional<String> token = token(workspaceId, userId);
        if (token.isEmpty()) {
            return; // Calendar를 연동하지 않은 참여자는 초대 메일로만 받는다.
        }
        try {
            CalendarEvent copy = calendarClient.importCopy(token.get(), PRIMARY, event.iCalUid(), write(event));
            copyRepository.save(new CalendarEventCopy(contentRefId, userId, PRIMARY, copy.id()));
        } catch (BusinessException e) {
            log.warn("참여자 캘린더 사본 등록 실패: contentRefId={}, userId={}, code={}",
                    contentRefId, userId, e.getErrorCode());
        }
    }

    private void deleteCopy(Long workspaceId, CalendarEventCopy copy) {
        token(workspaceId, copy.getUserId()).ifPresent(token -> {
            try {
                calendarClient.deleteCopy(token, copy.getCalendarId(), copy.getEventId());
            } catch (BusinessException e) {
                log.warn("참여자 캘린더 사본 삭제 실패: copyId={}, code={}", copy.getCopyId(), e.getErrorCode());
            }
        });
        copyRepository.delete(copy);
    }

    /** 이 참여자의 기본 캘린더가 곧 원본 일정이 들어간 캘린더면 사본이 필요 없다. */
    private static boolean ownsSourceCalendar(Long userId, UserSummary user, String sourceCalendarId,
                                              Long sourceOwnerUserId) {
        if (userId.equals(sourceOwnerUserId)) {
            return true;
        }
        return user != null && user.email() != null && user.email().equalsIgnoreCase(sourceCalendarId);
    }

    private Optional<String> token(Long workspaceId, Long userId) {
        try {
            return integrationService.findAccessToken(workspaceId, userId, Provider.GOOGLE_CALENDAR);
        } catch (BusinessException e) {
            log.info("참여자 캘린더 토큰을 쓸 수 없어 사본을 건너뜁니다: userId={}, code={}", userId, e.getErrorCode());
            return Optional.empty();
        }
    }

    private Map<Long, UserSummary> users(Collection<Long> userIds) {
        return userService.getUsers(List.copyOf(new LinkedHashSet<>(userIds))).stream()
                .collect(Collectors.toMap(UserSummary::userId, Function.identity(), (a, b) -> a));
    }

    /** 사본 내용. 화상 회의 링크는 사본에 회의 정보가 없으므로 설명에 붙인다. */
    private static EventWrite write(CalendarEvent event) {
        String description = event.description();
        if (event.meetLink() != null && (description == null || !description.contains(event.meetLink()))) {
            description = (description == null ? "" : description + "\n\n") + "Google Meet: " + event.meetLink();
        }
        return new EventWrite(event.title(), description, event.startAt(), event.endAt(), null);
    }
}
