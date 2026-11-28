package com.togather.assistant.service;

import com.togather.analysis.service.AnalysisService;
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
import com.togather.content.calendar.dto.CalendarEventRequest;
import com.togather.content.calendar.dto.PersonalEventRequest;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.gmail.service.GmailContentService;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.service.SlackContentService;
import com.togather.content.slack.service.SlackDirectMessageService;
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
    /** 한 번의 탐지에서 살펴보는 최대 메시지 수 (남은 메시지는 다음 탐지에서 본다) */
    static final int MAX_MESSAGES = 60;
    /** Gemini 한 번 호출에 넣는 메시지 수 (묶음끼리는 동시에 보낸다) */
    static final int BATCH_SIZE = 20;
    private static final java.util.concurrent.ExecutorService AI_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    /** 내가 참여한 스레드의 대화를 함께 볼 때 한 번에 보는 최대 스레드 수 */
    static final int MAX_THREADS = 30;
    private static final int MAX_MESSAGE_LENGTH = 800;
    private static final int EXCERPT_LENGTH = 120;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", java.util.Locale.KOREAN);

    private final SuggestionRepository suggestionRepository;
    private final PersonalTaskRepository personalTaskRepository;
    private final ContentRefRepository contentRefRepository;
    private final SlackContentService slackContentService;
    private final SlackDirectMessageService slackDirectMessages;
    private final GmailContentService gmailContentService;
    private final CalendarContentService calendarContentService;
    private final TaskGateway taskGateway;
    private final GeminiClient geminiClient;
    private final PromptTemplates promptTemplates;
    private final UserService userService;
    private final com.togather.meeting.repository.MeetingRepository meetingRepository;
    private final AnalysisService analysisService;
    private final com.togather.integration.service.IntegrationService integrationService;

    /**
     * 워크스페이스·사용자별로 이미 분석한 콘텐츠와 수정 시각을 기록한다.
     * 나중에 수집된 과거 DM도 분석하며 같은 버전은 다시 보내지 않는다. 서버 재시작 시 초기화된다.
     */
    private final Map<String, Map<Long, LocalDateTime>> scannedRevisions = new java.util.concurrent.ConcurrentHashMap<>();

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
        var direct = slackDirectMessages.collectForAssistant(workspaceId, userId);
        List<ContentRef> messages = filterRelevantMessages(workspaceId, userId, direct.messages());
        if (messages.isEmpty()) {
            return new DetectResultResponse(0, List.of(), direct.warnings());
        }
        // 한 번에 많이 보내면 Gemini 응답이 60초를 넘겨 실패한다. 스레드는 쪼개지 않고 BATCH_SIZE개 안팎으로 나눠 동시에 보낸다.
        String userName = userService.getUser(userId).name();
        List<List<ContentRef>> batches = batches(messages);
        List<java.util.concurrent.CompletableFuture<List<Found>>> futures = batches.stream()
                .map(batch -> java.util.concurrent.CompletableFuture.supplyAsync(() -> detectBatch(userName, batch),
                        AI_EXECUTOR))
                .toList();
        Map<Long, LocalDateTime> revisions = scannedRevisions.computeIfAbsent(scanKey(workspaceId, userId),
                ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        List<Found> found = new ArrayList<>();
        int scanned = 0;
        for (int i = 0; i < batches.size(); i++) {
            try {
                found.addAll(futures.get(i).join());
                batches.get(i).forEach(message -> revisions.put(message.getContentRefId(), revision(message)));
                scanned += batches.get(i).size();
            } catch (java.util.concurrent.CompletionException e) {
                log.warn("후보 탐지 일부 실패: workspaceId={}, userId={}, batch={}/{}, messages={}", workspaceId, userId,
                        i + 1, batches.size(), batches.get(i).size());
            }
        }
        if (scanned == 0) {
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        List<String> warnings = new ArrayList<>(direct.warnings());
        if (scanned < messages.size()) {
            warnings.add("AI 응답이 늦어 메시지 " + (messages.size() - scanned) + "개는 살펴보지 못했어요. 다시 찾으면 이어서 살펴봐요.");
        }

        List<Suggestion> candidates = new ArrayList<>();
        for (Found f : found) {
            AiCandidate c = f.candidate();
            SuggestionType candidateType = parseType(c.type());
            if (candidateType == null || c.title() == null || c.title().isBlank()) {
                continue;
            }
            if (type != null && candidateType != type) {
                continue;
            }
            candidates.add(new Suggestion(userId, candidateType, f.source().getContentRefId(),
                    truncate(c.title().strip(), 200),
                    candidateType == SuggestionType.SCHEDULE ? parseDateTime(c.datetime()) : null,
                    candidateType == SuggestionType.TASK ? parseDate(c.dueDate()) : null));
        }
        List<Suggestion> saved = saveNew(userId,
                excludeDuplicateCandidates(userId, excludeConfirmedMeetings(workspaceId, candidates)));
        log.info("후보 탐지: workspaceId={}, userId={}, messages={}, batches={}, created={}", workspaceId, userId,
                scanned, batches.size(), saved.size());
        return new DetectResultResponse(scanned, toDtos(saved), warnings);
    }

    /** AI가 찾은 후보와 근거 메시지 */
    private record Found(AiCandidate candidate, ContentRef source) {
    }

    /** 메시지 묶음 하나를 Gemini에 보내 후보를 찾는다. 근거 번호(s1, s2 …)는 묶음 안에서 매긴다. */
    private List<Found> detectBatch(String userName, List<ContentRef> messages) {
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
                "userName", userName,
                "today", PromptTemplates.today(LocalDate.now(SlackApiClient.ZONE)),
                "calendar", PromptTemplates.calendar(LocalDate.now(SlackApiClient.ZONE).minusDays(14), 35),
                "messages", lines.toString()));
        AiResult result = geminiClient.generateJson(prompt, AiResult.class);
        List<Found> found = new ArrayList<>();
        for (AiCandidate c : result.candidates() == null ? List.<AiCandidate>of() : result.candidates()) {
            ContentRef source = c.source() == null ? null : bySource.get(c.source().strip());
            if (source != null) {
                found.add(new Found(c, source));
            }
        }
        return found;
    }

    /**
     * 같은 Slack 스레드의 메시지는 한 묶음에 넣어 대화 맥락을 지킨다. 묶음은 BATCH_SIZE개를 넘으면 닫는다.
     * (스레드 하나가 BATCH_SIZE보다 길면 그 스레드만으로 한 묶음)
     */
    static List<List<ContentRef>> batches(List<ContentRef> messages) {
        Map<String, List<ContentRef>> groups = new LinkedHashMap<>();
        for (ContentRef m : messages) {
            String key = m.getProvider() == Provider.SLACK && m.getOwnerUserId() == null
                    ? "thread:" + threadRootId(m) : "single:" + m.getContentRefId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(m);
        }
        List<List<ContentRef>> batches = new ArrayList<>();
        List<ContentRef> current = new ArrayList<>();
        for (List<ContentRef> group : groups.values()) {
            if (!current.isEmpty() && current.size() + group.size() > BATCH_SIZE) {
                batches.add(current);
                current = new ArrayList<>();
            }
            current.addAll(group);
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    // ===================== 조회 =====================

    /** 논의 정리에서 확정한 스레드나 내가 보낸 메일에서 나온 후보는 보여 주지 않는다. */
    public List<SuggestionDTO> getSuggestions(Long workspaceId, Long userId, SuggestionType type) {
        List<Suggestion> pending = suggestionRepository.findByOwnerInWorkspace(userId, workspaceId,
                SuggestionStatus.PENDING, type);
        List<ContentRef> sources = contentRefRepository.findAllById(pending.stream()
                .map(Suggestion::getSourceContentRefId).filter(Objects::nonNull).distinct().toList());
        Set<Long> hidden = new HashSet<>(coveredByConfirmedAnalysis(sources));
        String myAddress = myGmailAddress(workspaceId, userId);
        sources.stream().filter(source -> source.getProvider() == Provider.GMAIL && sentBy(source, myAddress))
                .forEach(source -> hidden.add(source.getContentRefId()));
        return toDtos(pending.stream().filter(s -> !hidden.contains(s.getSourceContentRefId())).toList());
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
        return approveSchedule(workspaceId, userId, suggestionId, null);
    }

    /** endAt이 없으면 1시간짜리 일정으로 등록한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long approveSchedule(Long workspaceId, Long userId, Long suggestionId, LocalDateTime endAt) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        if (suggestion.getType() != SuggestionType.SCHEDULE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (suggestion.getDetectedDatetime() == null) {
            throw new BusinessException(ErrorCode.SUGGESTION_TIME_REQUIRED);
        }
        ContentRefDTO event = calendarContentService.createPersonalEvent(workspaceId, userId,
                buildEventData(suggestion, endAt));
        suggestion.approveAsEvent(event.contentRefId());
        suggestionRepository.save(suggestion);
        return event.contentRefId();
    }

    /**
     * 일정 후보를 팀 캘린더에 등록하고 승인한다. 참여자를 비우면 승인한 사람만 참여자로 넣는다.
     * 팀원 모두가 일정 화면에서 보게 된다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long approveTeamSchedule(Long workspaceId, Long userId, Long suggestionId, List<Long> participantIds,
                                    LocalDateTime endAt) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        if (suggestion.getType() != SuggestionType.SCHEDULE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (suggestion.getDetectedDatetime() == null) {
            throw new BusinessException(ErrorCode.SUGGESTION_TIME_REQUIRED);
        }
        List<Long> participants = participantIds == null || participantIds.isEmpty() ? List.of(userId) : participantIds;
        ContentRefDTO event = calendarContentService.createContent(workspaceId, userId,
                new CalendarEventRequest(suggestion.getTitle(), suggestion.getDetectedDatetime(), endAt, participants,
                        "AI 업무 보조에서 찾은 일정입니다."));
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

    /**
     * 할 일 후보를 팀 Task로 등록하고 승인한다. 담당자를 정하지 않으면 승인한 사람이 맡는다.
     * 팀원 모두가 Task 목록에서 보게 된다.
     */
    @Transactional
    public Long approveTeamTask(Long workspaceId, Long userId, Long suggestionId, Long assigneeId) {
        Suggestion suggestion = getPending(workspaceId, userId, suggestionId);
        if (suggestion.getType() != SuggestionType.TASK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Long taskId = taskGateway.createTask(workspaceId, new TaskGateway.FollowUpTask(suggestion.getTitle(),
                "AI 업무 보조에서 찾은 할 일입니다.", assigneeId != null ? assigneeId : userId,
                suggestion.getDueDate(), null));
        suggestion.approveAsTeamTask(taskId);
        return taskId;
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
        return filterRelevantMessages(workspaceId, userId, List.of());
    }

    private List<ContentRef> filterRelevantMessages(Long workspaceId, Long userId, List<ContentRef> directMessages) {
        syncGmail(workspaceId, userId);
        LocalDateTime after = LocalDateTime.now(SlackApiClient.ZONE).minusDays(LOOKBACK_DAYS);
        List<ContentRef> mine = slackContentService.findMessagesByUser(workspaceId, userId);
        List<ContentRef> all = new ArrayList<>(mine);
        all.addAll(threadContext(workspaceId, mine, after));
        Set<Long> covered = coveredByConfirmedAnalysis(all);
        all.removeIf(m -> covered.contains(m.getContentRefId()));
        directMessages.stream().filter(message -> workspaceId.equals(message.getWorkspaceId())
                && userId.equals(message.getOwnerUserId()) && message.getProvider() == Provider.SLACK).forEach(all::add);
        String myAddress = myGmailAddress(workspaceId, userId);
        contentRefRepository.findAllByWorkspaceIdAndOwnerUserIdAndProviderAndCreatedAtAfterOrderByCreatedAtDesc(
                        workspaceId, userId, Provider.GMAIL, after).stream()
                .filter(mail -> !sentBy(mail, myAddress))
                .forEach(all::add);
        Map<Long, LocalDateTime> scanned = scannedRevisions.computeIfAbsent(scanKey(workspaceId, userId),
                ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        scanned.keySet().retainAll(all.stream().map(ContentRef::getContentRefId).toList());
        Set<Long> seen = suggestionRepository.findAllByOwnerUserIdAndSourceContentRefIdIn(userId,
                        all.stream().map(ContentRef::getContentRefId).toList()).stream()
                .map(Suggestion::getSourceContentRefId).collect(Collectors.toSet());
        return all.stream()
                .filter(m -> !m.isOriginDeleted() && !seen.contains(m.getContentRefId()))
                .filter(m -> !isCalendarInvitation(m))
                .filter(m -> !revision(m).equals(scanned.get(m.getContentRefId())))
                .collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(ContentRef::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_MESSAGES)
                .toList();
    }

    /** 내 Gmail 연결의 메일 주소. 모르면 null (그때는 보낸 메일도 거르지 못한다) */
    private String myGmailAddress(Long workspaceId, Long userId) {
        try {
            return integrationService.findOwnExternalAccountId(workspaceId, userId, Provider.GMAIL).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 내가 보낸 메일인지. (보낸편지함의 메일도 함께 수집되므로, 받은 메일에서만 후보를 찾으려고 거른다)
     * 보낸 사람은 "이름 <주소>" 또는 "주소" 형식이다.
     */
    static boolean sentBy(ContentRef mail, String myAddress) {
        if (myAddress == null || myAddress.isBlank() || mail.getAuthorName() == null) {
            return false;
        }
        String from = mail.getAuthorName().toLowerCase();
        String me = myAddress.strip().toLowerCase();
        return from.equals(me) || from.contains("<" + me + ">");
    }

    /**
     * 내가 쓰거나 멘션된 채널 메시지가 속한 스레드의 나머지 대화. (예: 내가 참여한 스레드에서 팀원이 "화요일 3시에 리허설 하자")
     * 최근 스레드부터 MAX_THREADS개까지만 본다.
     */
    private List<ContentRef> threadContext(Long workspaceId, List<ContentRef> mine, LocalDateTime after) {
        List<Long> roots = mine.stream().map(SuggestionService::threadRootId).distinct().limit(MAX_THREADS).toList();
        List<ContentRef> context = new ArrayList<>(contentRefRepository.findAllById(roots));
        roots.forEach(root -> context.addAll(contentRefRepository.findAllByParentRefIdOrderByCreatedAtAsc(root)));
        return context.stream()
                .filter(m -> m.getProvider() == Provider.SLACK && m.getOwnerUserId() == null
                        && workspaceId.equals(m.getWorkspaceId()) && !m.isOriginDeleted()
                        && m.getCreatedAt() != null && m.getCreatedAt().isAfter(after))
                .toList();
    }

    /** Slack 채널 메시지면 스레드 루트, 아니면 자기 자신 */
    private static Long threadRootId(ContentRef message) {
        return message.getParentRefId() != null ? message.getParentRefId() : message.getContentRefId();
    }

    /**
     * 논의 정리에서 확정한 분석이 이미 다룬 Slack 채널 메시지 ID. (분석이 다룬 마지막 메시지 시각까지)
     * 확정 뒤에 새로 달린 메시지는 다시 후보가 될 수 있다.
     */
    private Set<Long> coveredByConfirmedAnalysis(Collection<ContentRef> messages) {
        List<ContentRef> channel = messages.stream()
                .filter(m -> m.getProvider() == Provider.SLACK && m.getOwnerUserId() == null).toList();
        if (channel.isEmpty()) {
            return Set.of();
        }
        Map<Long, LocalDateTime> coverage = analysisService.getConfirmedCoverage(
                channel.stream().map(SuggestionService::threadRootId).distinct().toList());
        return channel.stream()
                .filter(m -> {
                    LocalDateTime until = coverage.get(threadRootId(m));
                    return until != null && (m.getCreatedAt() == null || !m.getCreatedAt().isAfter(until));
                })
                .map(ContentRef::getContentRefId)
                .collect(Collectors.toSet());
    }

    /**
     * 내 Gmail을 먼저 가져온다. (C의 GmailContentService가 5분 넘게 동기화하지 않았으면 증분 동기화한다)
     * Gmail은 개인 서비스라 처음 한 번은 누군가 불러줘야 동기화가 시작된다. 연결이 없거나 실패하면 Slack만 본다.
     */
    private void syncGmail(Long workspaceId, Long userId) {
        try {
            gmailContentService.getContentsByUser(workspaceId, userId);
        } catch (RuntimeException e) {
            log.info("Gmail 동기화 건너뜀, Slack 메시지만 살펴봄: workspaceId={}, error={}", workspaceId,
                    e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
        }
    }

    private static String scanKey(Long workspaceId, Long userId) {
        return workspaceId + ":" + userId;
    }

    private static LocalDateTime revision(ContentRef source) {
        return source.getLastModifiedAt() != null ? source.getLastModifiedAt()
                : source.getCreatedAt() != null ? source.getCreatedAt() : LocalDateTime.MIN;
    }

    /**
     * Google Calendar가 보낸 초대·변경·취소·응답 메일. 이미 캘린더에 있는 일정이라 후보로 다시 내지 않는다.
     * (예전에는 메일 속 UTC 시간을 그대로 읽어 9시간 틀린 후보가 생기고, 확정한 회의를 다시 제안했다)
     */
    static boolean isCalendarInvitation(ContentRef message) {
        if (message.getProvider() != Provider.GMAIL) {
            return false;
        }
        String title = Objects.requireNonNullElse(message.getTitle(), "").strip();
        String content = Objects.requireNonNullElse(message.getContent(), "");
        boolean calendarMail = content.contains("calendar.google.com/calendar")
                || Objects.requireNonNullElse(message.getAuthorName(), "").contains("calendar-notification@google.com");
        return calendarMail && CALENDAR_SUBJECT.matcher(title).find();
    }

    private static final java.util.regex.Pattern CALENDAR_SUBJECT = java.util.regex.Pattern.compile(
            "^(Invitation|Updated invitation|Canceled event|Cancelled event|Accepted|Declined|Tentatively accepted"
                    + "|초대장|업데이트된 초대장|취소된 일정|일정 취소|수락함|거절함|미정)[^:：]*[:：]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 이미 확정한 회의와 같은 날, 비슷한 제목의 일정 후보는 내지 않는다. */
    List<Suggestion> excludeConfirmedMeetings(Long workspaceId, List<Suggestion> candidates) {
        if (candidates.stream().noneMatch(c -> c.getType() == SuggestionType.SCHEDULE)) {
            return candidates;
        }
        LocalDateTime now = LocalDateTime.now(SlackApiClient.ZONE);
        List<com.togather.meeting.domain.Meeting> confirmed = meetingRepository.findAllByWorkspaceIdAndStatusAndStartAtBetween(
                workspaceId, com.togather.common.enums.MeetingStatus.CONFIRMED, now.minusDays(LOOKBACK_DAYS), now.plusDays(90));
        return candidates.stream()
                .filter(c -> c.getType() != SuggestionType.SCHEDULE || confirmed.stream().noneMatch(m ->
                        isSimilar(normalize(m.getTitle()), normalize(c.getTitle()))
                                && (c.getDetectedDatetime() == null || m.getStartAt() == null
                                || c.getDetectedDatetime().toLocalDate().equals(m.getStartAt().toLocalDate()))))
                .toList();
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

    /** 명세서 buildEventData: 끝 시각을 정하지 않으면 1시간으로 등록한다. */
    private static PersonalEventRequest buildEventData(Suggestion suggestion, LocalDateTime endAt) {
        return new PersonalEventRequest(suggestion.getTitle(), suggestion.getDetectedDatetime(), endAt);
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
        if (ref.getProvider() == Provider.SLACK && ref.getOwnerUserId() != null) return "Slack DM";
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
