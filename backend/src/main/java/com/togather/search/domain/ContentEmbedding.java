package com.togather.search.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Chroma에 임베딩해 넣은 콘텐츠 (C-07). 원본 수정 시각이 바뀌면 다시 임베딩한다. */
@Getter
@Entity
@Table(name = "content_embedding")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentEmbedding {

    @Id
    @Column(name = "content_ref_id")
    private Long contentRefId;

    @Column(nullable = false)
    private Long workspaceId;

    private LocalDateTime sourceModifiedAt;

    @Column(nullable = false)
    private LocalDateTime embeddedAt;

    public ContentEmbedding(Long contentRefId, Long workspaceId, LocalDateTime sourceModifiedAt,
                            LocalDateTime embeddedAt) {
        this.contentRefId = contentRefId;
        this.workspaceId = workspaceId;
        this.sourceModifiedAt = sourceModifiedAt;
        this.embeddedAt = embeddedAt;
    }
}
