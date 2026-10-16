package com.togather.content.dto;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import java.time.LocalDateTime;

/**
 * CD-DTO ContentRefDTO (docs/shared-interfaces.md, B 담당)
 *
 * @param messageCount Slack 메시지면 스레드 답글 수, 그 외 0
 * @param syncFailed   마지막 동기화에서 이 콘텐츠의 갱신에 실패했는지
 */
public record ContentRefDTO(
        Long contentRefId,
        Provider provider,
        ContentType contentType,
        String externalId,
        String title,
        String content,
        String deepLink,
        String fileFormat,
        LocalDateTime lastModifiedAt,
        int messageCount,
        boolean syncFailed
) {

    public static ContentRefDTO from(ContentRef ref) {
        return from(ref, 0);
    }

    public static ContentRefDTO from(ContentRef ref, int messageCount) {
        return new ContentRefDTO(ref.getContentRefId(), ref.getProvider(), ref.getContentType(), ref.getOriginId(),
                ref.getTitle(), ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent(), ref.getDeepLink(),
                null, ref.getLastModifiedAt(), messageCount, ref.isSyncFailed());
    }
}
