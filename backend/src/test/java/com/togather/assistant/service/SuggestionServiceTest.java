package com.togather.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.assistant.dto.DetectResultResponse;
import com.togather.assistant.dto.MyTodoListDTO;
import com.togather.assistant.dto.SuggestionDTO;
import com.togather.assistant.dto.SuggestionUpdateRequest;
import com.togather.assistant.service.SuggestionService.AiCandidate;
import com.togather.assistant.service.SuggestionService.AiResult;
import com.togather.common.ai.GeminiClient;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.enums.SuggestionType;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class SuggestionServiceTest extends IntegrationTestSupport {

    @MockitoBean
    private GeminiClient geminiClient;
    @MockitoBean
    private SlackApiClient slackApiClient;
    @MockitoBean
    private IntegrationService integrationService;
    @MockitoBean
    private CalendarContentService calendarContentService;

    @Autowired
    private SuggestionService suggestionService;
    @Autowired
    private ContentRefService contentRefService;

    private Long userId;
    private Long otherId;
    private Long workspaceId;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        otherId = createUser("민준", "mj@test.com");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, otherId, "MEMBER");
        now = LocalDateTime.now(SlackApiClient.ZONE).withNano(0);
        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK)))
                .thenReturn(Optional.of("token"));
        when(slackApiClient.findUserByEmail("token", "cj@test.com")).thenReturn(new SlackUser("U1", "철준", "cj@test.com"));
        when(slackApiClient.findUserByEmail("token", "mj@test.com")).thenReturn(new SlackUser("U2", "민준", "mj@test.com"));

        save(Provider.SLACK, ContentType.MESSAGE, "C1:1", null, "U2", "민준", "<@U1> 금요일 3시에 기획 회의해요", now.minusHours(3));
        save(Provider.SLACK, ContentType.MESSAGE, "C1:2", null, "U1", "철준", "제가 발표 자료 초안 만들게요", now.minusHours(2));
        save(Provider.SLACK, ContentType.MESSAGE, "C1:3", null, "U2", "민준", "다른 사람 이야기", now.minusHours(1));
        save(Provider.GMAIL, ContentType.MAIL, "mail-1", userId, null, "교수님", "다음 주 중에 면담 가능할까요?", now.minusHours(4));
        save(Provider.GMAIL, ContentType.MAIL, "mail-2", otherId, null, "누군가", "민준의 메일", now.minusHours(4));
    }

    @Test
    void 본인_관련_메시지에서_후보를_찾고_같은_메시지는_다시_보지_않는다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("SCHEDULE", "기획 회의", "s?", "2026-10-16T15:00", null),
                new AiCandidate("TASK", "발표 자료 초안 작성", "s2", null, "2026-10-15"),
                new AiCandidate("SCHEDULE", "교수님 면담", "s3", null, null))));

        DetectResultResponse result = suggestionService.detectCandidates(workspaceId, userId, null);

        assertThat(result.scannedMessages()).isEqualTo(3); // 내 멘션, 내 메시지, 내 메일 (남의 메시지·메일 제외)
        assertThat(result.created()).extracting(SuggestionDTO::title)
                .containsExactlyInAnyOrder("발표 자료 초안 작성", "교수님 면담"); // 근거 번호가 틀린 후보는 버림
        SuggestionDTO meeting = result.created().stream().filter(s -> s.title().equals("교수님 면담")).findFirst()
                .orElseThrow();
        assertThat(meeting.timeUndetermined()).isTrue();
        assertThat(meeting.sourceProvider()).isEqualTo(Provider.GMAIL);

        DetectResultResponse again = suggestionService.detectCandidates(workspaceId, userId, null);
        assertThat(again.scannedMessages()).isEqualTo(1); // 후보가 없던 멘션 메시지만 다시 본다
    }

    @Test
    void 이미_있는_할_일과_같은_후보는_제외한다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("TASK", "발표 자료 초안 작성", "s2", null, null))));
        SuggestionDTO first = suggestionService.detectCandidates(workspaceId, userId, null).created().getFirst();
        suggestionService.approveTask(workspaceId, userId, first.suggestionId());

        save(Provider.SLACK, ContentType.MESSAGE, "C1:4", null, "U1", "철준", "발표 자료 초안 작성 다시 확인", now);
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("TASK", "발표 자료 초안 작성!", "s1", null, null))));

        assertThat(suggestionService.detectCandidates(workspaceId, userId, null).created()).isEmpty();
        MyTodoListDTO todos = suggestionService.getMyTodoList(workspaceId, userId);
        assertThat(todos.personalTasks()).extracting(t -> t.title()).containsExactly("발표 자료 초안 작성");
    }

    @Test
    void 시간이_미정인_일정은_일시를_정해야_승인할_수_있다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("SCHEDULE", "교수님 면담", "s3", null, null))));
        SuggestionDTO suggestion = suggestionService.detectCandidates(workspaceId, userId, SuggestionType.SCHEDULE)
                .created().getFirst();

        assertThatThrownBy(() -> suggestionService.approveSchedule(workspaceId, userId, suggestion.suggestionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUGGESTION_TIME_REQUIRED));
        verify(calendarContentService, never()).createPersonalEvent(any(), any(), any());

        LocalDateTime at = LocalDateTime.of(2026, 10, 20, 14, 0);
        suggestionService.updateSuggestion(workspaceId, userId, suggestion.suggestionId(),
                new SuggestionUpdateRequest("교수님 면담", at, null));
        when(calendarContentService.createPersonalEvent(eq(workspaceId), eq(userId), any())).thenReturn(
                new ContentRefDTO(5L, Provider.GOOGLE_CALENDAR, ContentType.EVENT, "e", "교수님 면담", null, null, null,
                        null, 0, false));

        assertThat(suggestionService.approveSchedule(workspaceId, userId, suggestion.suggestionId())).isEqualTo(5L);
        assertThat(suggestionService.getSuggestions(workspaceId, userId, null)).isEmpty();
    }

    @Test
    void 다른_사람의_후보는_볼_수도_고칠_수도_없다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("TASK", "발표 자료 초안 작성", "s2", null, LocalDate.now().toString()))));
        SuggestionDTO mine = suggestionService.detectCandidates(workspaceId, userId, null).created().getFirst();

        assertThat(suggestionService.getSuggestions(workspaceId, otherId, null)).isEmpty();
        assertThatThrownBy(() -> suggestionService.dismissSuggestion(workspaceId, otherId, mine.suggestionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUGGESTION_NOT_FOUND));

        suggestionService.dismissSuggestion(workspaceId, userId, mine.suggestionId());
        assertThat(suggestionService.getSuggestions(workspaceId, userId, SuggestionType.TASK)).isEmpty();
    }

    @Test
    void 제목_유사도() {
        assertThat(SuggestionService.isSimilar(SuggestionService.normalize("발표 자료 작성"),
                SuggestionService.normalize("발표자료작성!"))).isTrue();
        assertThat(SuggestionService.isSimilar(SuggestionService.normalize("회의"),
                SuggestionService.normalize("회의록 공유"))).isFalse();
    }

    private ContentRefDTO save(Provider provider, ContentType type, String originId, Long owner, String author,
                               String authorName, String text, LocalDateTime at) {
        return contentRefService.upsert(new ContentRefUpsert(workspaceId, owner, provider, type,
                originId + ":" + workspaceId, provider == Provider.SLACK ? "C1" : null, null,
                provider == Provider.SLACK ? "#general" : "메일 제목", text, null, at, at, author, authorName));
    }
}
