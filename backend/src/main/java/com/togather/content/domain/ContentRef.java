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

    @Builder
    private ContentRef(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                       String originId, String channelId, Long parentRefId, String content,
                       String title, String deepLink, LocalDateTime createdAt) {
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
    }

    public void updateContent(String title, String content) {
        this.title = title;
        this.content = content;
    }

    /** 원본이 외부에서 삭제되면 마지막 본문을 사본으로 보관한다. */
    public void markOriginDeleted() {
        this.isOriginDeleted = true;
        this.snapshot = this.content;
    }
}
