package com.togather.meeting.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.domain.DateRange;
import com.togather.common.enums.MeetingStatus;
import com.togather.common.enums.SlotPreference;
import com.togather.common.enums.SlotSource;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.meeting.domain.AvailabilitySlot;
import com.togather.meeting.domain.Meeting;
import com.togather.meeting.domain.MeetingAgenda;
import com.togather.meeting.domain.MeetingParticipant;
import com.togather.meeting.domain.MeetingTimeCandidate;
import com.togather.meeting.dto.AvailabilityResponse;
import com.togather.meeting.dto.AvailabilityResponse.SlotResponse;
import com.togather.meeting.dto.AvailabilitySaveRequest;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.dto.MeetingDetailResponse.AgendaResponse;
import com.togather.meeting.dto.MeetingDetailResponse.ParticipantResponse;
import com.togather.meeting.dto.MeetingSummary;
import com.togather.meeting.dto.MeetingSummaryResponse;
import com.togather.meeting.dto.MeetingUpdateRequest;
import com.togather.meeting.dto.ResponseStatusResponse;
import com.togather.meeting.dto.TimeCandidateResponse;
import com.togather.meeting.repository.AvailabilitySlotRepository;
import com.togather.meeting.repository.MeetingAgendaRepository;
import com.togather.meeting.repository.MeetingParticipantRepository;
import com.togather.meeting.repository.MeetingRepository;
import com.togather.meeting.repository.MeetingTimeCandidateRepository;
import com.togather.meeting.service.MeetingTimeRecommender.Scored;
import com.togather.task.dto.TaskSummary;
import com.togather.task.service.TaskService;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import com.togather.workspace.service.WorkspaceMemberService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-06 MeetingService. 회의 생성·수정·취소, 안건, 가용 시간, 시간 추천, 확정 (UC-10-01 ~ 09)
 *
 * <p>권한: 워크스페이스 멤버는 조회, 회의를 만든 사람과 팀장은 수정·취소·안건·확정, 참여자는 자기 가용 시간 입력</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingAgendaRepository agendaRepository;
    private final AvailabilitySlotRepository slotRepository;
    private final MeetingTimeCandidateRepository candidateRepository;
    private final MeetingTimeRecommender recommender;
    private final WorkspaceMemberService workspaceMemberService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserService userService;
    private final TaskService taskService;
    private final CalendarContentService calendarContentService;

    // ===================== 회의 CRUD =====================

    public List<MeetingSummaryResponse> getMeetings(Long workspaceId, Long userId) {
        List<Meeting> meetings = meetingRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        Map<Long, List<MeetingParticipant>> participants = participantRepository
                .findAllByMeetingIdIn(meetings.stream().map(Meeting::getMeetingId).toList())
                .stream().collect(Collectors.groupingBy(MeetingParticipant::getMeetingId));
        return meetings.stream().map(m -> {
            List<MeetingParticipant> list = participants.getOrDefault(m.getMeetingId(), List.of());
            int responded = (int) list.stream().filter(MeetingParticipant::isHasResponded).count();
            boolean participating = list.stream().anyMatch(p -> p.getUserId().equals(userId));
            return MeetingSummaryResponse.of(m, list.size(), responded, participating);
        }).toList();
    }

    public MeetingDetailResponse getMeeting(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        List<MeetingParticipant> participants = participantRepository.findAllByMeetingId(meetingId);
        Map<Long, UserSummary> users = users(participants.stream().map(MeetingParticipant::getUserId).toList());
        List<MeetingAgenda> agendas = agendaRepository.findAllByMeetingIdOrderByOrderNoAsc(meetingId);
        Map<Long, TaskSummary> tasks = taskService.getTasks(agendas.stream().map(MeetingAgenda::getTaskId).toList())
                .stream().collect(Collectors.toMap(TaskSummary::taskId, Function.identity()));
        return new MeetingDetailResponse(
                meeting.getMeetingId(), meeting.getTitle(), meeting.getStatus(), meeting.getDurationMinutes(),
                meeting.getCandidatePeriod().getStart(), meeting.getCandidatePeriod().getEnd(), meeting.getMeetingMode(),
                meeting.getStartAt(), meeting.getEndAt(), meeting.getCalendarRefId(), meeting.getMeetLink(),
                meeting.isCalendarSyncFailed(), meeting.getCreatedBy(), canManage(meeting, userId),
                participants.stream().anyMatch(p -> p.getUserId().equals(userId)),
                participants.stream().map(p -> toParticipant(p, users)).toList(),
                agendas.stream().map(a -> new AgendaResponse(a.getAgendaId(), a.getOrderNo(), a.getTaskId(),
                        tasks.get(a.getTaskId()))).toList());
    }

    /** SCHEDULING 상태로 회의를 만든다. 만든 사람은 참여자에 자동으로 들어간다. */
    @Transactional
    public MeetingDetailResponse createMeeting(Long workspaceId, Long userId, MeetingCreateRequest request) {
        DateRange period = period(request.candidateStartAt(), request.candidateEndAt(), request.durationMinutes());
        Meeting meeting = meetingRepository.save(new Meeting(workspaceId, userId, request.title().strip(),
                request.durationMinutes(), period, blankToNull(request.meetingMode())));

        Set<Long> participantIds = new LinkedHashSet<>();
        participantIds.add(userId);
        if (request.participantIds() != null) {
            participantIds.addAll(request.participantIds());
        }
        validateMembers(workspaceId, participantIds);
        participantRepository.saveAll(participantIds.stream()
                .map(id -> new MeetingParticipant(meeting.getMeetingId(), id)).toList());

        if (request.agendaTaskIds() != null) {
            int order = 1;
            for (Long taskId : new LinkedHashSet<>(request.agendaTaskIds())) {
                validateTask(workspaceId, taskId);
                agendaRepository.save(new MeetingAgenda(meeting.getMeetingId(), taskId, order++));
            }
        }
        return getMeeting(workspaceId, meeting.getMeetingId(), userId);
    }

    @Transactional
    public MeetingDetailResponse updateMeeting(Long workspaceId, Long meetingId, Long userId, MeetingUpdateRequest request) {
        Meeting meeting = findManageableScheduling(workspaceId, meetingId, userId);
        int duration = request.durationMinutes() != null ? request.durationMinutes() : meeting.getDurationMinutes();
        LocalDateTime start = request.candidateStartAt() != null ? request.candidateStartAt() : meeting.getCandidatePeriod().getStart();
        LocalDateTime end = request.candidateEndAt() != null ? request.candidateEndAt() : meeting.getCandidatePeriod().getEnd();
        DateRange period = period(start, end, duration);
        boolean periodChanged = !start.equals(meeting.getCandidatePeriod().getStart())
                || !end.equals(meeting.getCandidatePeriod().getEnd());
        boolean durationChanged = duration != meeting.getDurationMinutes();

        meeting.update(request.title() != null ? request.title().strip() : meeting.getTitle(), duration, period,
                request.meetingMode() != null ? blankToNull(request.meetingMode()) : meeting.getMeetingMode());

        if (request.participantIds() != null) {
            updateParticipants(meeting, userId, request.participantIds());
        }
        if (periodChanged) {
            // 후보 기간이 바뀌면 이전 가용 시간은 의미가 없으므로 모두 다시 받는다.
            slotRepository.deleteAllByMeetingId(meetingId);
            participantRepository.findAllByMeetingId(meetingId).forEach(MeetingParticipant::resetResponse);
        }
        if (periodChanged || durationChanged) {
            candidateRepository.deleteAllByMeetingId(meetingId);
        }
        return getMeeting(workspaceId, meetingId, userId);
    }

    /** 회의를 취소한다. 확정된 회의면 등록된 캘린더 일정도 지운다(실패해도 취소는 진행). */
    @Transactional
    public void cancelMeeting(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        checkManage(meeting, userId);
        if (meeting.getStatus() == MeetingStatus.CANCELED) {
            return;
        }
        if (meeting.getCalendarRefId() != null) {
            try {
                calendarContentService.deleteEvent(workspaceId, userId, meeting.getCalendarRefId());
            } catch (BusinessException e) {
                log.warn("회의 취소 중 캘린더 일정 삭제 실패: meetingId={}, code={}", meetingId, e.getErrorCode());
            }
        }
        meeting.cancel();
    }

    // ===================== 안건 =====================

    /** 안건으로 고를 수 있는 진행 중 Task */
    public List<TaskSummary> getAgendaCandidates(Long workspaceId) {
        return taskService.getTasksInProgress(workspaceId);
    }

    @Transactional
    public MeetingDetailResponse addAgenda(Long workspaceId, Long meetingId, Long userId, Long taskId) {
        Meeting meeting = findManageable(workspaceId, meetingId, userId);
        validateTask(workspaceId, taskId);
        if (!agendaRepository.existsByMeetingIdAndTaskId(meetingId, taskId)) {
            int nextOrder = agendaRepository.findAllByMeetingIdOrderByOrderNoAsc(meetingId).stream()
                    .mapToInt(MeetingAgenda::getOrderNo).max().orElse(0) + 1;
            agendaRepository.save(new MeetingAgenda(meeting.getMeetingId(), taskId, nextOrder));
        }
        return getMeeting(workspaceId, meetingId, userId);
    }

    @Transactional
    public MeetingDetailResponse changeAgenda(Long workspaceId, Long meetingId, Long agendaId, Long userId, Long taskId) {
        findManageable(workspaceId, meetingId, userId);
        validateTask(workspaceId, taskId);
        findAgenda(meetingId, agendaId).changeTask(taskId);
        return getMeeting(workspaceId, meetingId, userId);
    }

    @Transactional
    public MeetingDetailResponse deleteAgenda(Long workspaceId, Long meetingId, Long agendaId, Long userId) {
        findManageable(workspaceId, meetingId, userId);
        agendaRepository.delete(findAgenda(meetingId, agendaId));
        return getMeeting(workspaceId, meetingId, userId);
    }

    // ===================== 가용 시간 =====================

    /** 내 가용 시간과 캘린더 일정으로 자동 불가능 처리할 칸 */
    public AvailabilityResponse getMyAvailability(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        MeetingParticipant participant = findParticipant(meetingId, userId);
        List<SlotResponse> mine = slotRepository.findAllByMeetingIdAndUserId(meetingId, userId).stream()
                .map(s -> new SlotResponse(s.getPeriod().getStart(), s.getPreference(), s.getSource()))
                .toList();
        BusyResult busy = busyCells(workspaceId, userId, meeting.getCandidatePeriod());
        return new AvailabilityResponse(meeting.getCandidatePeriod().getStart(), meeting.getCandidatePeriod().getEnd(),
                MeetingTimeRecommender.SLOT_MINUTES, MeetingTimeRecommender.DAY_START_HOUR,
                MeetingTimeRecommender.DAY_END_HOUR, participant.isHasResponded(), mine, busy.cells(), busy.connected());
    }

    /** 내 가용 시간을 저장한다. 캘린더 일정이 있는 칸은 불가능(CALENDAR_AUTO)으로 덮어쓴다. */
    @Transactional
    public AvailabilityResponse saveAvailability(Long workspaceId, Long meetingId, Long userId,
                                                 AvailabilitySaveRequest request) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        if (!meeting.isScheduling()) {
            throw new BusinessException(ErrorCode.MEETING_NOT_SCHEDULING);
        }
        MeetingParticipant participant = findParticipant(meetingId, userId);
        Set<LocalDateTime> validCells = new HashSet<>(recommender.cells(meeting.getCandidatePeriod()));
        Set<LocalDateTime> busy = new HashSet<>(busyCells(workspaceId, userId, meeting.getCandidatePeriod()).cells());

        slotRepository.deleteAllByMeetingIdAndUserId(meetingId, userId);
        Map<LocalDateTime, SlotPreference> manual = new HashMap<>();
        for (AvailabilitySaveRequest.SlotInput input : request.slots()) {
            if (validCells.contains(input.startAt()) && !busy.contains(input.startAt())) {
                manual.put(input.startAt(), input.preference());
            }
        }
        List<AvailabilitySlot> slots = new ArrayList<>();
        manual.forEach((cell, preference) -> slots.add(slot(meetingId, userId, cell, preference, SlotSource.MANUAL)));
        busy.stream().filter(validCells::contains)
                .forEach(cell -> slots.add(slot(meetingId, userId, cell, SlotPreference.UNAVAILABLE, SlotSource.CALENDAR_AUTO)));
        slotRepository.saveAll(slots);
        participant.markResponded();
        return getMyAvailability(workspaceId, meetingId, userId);
    }

    /** 참여자별 응답 현황 (UC-10-07) */
    public ResponseStatusResponse getResponseStatus(Long workspaceId, Long meetingId) {
        findMeeting(workspaceId, meetingId);
        List<MeetingParticipant> participants = participantRepository.findAllByMeetingId(meetingId);
        Map<Long, UserSummary> users = users(participants.stream().map(MeetingParticipant::getUserId).toList());
        Map<Boolean, List<ParticipantResponse>> split = participants.stream()
                .map(p -> toParticipant(p, users))
                .collect(Collectors.partitioningBy(ParticipantResponse::hasResponded));
        return new ResponseStatusResponse(split.get(true), split.get(false));
    }

    // ===================== 추천 · 확정 =====================

    /** 최적 회의 시간을 계산해 저장하고 반환한다. (UC-10-08) */
    @Transactional
    public List<TimeCandidateResponse> recommendTimes(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        if (!meeting.isScheduling()) {
            throw new BusinessException(ErrorCode.MEETING_NOT_SCHEDULING);
        }
        List<MeetingParticipant> participants = participantRepository.findAllByMeetingId(meetingId);
        Set<Long> responded = participants.stream().filter(MeetingParticipant::isHasResponded)
                .map(MeetingParticipant::getUserId).collect(Collectors.toSet());
        Map<Long, Map<LocalDateTime, SlotPreference>> responses = new HashMap<>();
        responded.forEach(id -> responses.put(id, new HashMap<>()));
        for (AvailabilitySlot slot : slotRepository.findAllByMeetingId(meetingId)) {
            Map<LocalDateTime, SlotPreference> cells = responses.get(slot.getUserId());
            if (cells != null) {
                cells.merge(slot.getPeriod().getStart(), slot.getPreference(),
                        (a, b) -> a == SlotPreference.UNAVAILABLE ? a : b);
            }
        }

        List<Scored> scored = recommender.recommend(meeting.getCandidatePeriod(), meeting.getDurationMinutes(), responses);
        candidateRepository.deleteAllByMeetingId(meetingId);
        List<MeetingTimeCandidate> saved = new ArrayList<>();
        for (Scored s : scored) {
            saved.add(new MeetingTimeCandidate(meetingId, s.startAt(), s.rank(), s.availableCount(), s.nonPreferredCount()));
        }
        Map<Integer, Long> rankCounts = scored.stream().collect(Collectors.groupingBy(Scored::rank, Collectors.counting()));
        candidateRepository.saveAll(saved);

        Map<Long, UserSummary> users = users(participants.stream().map(MeetingParticipant::getUserId).toList());
        List<TimeCandidateResponse> result = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            MeetingTimeCandidate c = saved.get(i);
            List<String> unavailableNames = scored.get(i).unavailableUserIds().stream()
                    .map(users::get).filter(Objects::nonNull).map(UserSummary::name).toList();
            result.add(new TimeCandidateResponse(c.getCandidateId(), c.getRank(), rankCounts.get(c.getRank()) > 1, c.getStartAt(),
                    c.getStartAt().plusMinutes(meeting.getDurationMinutes()), c.getAvailableCount(),
                    c.getNonPreferredCount(), responded.size(), participants.size(), unavailableNames));
        }
        return result;
    }

    /**
     * 회의 시간을 확정하고 팀 캘린더에 Google Meet 링크와 함께 등록한다. (UC-10-09)
     * 캘린더 등록에 실패해도 확정은 유지하고 calendarSyncFailed로 표시한다. 이후 재시도할 수 있다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public MeetingDetailResponse confirmMeeting(Long workspaceId, Long meetingId, Long userId, LocalDateTime startAt) {
        Meeting meeting = findManageableScheduling(workspaceId, meetingId, userId);
        DateRange period = meeting.getCandidatePeriod();
        if (startAt.isBefore(period.getStart()) || startAt.plusMinutes(meeting.getDurationMinutes()).isAfter(period.getEnd())) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
        meeting.confirm(startAt);
        registerCalendar(meeting, userId);
        return getMeeting(workspaceId, meetingId, userId);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public MeetingDetailResponse retryCalendarRegistration(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findManageable(workspaceId, meetingId, userId);
        if (meeting.getStatus() != MeetingStatus.CONFIRMED || meeting.getCalendarRefId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        registerCalendar(meeting, userId);
        return getMeeting(workspaceId, meetingId, userId);
    }

    // ===================== 다른 파트 제공 (docs/shared-interfaces.md) =====================

    /** Task가 안건으로 들어간 회의 (취소된 회의 제외). Task 협업 맥락 조회에서 쓴다. */
    public List<MeetingSummary> getMeetingsByTask(Long taskId) {
        List<Long> meetingIds = agendaRepository.findAllByTaskId(taskId).stream()
                .map(MeetingAgenda::getMeetingId).distinct().toList();
        if (meetingIds.isEmpty()) {
            return List.of();
        }
        return meetingRepository.findAllByMeetingIdInAndStatusNot(meetingIds, MeetingStatus.CANCELED).stream()
                .map(MeetingSummary::from)
                .toList();
    }

    // ===================== 내부 =====================

    private void registerCalendar(Meeting meeting, Long userId) {
        List<Long> participantIds = participantRepository.findAllByMeetingId(meeting.getMeetingId()).stream()
                .map(MeetingParticipant::getUserId).toList();
        try {
            CalendarEventResponse event = calendarContentService.createMeetingEvent(meeting.getWorkspaceId(), userId,
                    meeting.getTitle(), meeting.getStartAt(), meeting.getEndAt(), participantIds, description(meeting));
            meeting.registerCalendarEvent(event.contentRefId(), event.meetLink());
        } catch (BusinessException e) {
            log.warn("회의 캘린더 등록 실패: meetingId={}, code={}", meeting.getMeetingId(), e.getErrorCode());
            meeting.markCalendarSyncFailed();
        }
    }

    private String description(Meeting meeting) {
        List<MeetingAgenda> agendas = agendaRepository.findAllByMeetingIdOrderByOrderNoAsc(meeting.getMeetingId());
        Map<Long, TaskSummary> tasks = taskService.getTasks(agendas.stream().map(MeetingAgenda::getTaskId).toList())
                .stream().collect(Collectors.toMap(TaskSummary::taskId, Function.identity()));
        StringBuilder sb = new StringBuilder("ToGather에서 확정한 회의입니다.");
        if (meeting.getMeetingMode() != null) {
            sb.append("\n진행 방식: ").append(meeting.getMeetingMode());
        }
        if (!agendas.isEmpty()) {
            sb.append("\n\n안건");
            for (MeetingAgenda agenda : agendas) {
                TaskSummary task = tasks.get(agenda.getTaskId());
                sb.append("\n").append(agenda.getOrderNo()).append(". ").append(task == null ? "(삭제된 Task)" : task.title());
            }
        }
        return sb.toString();
    }

    private void updateParticipants(Meeting meeting, Long userId, List<Long> requested) {
        Set<Long> next = new LinkedHashSet<>(requested);
        if (next.isEmpty()) {
            next.add(userId);
        }
        validateMembers(meeting.getWorkspaceId(), next);
        List<MeetingParticipant> current = participantRepository.findAllByMeetingId(meeting.getMeetingId());
        Set<Long> currentIds = current.stream().map(MeetingParticipant::getUserId).collect(Collectors.toSet());
        for (MeetingParticipant p : current) {
            if (!next.contains(p.getUserId())) {
                slotRepository.deleteAllByMeetingIdAndUserId(meeting.getMeetingId(), p.getUserId());
                participantRepository.delete(p);
            }
        }
        next.stream().filter(id -> !currentIds.contains(id))
                .forEach(id -> participantRepository.save(new MeetingParticipant(meeting.getMeetingId(), id)));
    }

    private record BusyResult(List<LocalDateTime> cells, boolean connected) {
    }

    /** 캘린더를 연동하지 않았거나 조회에 실패하면 자동 표시 없이 진행한다. */
    private BusyResult busyCells(Long workspaceId, Long userId, DateRange period) {
        if (!calendarContentService.isCalendarConnected(workspaceId, userId)) {
            return new BusyResult(List.of(), false);
        }
        try {
            List<DateRange> busy = calendarContentService.getBusyTimes(workspaceId, userId, period);
            return new BusyResult(recommender.overlappingCells(recommender.cells(period), busy), true);
        } catch (BusinessException e) {
            log.info("가용 시간 캘린더 자동 표시 생략: userId={}, code={}", userId, e.getErrorCode());
            return new BusyResult(List.of(), false);
        }
    }

    private AvailabilitySlot slot(Long meetingId, Long userId, LocalDateTime cell, SlotPreference preference,
                                  SlotSource source) {
        return new AvailabilitySlot(meetingId, userId, preference, source,
                new DateRange(cell, cell.plusMinutes(MeetingTimeRecommender.SLOT_MINUTES)));
    }

    private DateRange period(LocalDateTime start, LocalDateTime end, int durationMinutes) {
        if (start == null || end == null || start.plusMinutes(durationMinutes).isAfter(end)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
        return new DateRange(start, end);
    }

    private void validateMembers(Long workspaceId, Collection<Long> userIds) {
        Set<Long> members = new HashSet<>(workspaceMemberService.getMemberUserIds(workspaceId));
        if (!members.containsAll(userIds)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    private void validateTask(Long workspaceId, Long taskId) {
        if (!taskService.getTask(taskId).workspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
    }

    private Meeting findMeeting(Long workspaceId, Long meetingId) {
        return meetingRepository.findById(meetingId)
                .filter(m -> m.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_FOUND));
    }

    private Meeting findManageable(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findMeeting(workspaceId, meetingId);
        checkManage(meeting, userId);
        return meeting;
    }

    private Meeting findManageableScheduling(Long workspaceId, Long meetingId, Long userId) {
        Meeting meeting = findManageable(workspaceId, meetingId, userId);
        if (!meeting.isScheduling()) {
            throw new BusinessException(ErrorCode.MEETING_NOT_SCHEDULING);
        }
        return meeting;
    }

    private MeetingParticipant findParticipant(Long meetingId, Long userId) {
        return participantRepository.findByMeetingIdAndUserId(meetingId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_PARTICIPANT));
    }

    private MeetingAgenda findAgenda(Long meetingId, Long agendaId) {
        return agendaRepository.findById(agendaId)
                .filter(a -> a.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENDA_NOT_FOUND));
    }

    private void checkManage(Meeting meeting, Long userId) {
        if (!canManage(meeting, userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_OWNER_REQUIRED);
        }
    }

    /** 회의를 만든 사람 또는 팀장 */
    private boolean canManage(Meeting meeting, Long userId) {
        if (userId.equals(meeting.getCreatedBy())) {
            return true;
        }
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(meeting.getWorkspaceId(), userId)
                .map(WorkspaceMember::isOwner)
                .orElse(false);
    }

    private Map<Long, UserSummary> users(List<Long> userIds) {
        return userService.getUsers(userIds).stream().collect(Collectors.toMap(UserSummary::userId, Function.identity()));
    }

    private static ParticipantResponse toParticipant(MeetingParticipant p, Map<Long, UserSummary> users) {
        UserSummary user = users.get(p.getUserId());
        return new ParticipantResponse(p.getUserId(), user == null ? "(알 수 없음)" : user.name(),
                user == null ? null : user.profileImage(), p.isHasResponded());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
