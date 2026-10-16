package com.togather.assistant.service;

import com.togather.analysis.service.TaskGateway;
import com.togather.assistant.domain.PersonalTask;
import com.togather.assistant.domain.Suggestion;
import com.togather.assistant.dto.DetectResultResponse;
import com.togather.assistant.dto.MyTodoListDTO;
import com.togather.assistant.dto.PersonalTaskResponse;
import com.togather.assistant.dto.SuggestionDTO;
import com.togather.assistant.dto.SuggestionUpdateRequest;
import com.togather.assistant.repository.PersonalTaskRepository;
import com.togather.assistant.repository.SuggestionRepository;
import com.togather.auth.service.UserService;
import com.togather.common.ai.GeminiClient;
import com.togather.common.ai.PromptTemplates;
import com.togather.common.enums.PersonalTaskStatus;
import com.togather.common.enums.Provider;
import com.togather.common.enums.SuggestionStatus;
import com.togather.common.enums.SuggestionType;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.dto.PersonalEventRequest;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.service.SlackContentService;
import com.togather.content.slack.service.SlackMessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-08-02 SuggestionService (UC-13-01 ~ 02). Gmail·Slack 메시지에서 일정·할 일 후보를 찾고,
 * 사용자가 승인하면 일정은 개인 캘린더에, 할 일은 개인 할 일로 등록한다. (승인 전에는 아무것도 등록하지 않는다)
 * 후보·개인 할 일은 개인 데이터라 모든 조회에 ownerUserId 조건을 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuggestionService {

    static final int LOOKBACK_DAYS = 14;
    /** Gemini 한 번에 보내는 최대 메시지 수 */
    static final int MAX_MESSAGES = 60;
    private static final int MAX_MESSAGE_LENGTH = 800;
    private static final int EXCERPT_LENGTH = 120;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SuggestionRepository suggestionRepository;
    private final PersonalTaskRepository personalTaskRepository;
    private final ContentRefRepository contentRefRepository;
    private final SlackContentService slackContentService;
    private final CalendarContentService calendarContentService;
    private final TaskGateway taskGateway;
    private final GeminiClient geminiClient;
    private final PromptTemplates promptTemplates;
    private final UserService userService;

    /**
     * 워크스페이스·사용자별로 Gemini에 보낸 마지막 메시지의 작성 시각. 이보다 오래된 메시지는 다시 보내지 않는다.
     * (후보가 없던 메시지를 매번 다시 분석하지 않도록. 서버를 다시 켜면 처음부터 본다)
     */
    private final Map<String, LocalDateTime> scannedUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** Gemini 응답 형식 (resources/prompts/assistant-candidates.txt) */
    public record AiResult(List<AiCandidate> candidates) {
    }

    public record AiCandidate(String type, String title, String source, String datetime, String dueDate) {
    }

    // ===================== 탐지 (SD-13-01) =====================

    /**
     * 최근 14일 동안 내가 쓰거나 받은 메시지 중 아직 살펴보지 않은 것에서 후보를 찾는다.
     *
     * @param type null이면 일정·할 일 모두
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DetectResultResponse detectCandidates(Long workspaceId, Long userId, SuggestionType type) {
        List<ContentRef> messages = filterRelevantMessages(workspaceId, userId);
        if (messages.isEmpty()) {
            return new DetectResultResponse(0, List.of());
        }
        Map<String, ContentRef> bySource = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ContentRef m = messages.get(i);
            String label = "s" + (i + 1);
            bySource.put(label, m);
            lines.append(label).append(" [").append(sourceName(m)).append("] ")
                    .append(Objects.requireNonNullElse(m.getAuthorName(), "")).append(' ')
                    .append(m.getCreatedAt() == null ? "" : TIME.format(m.getCreatedAt())).append(": ")
                    .append(messageText(m)).append('\n');
        }
        String prompt = promptTemplates.render("assistant-candidates", Map.of(
                "userName", userService.getUser(userId).name(),
                "today", LocalDate.now(SlackApiClient.ZONE).toString(),
                "messages", lines.toString()));
        AiResult result = geminiClient.generateJson(prompt, AiResult.class);
        messages.stream().map(ContentRef::getCreatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(latest -> scannedUntil.merge(scanKey(workspaceId, userId), latest,
                        (a, b) -> a.isAfter(b) ? a : b));

        List<Suggestion> candidates = new ArrayList<>();
        for (AiCandidate c : result.candidates() == null ? List.<AiCandidate>of() : result.candidates()) {
            SuggestionType candidateType = parseType(c.type());
            ContentRef source = c.source() == null ? null : bySource.get(c.source().strip());
            if (candidateType == null || source == null || c.title() == null || c.title().isBlank()) {
                continue;
            }
            if (type != null && candidateType != type) {
                continue;
            }
            candidates.add(new Suggestion(userId, candidateType, source.getContentRefId(), truncate(c.title().strip(), 200),
                    candidateType == SuggestionType.SCHEDULE ? parseDateTime(c.datetime()) : null,
                    candidateType == SuggestionType.TASK ? parseDate(c.dueDate()) : null));
        }
        List<Suggestion> saved = saveNew(userId, excludeDuplicateCandidates(userId, candidates));
        log.info("후보 탐지: workspaceId={}, userId={}, messages={}, created={}", workspaceId, userId,
                messages.size(), saved.size());
        return new DetectResultResponse(messages.size(), toDtos(saved));
    }

    // ===================== 조회 =====================

    public List<SuggestionDTO> getSuggestions(Long workspaceId, Long userId, SuggestionType type) {
        return toDtos(suggestionRepository.findByOwnerInWorkspace(userId, workspaceId, SuggestionStatus.PENDING, type));
    }

    public MyTodoListDTO getMyTodoList(Long workspaceId, Long userId) {
        List<MyTodoListDTO.AssignedTask> assigned = taskGateway.getMyTasks(userId).stream()
                .filter(t -> workspaceId.equals(t.workspaceId()))
                .map(t -> new MyTodoListDTO.AssignedTask(t.taskId(), t.workspaceId(), t.title(), t.dueDate()))
                .toList();
        List<PersonalTaskResponse> personal = new ArrayList<>(personalTaskRepository
                .findAllByOwnerUserIdAndStatusOrderByDueDateAscPersonalTaskIdAsc(userId, PersonalTaskStatus.TODO)
                .stream().map(SuggestionService::toResponse).toList());
        personal.addAll(personalTaskRepository
                .findTop20ByOwnerUserIdAndStatusOrderByUpdatedAtDesc(userId, PersonalTaskStatus.DONE)
                .stream().map(SuggestionService::toResponse).toList());
        return new MyTodoListDTO(assigned, personal, getSuggestions(workspaceId, userId, SuggestionType.TASK));
    }

    // ===================== 검토·승인 (SD-13-02) =====================

    @Transactional
    public SuggestionDTO updateSuggestion(Long workspaceId, Long userId, Long suggestionId,
                                          SuggestionUpdateRequest request) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        suggestion.update(request.title().strip(), request.detectedDatetime(), request.dueDate());
        return toDtos(List.of(suggestion)).getFirst();
    }

    /** 일정 후보를 개인 캘린더에 등록하고 승인한다. 시간이 미정이면 먼저 일시를 정해야 한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long approveSchedule(Long workspaceId, Long userId, Long suggestionId) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        if (suggestion.getType() != SuggestionType.SCHEDULE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (suggestion.getDetectedDatetime() == null) {
            throw new BusinessException(ErrorCode.SUGGESTION_TIME_REQUIRED);
        }
        ContentRefDTO event = calendarContentService.createPersonalEvent(workspaceId, userId,
                buildEventData(suggestion));
        suggestion.approveAsEvent(event.contentRefId());
        suggestionRepository.save(suggestion);
        return event.contentRefId();
    }

    /** 할 일 후보를 개인 할 일로 등록하고 승인한다. */
    @Transactional
    public Long approveTask(Long workspaceId, Long userId, Long suggestionId) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        if (suggestion.getType() != SuggestionType.TASK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        PersonalTask task = personalTaskRepository.save(new PersonalTask(userId, suggestion.getTitle(),
                suggestion.getDueDate(), suggestion.getSourceContentRefId()));
        suggestion.approveAsPersonalTask(task.getPersonalTaskId());
        return task.getPersonalTaskId();
    }

    /** 무시하면 목록에서 사라지고, 같은 메시지에서 다시 추출하지 않는다. */
    @Transactional
    public void dismissSuggestion(Long workspaceId, Long userId, Long suggestionId) {
        getPending(workspaceId, userId, suggestionId).dismiss();
    }

    @Transactional
    public void completePersonalTask(Long userId, Long personalTaskId) {
        getPersonalTask(userId, personalTaskId).complete();
    }

    // ===================== 개인 할 일 직접 관리 (UI-13 내 할 일) =====================

    @Transactional
    public PersonalTaskResponse createPersonalTask(Long userId, String title, LocalDate dueDate) {
        return toResponse(personalTaskRepository.save(new PersonalTask(userId, title.strip(), dueDate, null)));
    }

    @Transactional
    public PersonalTaskResponse updatePersonalTask(Long userId, Long personalTaskId, String title, LocalDate dueDate) {
        PersonalTask task = getPersonalTask(userId, personalTaskId);
        task.update(title.strip(), dueDate);
        return toResponse(task);
    }

    @Transactional
    public void reopenPersonalTask(Long userId, Long personalTaskId) {
        getPersonalTask(userId, personalTaskId).reopen();
    }

    @Transactional
    public void deletePersonalTask(Long userId, Long personalTaskId) {
        personalTaskRepository.delete(getPersonalTask(userId, personalTaskId));
    }

    private PersonalTask getPersonalTask(Long userId, Long personalTaskId) {
        return personalTaskRepository.findByPersonalTaskIdAndOwnerUserId(personalTaskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERSONAL_TASK_NOT_FOUND));
    }

    // ===================== 내부 =====================

    /** 명세서 filterRelevantMessages: 본인이 쓰거나 멘션된 Slack 메시지 + 내 Gmail, 이미 살펴본 메시지 제외 */
    List<ContentRef> filterRelevantMessages(Long workspaceId, Long userId) {
        LocalDateTime after = LocalDateTime.now(SlackApiClient.ZONE).minusDays(LOOKBACK_DAYS);
        List<ContentRef> all = new ArrayList<>(slackContentService.findMessagesByUser(workspaceId, userId));
        all.addAll(contentRefRepository.findAllByWorkspaceIdAndOwnerUserIdAndProviderAndCreatedAtAfterOrderByCreatedAtDesc(
                workspaceId, userId, Provider.GMAIL, after));
        LocalDateTime scanned = scannedUntil.get(scanKey(workspaceId, userId));
        Set<Long> seen = suggestionRepository.findAllByOwnerUserIdAndSourceContentRefIdIn(userId,
                        all.stream().map(ContentRef::getContentRefId).toList()).stream()
                .map(Suggestion::getSourceContentRefId).collect(Collectors.toSet());
        return all.stream()
                .filter(m -> !m.isOriginDeleted() && !seen.contains(m.getContentRefId()))
                .filter(m -> scanned == null || (m.getCreatedAt() != null && m.getCreatedAt().isAfter(scanned)))
                .collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(ContentRef::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_MESSAGES)
                .toList();
    }

    private static String scanKey(Long workspaceId, Long userId) {
        return workspaceId + ":" + userId;
    }

    /** 명세서 excludeDuplicateCandidates: 대기 중 후보, 개인 할 일, 담당 Task와 제목이 겹치면 제외 */
    List<Suggestion> excludeDuplicateCandidates(Long userId, List<Suggestion> candidates) {
        Set<String> existing = new HashSet<>();
        suggestionRepository.findAllByOwnerUserIdAndStatus(userId, SuggestionStatus.PENDING)
                .forEach(s -> existing.add(normalize(s.getTitle())));
        personalTaskRepository.findAllByOwnerUserIdAndStatusOrderByDueDateAscPersonalTaskIdAsc(userId,
                PersonalTaskStatus.TODO).forEach(t -> existing.add(normalize(t.getTitle())));
        taskGateway.getMyTasks(userId).forEach(t -> existing.add(normalize(t.title())));
        List<Suggestion> result = new ArrayList<>();
        for (Suggestion candidate : candidates) {
            String key = normalize(candidate.getTitle());
            if (existing.stream().noneMatch(e -> isSimilar(e, key))) {
                existing.add(key);
                result.add(candidate);
            }
        }
        return result;
    }

    static boolean isSimilar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || (Math.min(a.length(), b.length()) >= 4 && (a.contains(b) || b.contains(a)));
    }

    static String normalize(String title) {
        return title == null ? "" : title.toLowerCase().replaceAll("[\\s\\p{Punct}]", "");
    }

    /** 명세서 buildEventData: 일정은 1시간으로 등록한다. */
    private static PersonalEventRequest buildEventData(Suggestion suggestion) {
        return new PersonalEventRequest(suggestion.getTitle(), suggestion.getDetectedDatetime(), null);
    }

    private List<Suggestion> saveNew(Long userId, List<Suggestion> suggestions) {
        return suggestionRepository.saveAll(suggestions);
    }

    private Suggestion getPending(Long workspaceId, Long userId, Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findBySuggestionIdAndOwnerUserId(suggestionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUGGESTION_NOT_FOUND));
        boolean inWorkspace = suggestion.getSourceContentRefId() != null && contentRefRepository
                .findById(suggestion.getSourceContentRefId())
                .map(ref -> ref.getWorkspaceId().equals(workspaceId)).orElse(false);
        if (!inWorkspace) {
            throw new BusinessException(ErrorCode.SUGGESTION_NOT_FOUND);
        }
        if (suggestion.getStatus() != SuggestionStatus.PENDING) {
            throw new BusinessException(ErrorCode.SUGGESTION_ALREADY_HANDLED);
        }
        return suggestion;
    }

    private List<SuggestionDTO> toDtos(Collection<Suggestion> suggestions) {
        Map<Long, ContentRef> sources = new HashMap<>();
        contentRefRepository.findAllById(suggestions.stream().map(Suggestion::getSourceContentRefId)
                        .filter(Objects::nonNull).distinct().toList())
                .forEach(ref -> sources.put(ref.getContentRefId(), ref));
        return suggestions.stream().map(s -> {
            ContentRef source = sources.get(s.getSourceContentRefId());
            return new SuggestionDTO(s.getSuggestionId(), s.getType(), s.getStatus(), s.getTitle(),
                    s.getDetectedDatetime(), s.getType() == SuggestionType.SCHEDULE && s.getDetectedDatetime() == null,
                    s.getDueDate(), s.getSourceContentRefId(), source == null ? null : source.getProvider(),
                    source == null ? null : source.getTitle(), source == null ? null : source.getAuthorName(),
                    source == null ? null : source.getCreatedAt(),
                    source == null ? null : truncate(messageText(source), EXCERPT_LENGTH),
                    source == null ? null : source.getDeepLink());
        }).toList();
    }

    private static PersonalTaskResponse toResponse(PersonalTask t) {
        return new PersonalTaskResponse(t.getPersonalTaskId(), t.getTitle(), t.getDueDate(), t.getStatus(),
                t.getSourceContentRefId());
    }

    private static String messageText(ContentRef ref) {
        String text = ref.getProvider() == Provider.SLACK
                ? SlackMessageFormat.parse(ref.getContent()).text()
                : Objects.requireNonNullElse(ref.getTitle(), "") + " / " + Objects.requireNonNullElse(ref.getContent(), "");
        return truncate(text.replace('\n', ' '), MAX_MESSAGE_LENGTH);
    }

    private static String sourceName(ContentRef ref) {
        return ref.getProvider() == Provider.SLACK
                ? "Slack " + Objects.requireNonNullElse(ref.getTitle(), "")
                : "메일";
    }

    private static SuggestionType parseType(String type) {
        try {
            return type == null ? null : SuggestionType.valueOf(type.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        String v = value.strip();
        try {
            return LocalDateTime.parse(v.length() == 16 ? v + ":00" : v.substring(0, Math.min(19, v.length())));
        } catch (DateTimeParseException e) {
            return null; // 시간 미정
        }
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, Math.min(10, value.strip().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
