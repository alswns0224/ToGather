package com.togather.content.domain;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * C-00-07. 외부 콘텐츠(Slack 메시지, Notion 페이지, Drive 파일, Calendar 일정, Gmail 메일)의 공통 참조.
 * createdAt은 외부 원본의 생성 시각이므로 BaseTimeEntity를 상속하지 않는다.
 * Gmail/Calendar 같은 개인 콘텐츠는 ownerUserId 조건이 있는 Repository 메서드로만 조회한다.
 */
@Getter
@Entity
@Table(name = "content_ref")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long contentRefId;

    private Long workspaceId;

    /** 개인 콘텐츠(Gmail, Calendar)의 소유자. 팀 콘텐츠는 null */
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    private ContentType contentType;

    private String originId;

    private String channelId;

    private Long parentRefId;

    private String content;

    private boolean isOriginDeleted;

    private String snapshot;

    private String title;

    private String deepLink;

    private LocalDateTime createdAt;

    /** 외부 원본의 최종 수정 시각. 모르면 createdAt과 같다. */
    private LocalDateTime lastModifiedAt;

    /** 마지막 동기화에서 이 콘텐츠의 저장·갱신에 실패했는지 */
    private boolean syncFailed;

    /** 작성자의 외부 서비스 ID (Slack 사용자 ID 등) */
    private String authorExternalId;

    private String authorName;

    @Builder
    private ContentRef(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                       String originId, String channelId, Long parentRefId, String content,
                       String title, String deepLink, LocalDateTime createdAt, LocalDateTime lastModifiedAt,
                       String authorExternalId, String authorName) {
        this.workspaceId = workspaceId;
        this.ownerUserId = ownerUserId;
        this.provider = provider;
        this.contentType = contentType;
        this.originId = originId;
        this.channelId = channelId;
        this.parentRefId = parentRefId;
        this.content = content;
        this.title = title;
        this.deepLink = deepLink;
        this.createdAt = createdAt;
        this.lastModifiedAt = lastModifiedAt != null ? lastModifiedAt : createdAt;
        this.authorExternalId = authorExternalId;
        this.authorName = authorName;
    }

    public void updateContent(String title, String content) {
        this.title = title;
        this.content = content;
    }

    /** 동기화로 받은 최신 원본을 반영한다. null인 값은 바꾸지 않는다. */
    public void applySync(String title, String content, String deepLink, LocalDateTime lastModifiedAt,
                          String authorExternalId, String authorName) {
        this.title = title;
        this.content = content;
        if (deepLink != null) {
            this.deepLink = deepLink;
        }
        if (lastModifiedAt != null) {
            this.lastModifiedAt = lastModifiedAt;
        }
        if (authorExternalId != null) {
            this.authorExternalId = authorExternalId;
        }
        if (authorName != null) {
            this.authorName = authorName;
        }
        this.syncFailed = false;
    }

    public void markSyncFailed() {
        this.syncFailed = true;
    }

    /** 원본이 외부에서 삭제되면 마지막 본문을 사본으로 보관한다. */
    public void markOriginDeleted() {
        this.isOriginDeleted = true;
        this.snapshot = this.content;
    }
}
