package com.togather.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContentRefServiceTest extends IntegrationTestSupport {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Autowired
    private ContentRefService contentRefService;

    private Long userId;
    private Long otherUserId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "a@test.com");
        otherUserId = createUser("민준", "b@test.com");
        workspaceId = createWorkspace(userId);
    }

    @Test
    void 같은_원본은_한_번만_저장하고_수정_시각과_작성자를_갱신한다() {
        ContentRefDTO first = contentRefService.upsert(message("1.0", null, "처음", T0, null));
        ContentRefDTO second = contentRefService.upsert(message("1.0", null, "수정됨", T0, T0.plusHours(1)));

        assertThat(second.contentRefId()).isEqualTo(first.contentRefId());
        assertThat(second.content()).isEqualTo("수정됨");
        assertThat(first.lastModifiedAt()).isEqualTo(T0);
        assertThat(second.lastModifiedAt()).isEqualTo(T0.plusHours(1));
    }

    @Test
    void 답글_수를_messageCount로_돌려준다() {
        ContentRefDTO root = contentRefService.upsert(message("1.0", null, "스레드", T0, null));
        contentRefService.upsert(message("1.1", root.contentRefId(), "답글1", T0.plusMinutes(1), null));
        contentRefService.upsert(message("1.2", root.contentRefId(), "답글2", T0.plusMinutes(2), null));

        assertThat(contentRefService.getContentRef(root.contentRefId()).messageCount()).isEqualTo(2);
        assertThat(contentRefService.getContentRefs(List.of(root.contentRefId())).getFirst().messageCount())
                .isEqualTo(2);
    }

    @Test
    void 동기화_실패를_표시하고_다음_동기화에서_해제한다() {
        contentRefService.upsert(message("1.0", null, "본문", T0, null));

        contentRefService.markSyncFailed(workspaceId, Provider.SLACK, "C1:1.0");
        assertThat(contentRefService.findByOrigin(workspaceId, Provider.SLACK, "C1:1.0").orElseThrow().syncFailed())
                .isTrue();

        contentRefService.upsert(message("1.0", null, "본문", T0, null));
        assertThat(contentRefService.findByOrigin(workspaceId, Provider.SLACK, "C1:1.0").orElseThrow().syncFailed())
                .isFalse();
    }

    @Test
    void 개인_콘텐츠는_소유자_것만_조회한다() {
        contentRefService.upsert(mail("m1", userId, T0));
        contentRefService.upsert(mail("m2", otherUserId, T0));
        contentRefService.upsert(mail("m3", userId, T0.minusDays(3)));

        assertThat(contentRefService.getPersonalContents(userId, Provider.GMAIL, null))
                .extracting(ContentRefDTO::externalId).containsExactly("m1", "m3");
        assertThat(contentRefService.getPersonalContents(userId, Provider.GMAIL, T0.minusDays(1)))
                .extracting(ContentRefDTO::externalId).containsExactly("m1");
    }

    @Test
    void 워크스페이스를_지정하면_그_워크스페이스의_개인_콘텐츠만_조회한다() {
        Long otherWorkspaceId = createWorkspace(userId);
        contentRefService.upsert(mail("m1", userId, T0));
        contentRefService.upsert(mail("m2", otherUserId, T0));
        contentRefService.upsert(new ContentRefUpsert(otherWorkspaceId, userId, Provider.GMAIL, ContentType.MAIL,
                "other-ws-mail", null, null, "다른 워크스페이스", "본문", null, T0));
        contentRefService.upsert(mail("m3", userId, T0.minusDays(3)));

        assertThat(contentRefService.getPersonalContents(workspaceId, userId, Provider.GMAIL, null))
                .extracting(ContentRefDTO::externalId).containsExactly("m1", "m3");
        assertThat(contentRefService.getPersonalContents(workspaceId, userId, Provider.GMAIL, T0.minusDays(1)))
                .extracting(ContentRefDTO::externalId).containsExactly("m1");
        assertThat(contentRefService.getPersonalContents(otherWorkspaceId, userId, Provider.GMAIL, null))
                .extracting(ContentRefDTO::externalId).containsExactly("other-ws-mail");
    }

    @Test
    void 원본이_삭제되면_사본을_본문으로_준다() {
        ContentRefDTO saved = contentRefService.upsert(message("1.0", null, "남길 내용", T0, null));

        contentRefService.markOriginDeleted(saved.contentRefId());

        assertThat(contentRefService.getOwner(saved.contentRefId()).isOriginDeleted()).isTrue();
        assertThat(contentRefService.getContentRef(saved.contentRefId()).content()).isEqualTo("남길 내용");
    }

    private ContentRefUpsert message(String ts, Long parentRefId, String text, LocalDateTime createdAt,
                                     LocalDateTime modifiedAt) {
        return new ContentRefUpsert(workspaceId, null, Provider.SLACK, ContentType.MESSAGE, "C1:" + ts, "C1",
                parentRefId, null, text, null, createdAt, modifiedAt, "U1", "철준");
    }

    private ContentRefUpsert mail(String id, Long owner, LocalDateTime at) {
        return new ContentRefUpsert(workspaceId, owner, Provider.GMAIL, ContentType.MAIL, id, null, null,
                "제목", "본문", null, at);
    }
}
