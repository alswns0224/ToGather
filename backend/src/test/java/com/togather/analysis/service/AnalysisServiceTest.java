package com.togather.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.analysis.dto.AnalysisItemDTO;
import com.togather.analysis.dto.AnalysisItemRequest;
import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.AnalysisItemUpdateRequest;
import com.togather.analysis.dto.AnalysisResultDTO;
import com.togather.analysis.service.DiscussionAnalyzer.AiItem;
import com.togather.analysis.service.DiscussionAnalyzer.AiResult;
import com.togather.common.ai.GeminiClient;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
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

/** 논의 분석 → 검토 → 확정 흐름. Gemini와 Slack은 가짜로 바꾼다. */
class AnalysisServiceTest extends IntegrationTestSupport {

    private static final String TOKEN = "xoxp-test";

    @MockitoBean
    private GeminiClient geminiClient;
    @MockitoBean
    private SlackApiClient slackApiClient;
    @MockitoBean
    private IntegrationService integrationService;

    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private ContentRefService contentRefService;

    private Long userId;
    private Long memberId;
    private Long workspaceId;
    private ContentRefDTO root;
    private ContentRefDTO reply;
    private LocalDateTime t0;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        memberId = createUser("민준", "mj@test.com");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, memberId, "MEMBER");
        t0 = LocalDateTime.now(SlackApiClient.ZONE).minusHours(2).withNano(0);
        root = slack("1.0", null, "U1", "철준", "발표 자료는 누가 만들까요?", t0);
        reply = slack("1.1", root.contentRefId(), "U2", "민준", "제가 금요일까지 만들게요", t0.plusMinutes(5));

        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK)))
                .thenReturn(Optional.of(TOKEN));
        when(slackApiClient.getUser(TOKEN, "U2")).thenReturn(new SlackUser("U2", "민준", "mj@test.com"));
    }

    @Test
    void 스레드를_분석해_항목과_근거를_저장하고_담당자를_팀원으로_연결한다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("FOLLOW_UP", "민준이 발표 자료 작성", List.of("m2"), "U2", "2026-10-16", null, List.of()),
                new AiItem("DECISION", "발표 자료는 민준 담당", List.of("m1", "m2"), null, null, null, null),
                new AiItem("WRONG", "형식이 틀린 항목", List.of(), null, null, null, null)), true));

        AnalysisResultDTO result = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);

        assertThat(result.status()).isEqualTo(AnalysisStatus.REVIEWING);
        assertThat(result.items()).hasSize(2);
        AnalysisItemResponse followUp = result.items().getFirst();
        assertThat(followUp.suggestedAssigneeId()).isEqualTo(memberId);
        assertThat(followUp.suggestedDueDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(followUp.undetermined()).isFalse();
        assertThat(followUp.evidences()).extracting(e -> e.contentRefId()).containsExactly(reply.contentRefId());
        assertThat(result.items().get(1).evidences()).hasSize(2);
        assertThat(result.messages()).allMatch(m -> m.analyzed());
    }

    @Test
    void 새_메시지가_없으면_다시_분석하지_않고_있으면_새_메시지만_분석한다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "결정", List.of("m1"), null, null, null, null)), true));
        AnalysisResultDTO first = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);

        AnalysisResultDTO again = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        assertThat(again.analysisId()).isEqualTo(first.analysisId());

        slack("1.2", root.contentRefId(), "U1", "철준", "그럼 회의는 다음 주 화요일에 해요", t0.plusMinutes(20));
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("MEETING_PROPOSAL", "다음 주 화요일 회의", List.of("m3"), null, null, null, null)), true));
        AnalysisResultDTO second = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);

        // 확정 전 분석에 이어서 추가된다. 검토하던 항목이 목록에서 사라지지 않는다.
        assertThat(second.analysisId()).isEqualTo(first.analysisId());
        assertThat(second.items()).extracting(AnalysisItemResponse::content)
                .containsExactly("결정", "다음 주 화요일 회의");
        assertThat(analysisService.getPendingAnalyses(workspaceId)).hasSize(1);
        verify(geminiClient).generateJson(argThat(prompt -> prompt.contains("m3 (새 메시지)")
                && prompt.contains("m1 (이전 메시지)") && prompt.contains("DECISION: 결정")), eq(AiResult.class));
    }

    @Test
    void 확정한_뒤_새_메시지는_새_분석으로_만든다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "결정", List.of("m1"), null, null, null, null)), true));
        AnalysisResultDTO first = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        analysisService.confirmAnalysis(workspaceId, first.analysisId());

        slack("1.2", root.contentRefId(), "U1", "철준", "추가 논의", t0.plusMinutes(20));
        AnalysisResultDTO second = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);

        assertThat(second.analysisId()).isNotEqualTo(first.analysisId());
    }

    @Test
    void 이어서_분석하다_실패하면_검토하던_항목은_그대로_남는다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "결정", List.of("m1"), null, null, null, null)), true));
        AnalysisResultDTO first = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        slack("1.2", root.contentRefId(), "U1", "철준", "추가 논의", t0.plusMinutes(20));
        when(geminiClient.generateJson(anyString(), eq(AiResult.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_CALL_FAILED));

        assertThatThrownBy(() -> analysisService.analyzeThread(workspaceId, root.contentRefId(), userId))
                .isInstanceOf(BusinessException.class);

        AnalysisResultDTO after = analysisService.getAnalysis(workspaceId, first.analysisId());
        assertThat(after.status()).isEqualTo(AnalysisStatus.REVIEWING);
        assertThat(after.items()).hasSize(1);
    }

    @Test
    void Gemini가_실패하면_FAILED로_남기고_다시_시도할_수_있다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_CALL_FAILED));

        assertThatThrownBy(() -> analysisService.analyzeThread(workspaceId, root.contentRefId(), userId))
                .isInstanceOf(BusinessException.class);
        assertThat(analysisService.getPendingAnalyses(workspaceId)).extracting(s -> s.status())
                .containsExactly(AnalysisStatus.FAILED);

        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(), true));
        AnalysisResultDTO retried = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        assertThat(retried.status()).isEqualTo(AnalysisStatus.NO_CONTENT);
    }

    @Test
    void 검토_후_확정하면_더_이상_수정할_수_없다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(), true));
        AnalysisResultDTO empty = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        assertThat(empty.status()).isEqualTo(AnalysisStatus.NO_CONTENT);

        // 빈 양식에서 직접 작성
        AnalysisItemResponse added = analysisService.addItem(workspaceId, empty.analysisId(),
                new AnalysisItemRequest(ItemType.FOLLOW_UP, "자료 조사", null, null, null, null));
        assertThat(added.undetermined()).isTrue();
        analysisService.linkEvidence(workspaceId, added.itemId(), reply.contentRefId());

        AnalysisItemResponse updated = analysisService.updateItem(workspaceId, added.itemId(),
                new AnalysisItemUpdateRequest("자료 조사", memberId, LocalDate.of(2026, 10, 20), null, null));
        assertThat(updated.undetermined()).isFalse();
        assertThat(updated.suggestedAssigneeName()).isEqualTo("민준");

        analysisService.confirmAnalysis(workspaceId, empty.analysisId());
        assertThat(analysisService.getPendingAnalyses(workspaceId)).isEmpty();
        assertThat(analysisService.getAnalyses(workspaceId, true)).extracting(s -> s.status())
                .containsExactly(AnalysisStatus.CONFIRMED); // 분석 목록 화면은 확정된 것도 보여준다
        assertThatThrownBy(() -> analysisService.deleteItem(workspaceId, added.itemId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_ALREADY_CONFIRMED));

        AnalysisItemDTO summary = analysisService.getItemSummary(added.itemId());
        assertThat(summary.evidenceContentRefIds()).containsExactly(reply.contentRefId());
    }

    @Test
    void 다른_스레드의_메시지는_근거로_연결할_수_없고_팀원이_아니면_담당자가_될_수_없다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "결정", List.of("m1"), null, null, null, null)), true));
        AnalysisResultDTO result = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
        Long itemId = result.items().getFirst().itemId();
        ContentRefDTO other = slack("2.0", null, "U1", "철준", "다른 스레드", t0);
        Long stranger = createUser("외부인", "x@test.com");

        assertThatThrownBy(() -> analysisService.linkEvidence(workspaceId, itemId, other.contentRefId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVIDENCE_NOT_IN_THREAD));
        assertThatThrownBy(() -> analysisService.updateItem(workspaceId, itemId,
                new AnalysisItemUpdateRequest("결정", stranger, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 확정_전_취소하면_분석이_사라진다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "결정", List.of("m1"), null, null, null, null)), true));
        AnalysisResultDTO result = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);

        analysisService.discardAnalysis(workspaceId, result.analysisId());

        assertThat(analysisService.findLatestAnalysisId(workspaceId, root.contentRefId())).isEmpty();
    }

    @Test
    void 자동_분석은_조용해진_스레드만_분석한다() {
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(), true));
        ContentRefDTO active = slack("3.0", null, "U1", "철준", "지금 얘기 중", LocalDateTime.now(SlackApiClient.ZONE));
        slack("3.1", active.contentRefId(), "U2", "민준", "네", LocalDateTime.now(SlackApiClient.ZONE));

        int count = analysisService.collectAndAnalyze(workspaceId, t0.minusDays(1), 5);

        assertThat(count).isEqualTo(1); // root 스레드만 (2시간 전에 끝남)
        assertThat(analysisService.findLatestAnalysisId(workspaceId, active.contentRefId())).isEmpty();
        verify(integrationService, never()).getAccessToken(any(), any(), any());
    }

    @Test
    void 답글은_분석_대상이_아니다() {
        assertThatThrownBy(() -> analysisService.analyzeThread(workspaceId, reply.contentRefId(), userId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_TARGET_INVALID));
    }

    private ContentRefDTO slack(String ts, Long parent, String author, String name, String text, LocalDateTime at) {
        return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK, ContentType.MESSAGE,
                "C1:" + ts + ":" + workspaceId, "C1", parent, "#general", text, null, at, at, author, name));
    }
}
