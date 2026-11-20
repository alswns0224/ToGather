package com.togather.content.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.assistant.service.SuggestionService;
import com.togather.assistant.service.SuggestionService.AiCandidate;
import com.togather.assistant.service.SuggestionService.AiResult;
import com.togather.common.ai.GeminiClient;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.domain.ContentRef;
import com.togather.content.gmail.service.GmailContentService;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.AuthInfo;
import com.togather.content.slack.client.SlackApiClient.DirectMessage;
import com.togather.content.slack.client.SlackApiClient.DirectMessagePage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import com.togather.support.IntegrationTestSupport;
import com.togather.sync.service.SyncService;
import com.togather.task.dto.TaskCommand;
import com.togather.task.service.TaskResourceService;
import com.togather.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class SlackDirectMessageServiceTest extends IntegrationTestSupport {
    @MockitoBean private GeminiClient geminiClient;
    @MockitoBean private SlackApiClient slackApiClient;
    @MockitoBean private IntegrationService integrationService;
    @MockitoBean private CalendarContentService calendarContentService;
    @MockitoBean private GmailContentService gmailContentService;
    @Autowired private SlackDirectMessageService direct;
    @Autowired private SlackContentService slack;
    @Autowired private ContentRefService contents;
    @Autowired private ContentRefRepository references;
    @Autowired private SuggestionService suggestions;
    @Autowired private TaskResourceService taskResources;
    @Autowired private TaskService tasks;
    @Autowired private SyncService sync;
    private Long owner;
    private Long other;
    private Long workspace;
    private LocalDateTime now;
    private OwnAccess own;
    private SlackMessage incoming;

    @BeforeEach
    void setUp() {
        owner = createUser("DM 사용자", UUID.randomUUID() + "@test.example");
        other = createUser("다른 팀원", UUID.randomUUID() + "@test.example");
        workspace = createWorkspace(owner);
        addMember(workspace, other, "MEMBER");
        now = LocalDateTime.now(SlackApiClient.ZONE).withNano(0);
        own = new OwnAccess(100L, "T1:U1", "im:read,im:history", "own-token");
        when(integrationService.findOwnAccess(workspace, owner, Provider.SLACK)).thenReturn(Optional.of(own));
        when(slackApiClient.getDirectMessages("own-token")).thenReturn(List.of(new DirectMessage("D1", "U2")));
        when(slackApiClient.authTest(anyString())).thenReturn(new AuthInfo("T1", "U1", "https://test.slack.com/"));
        when(slackApiClient.getUser(anyString(), anyString())).thenAnswer(call -> new SlackUser(call.getArgument(1), "대화 상대", null));
        incoming = message(now.minusHours(2), null, "금요일까지 발표 자료를 준비해 주세요", 0);
        history(List.of(incoming), null);
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of()));
    }

    @Test
    void 상대가_보낸_DM도_본인에게만_저장하고_AI_일정과_할일_후보로_사용한다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("TASK", "발표 자료 준비", "s1", null, "2026-12-01"),
                new AiCandidate("SCHEDULE", "기획 회의", "s1", "2026-12-01T14:00:00", null))));
        var detected = suggestions.detectCandidates(workspace, owner, null);
        assertThat(detected.scannedMessages()).isEqualTo(1);
        assertThat(detected.created()).hasSize(2).allSatisfy(item -> {
            assertThat(item.sourceProvider()).isEqualTo(Provider.SLACK);
            assertThat(item.sourceTitle()).isEqualTo("Slack DM");
            assertThat(item.sourceExcerpt()).contains("발표 자료");
        });
        var ref = mine().getFirst();
        assertThat(ref.getOwnerUserId()).isEqualTo(owner);
        assertThat(ref.getOriginId()).startsWith("dm:" + owner + ":");
        assertThat(suggestions.getSuggestions(workspace, other, null)).isEmpty();
        assertThat(suggestions.getMyTodoList(workspace, owner).personalTasks()).isEmpty();
        verify(calendarContentService, never()).createPersonalEvent(any(), any(), any());
        verify(calendarContentService, never()).createContent(any(), any(), any());
    }

    @Test
    void 팀_자료와_슬랙_논의와_후보목록과_동기화상태에는_DM이_섞이지_않는다() {
        direct.collectForAssistant(workspace, owner);
        var ref = mine().getFirst();
        assertThat(slack.getContents(workspace)).isEmpty();
        assertThat(slack.getUpdatedThreads(workspace, now.minusDays(14))).isEmpty();
        assertThat(slack.findMessagesByUser(workspace, other)).isEmpty();
        assertThatThrownBy(() -> slack.getContentDetail(ref.getContentRefId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> slack.getThreadMessages(ref.getContentRefId(), null)).isInstanceOf(BusinessException.class);
        assertThat(sync.getStatuses(workspace, Provider.SLACK)).isEmpty();
        assertThat(references.searchByKeyword(workspace, List.of(Provider.SLACK), "%발표%", org.springframework.data.domain.PageRequest.of(0, 20))).isEmpty();
        assertThat(references.findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullOrderByCreatedAtDesc(
                workspace, Provider.SLACK, "D1", org.springframework.data.domain.PageRequest.of(0, 20))).isEmpty();
        Long task = tasks.createTask(workspace, new TaskCommand("팀 작업", null, owner, null, com.togather.common.enums.TaskStatus.IN_PROGRESS, List.of(), null));
        assertThat(taskResources.getCandidates(workspace, owner, task, Provider.SLACK, "")).isEmpty();
        assertThatThrownBy(() -> taskResources.link(workspace, owner, task, ref.getContentRefId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void DM_후보를_팀_Task로_승인해도_비공개_원문을_자동_연결하거나_설명에_복사하지_않는다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiCandidate("TASK", "발표 자료 준비", "s1", null, null))));
        var candidate = suggestions.detectCandidates(workspace, owner, null).created().getFirst();
        Long task = suggestions.approveTeamTask(workspace, owner, candidate.suggestionId(), other);
        assertThat(tasks.getTask(task).assigneeId()).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT description FROM task WHERE task_id=?", String.class, task))
                .doesNotContain(incoming.text());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_content_link WHERE task_id=?", Long.class, task)).isZero();
    }

    @Test
    void 같은_대화를_두_사용자가_수집해도_소유자와_원본키가_충돌하지_않는다() {
        when(integrationService.findOwnAccess(workspace, other, Provider.SLACK)).thenReturn(Optional.of(new OwnAccess(200L, "T1:U2", "im:read im:history", "other-token")));
        when(slackApiClient.getDirectMessages("other-token")).thenReturn(List.of(new DirectMessage("D1", "U1")));
        when(slackApiClient.getDirectMessageHistory(eq("other-token"), eq("D1"), any(), any(), any())).thenReturn(new DirectMessagePage(List.of(incoming), null));
        var first = direct.collectForAssistant(workspace, owner).messages().getFirst();
        var second = direct.collectForAssistant(workspace, other).messages().getFirst();
        assertThat(first.getContentRefId()).isNotEqualTo(second.getContentRefId());
        assertThat(first.getOriginId()).isNotEqualTo(second.getOriginId());
        assertThat(first.getOwnerUserId()).isEqualTo(owner);
        assertThat(second.getOwnerUserId()).isEqualTo(other);
        assertThatThrownBy(() -> contents.upsert(new ContentRefUpsert(workspace, other, Provider.SLACK, ContentType.MESSAGE,
                first.getOriginId(), "D1", null, "침범", "침범", null, now, now, "U2", null))).isInstanceOf(BusinessException.class);
    }

    @Test
    void 본인_연결이나_DM권한이_없으면_팀원_토큰을_대신_쓰지_않고_안내한다() {
        assertThat(direct.collectForAssistant(workspace, other).warnings()).anyMatch(text -> text.contains("본인 Slack"));
        when(integrationService.findOwnAccess(workspace, owner, Provider.SLACK)).thenReturn(Optional.of(new OwnAccess(100L, "T1:U1", "channels:history", "own-token")));
        assertThat(direct.collectForAssistant(workspace, owner).warnings()).anyMatch(text -> text.contains("권한"));
        verify(slackApiClient, never()).getDirectMessages(anyString());
        verify(integrationService, never()).findAccessToken(any(), any(), any());
    }

    @Test
    void 비멤버와_다른워크스페이스에서는_수집할_수_없다() {
        Long outsider = createUser("외부", UUID.randomUUID() + "@test.example");
        Long anotherWorkspace = createWorkspace(other);
        assertThatThrownBy(() -> direct.collectForAssistant(workspace, outsider)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> direct.collectForAssistant(anotherWorkspace, owner)).isInstanceOf(BusinessException.class);
        verify(slackApiClient, never()).getDirectMessages(anyString());
    }

    @Test
    void 페이지가_잘리면_커서와_시각을_보존하고_재개_후에만_삭제를_판단한다() {
        String longCursor = "opaque-" + "x".repeat(500);
        SlackMessage older = message(now.minusHours(3), null, "더 오래된 요청", 0);
        history(List.of(incoming), longCursor);
        assertThat(direct.collectForAssistant(workspace, owner).warnings()).isNotEmpty();
        assertThat(mine()).hasSize(1);
        String cursor = jdbc.queryForObject("SELECT sync_cursor FROM sync_state WHERE workspace_id=? AND target_id LIKE '%:D1'", String.class, workspace);
        assertThat(cursor).hasSizeGreaterThan(500);
        force();
        when(slackApiClient.getDirectMessageHistory(eq("own-token"), eq("D1"), any(), any(), eq(longCursor)))
                .thenReturn(new DirectMessagePage(List.of(older), null));
        direct.collectForAssistant(workspace, owner);
        assertThat(mine()).hasSize(2);
        ArgumentCaptor<LocalDateTime> latest = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(slackApiClient).getDirectMessageHistory(eq("own-token"), eq("D1"), any(), latest.capture(), isNull());
        verify(slackApiClient).getDirectMessageHistory(eq("own-token"), eq("D1"), any(), eq(latest.getValue()), eq(longCursor));
    }

    @Test
    void 증분_수집은_수정과_삭제를_반영하며_실패하면_기존_메시지와_기준시각을_유지한다() {
        var first = direct.collectForAssistant(workspace, owner).messages().getFirst();
        force();
        var edited = new SlackMessage(incoming.ts(), null, "U2", "수정한 요청", 0, null, incoming.createdAt(), now.minusMinutes(1), List.of(), null);
        history(List.of(edited), null);
        assertThat(direct.collectForAssistant(workspace, owner).messages().getFirst().getContent()).isEqualTo("수정한 요청");
        force();
        when(slackApiClient.getDirectMessageHistory(eq("own-token"), eq("D1"), any(), any(), any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        assertThat(direct.collectForAssistant(workspace, owner).messages()).hasSize(1);
        assertThat(contents.getOwner(first.getContentRefId()).isOriginDeleted()).isFalse();
        force(); history(List.of(), null);
        assertThat(direct.collectForAssistant(workspace, owner).messages()).isEmpty();
        assertThat(contents.getOwner(first.getContentRefId()).isOriginDeleted()).isTrue();
    }

    @Test
    void 재시도_간격과_요청한도_대기시간을_지킨다() {
        direct.collectForAssistant(workspace, owner);
        clearInvocations(slackApiClient);
        direct.collectForAssistant(workspace, owner);
        verify(slackApiClient, never()).getDirectMessages(anyString());
        force();
        when(slackApiClient.getDirectMessages("own-token")).thenThrow(new SlackApiClient.RateLimitException(600));
        assertThat(direct.collectForAssistant(workspace, owner).warnings()).anyMatch(text -> text.contains("요청 한도"));
        clearInvocations(slackApiClient);
        jdbc.update("UPDATE sync_state SET last_attempt_at=now()-interval '6 minutes' WHERE workspace_id=?", workspace);
        direct.collectForAssistant(workspace, owner);
        verify(slackApiClient, never()).getDirectMessages(anyString());
    }

    @Test
    void 한_번_수집한_사용자는_자동수집되며_계정변경_후_이전계정_DM을_분석하지_않는다() {
        direct.collectForAssistant(workspace, owner); force();
        clearInvocations(slackApiClient); direct.syncKnownOwners();
        verify(slackApiClient).getDirectMessages("own-token");
        when(integrationService.findOwnAccess(workspace, owner, Provider.SLACK)).thenReturn(Optional.of(new OwnAccess(100L, "T2:U9", "im:read,im:history", "new-token")));
        when(slackApiClient.getDirectMessages("new-token")).thenReturn(List.of());
        assertThat(direct.collectForAssistant(workspace, owner).messages()).isEmpty();
    }

    @Test
    void DM_스레드의_답글도_개인_소유로_수집한다() {
        SlackMessage root = message(now.minusHours(2), null, "논의 시작", 1);
        SlackMessage reply = message(now.minusHours(1), root.ts(), "답글에서 부탁한 작업", 0);
        history(List.of(root), null);
        direct.collectForAssistant(workspace, owner); force();
        when(slackApiClient.getDirectMessageReplies(eq("own-token"), eq("D1"), eq(root.ts()), any(), any(), any()))
                .thenReturn(new DirectMessagePage(List.of(root, reply), null));
        assertThat(direct.collectForAssistant(workspace, owner).messages()).hasSize(2);
        assertThat(mine()).filteredOn(ref -> ref.getParentRefId() != null).singleElement().satisfies(ref -> {
            assertThat(ref.getOwnerUserId()).isEqualTo(owner); assertThat(ref.getContent()).contains("답글");
        });
    }

    @Test
    void 요청예산을_넘는_DM은_다음차례에_이어_받으며_처음대화만_반복하지_않는다() {
        when(slackApiClient.getDirectMessages("own-token")).thenReturn(java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new DirectMessage("D" + index, "U2")).toList());
        when(slackApiClient.getDirectMessageHistory(eq("own-token"), anyString(), any(), any(), any()))
                .thenReturn(new DirectMessagePage(List.of(incoming), null));
        var first = direct.collectForAssistant(workspace, owner);
        assertThat(first.messages()).hasSize(6);
        assertThat(first.warnings()).isNotEmpty();
        jdbc.update("UPDATE sync_state SET last_attempt_at=NULL WHERE workspace_id=? AND target_id=?",
                workspace, SlackDirectMessageService.accountTarget(own));
        clearInvocations(slackApiClient);
        var second = direct.collectForAssistant(workspace, owner);
        assertThat(second.messages()).hasSize(7);
        verify(slackApiClient).getDirectMessageHistory(eq("own-token"), eq("D7"), any(), any(), any());
        verify(slackApiClient, never()).getDirectMessageHistory(eq("own-token"), eq("D1"), any(), any(), any());
    }

    @Test
    void 개인_DM의_팀_연결조회는_본인과_다른팀원에게_모두_거절한다() throws Exception {
        direct.collectForAssistant(workspace, owner);
        var context = applicationContext();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        for (Long user : List.of(owner, other)) {
            var session = new org.springframework.mock.web.MockHttpSession(context.getServletContext());
            session.setAttribute(com.togather.common.auth.LoginUserArgumentResolver.SESSION_USER_ID, user);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/workspaces/" + workspace + "/contents/" + mine().getFirst().getContentRefId() + "/tasks").session(session))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/workspaces/" + workspace + "/assistant/suggestions/detect"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Autowired private org.springframework.web.context.WebApplicationContext webContext;
    private org.springframework.web.context.WebApplicationContext applicationContext() { return webContext; }

    @Test
    void 나중에_수집한_과거_DM과_60건_밖의_메시지도_후보_탐지에서_빠지지_않는다() {
        suggestions.detectCandidates(workspace, owner, null);
        force();
        SlackMessage older = message(now.minusDays(2), null, "뒤늦게 받은 과거 요청", 0);
        history(List.of(incoming, older), null);
        assertThat(suggestions.detectCandidates(workspace, owner, null).scannedMessages()).isEqualTo(1);
        assertThat(suggestions.detectCandidates(workspace, owner, null).scannedMessages()).isZero();
        for (int i = 0; i < 65; i++) contents.upsert(new ContentRefUpsert(workspace, owner, Provider.GMAIL, ContentType.MAIL,
                "mail-" + i, null, null, "요청 " + i, "해야 할 작업", null, now.minusMinutes(i), now.minusMinutes(i), null, null));
        assertThat(suggestions.detectCandidates(workspace, owner, null).scannedMessages()).isEqualTo(60);
        assertThat(suggestions.detectCandidates(workspace, owner, null).scannedMessages()).isEqualTo(5);
    }

    private SlackMessage message(LocalDateTime at, String threadTs, String text, int replies) {
        return new SlackMessage(SlackApiClient.toTs(at), threadTs, "U2", text, replies, null, at, null, List.of(), null);
    }
    private void history(List<SlackMessage> messages, String cursor) {
        when(slackApiClient.getDirectMessageHistory(eq("own-token"), eq("D1"), any(), any(), any()))
                .thenReturn(new DirectMessagePage(messages, cursor));
    }
    private List<ContentRef> mine() {
        return references.findPrivateSlackMessages(workspace, owner, "dm:%", now.minusDays(14));
    }
    private void force() { jdbc.update("UPDATE sync_state SET last_attempt_at=NULL WHERE workspace_id=?", workspace); }
}
