package com.togather.content.dto;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import java.time.LocalDateTime;

/** CD-DTO ContentRefDTO (docs/shared-interfaces.md, B 담당) */
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
        return new ContentRefDTO(ref.getContentRefId(), ref.getProvider(), ref.getContentType(), ref.getOriginId(),
                ref.getTitle(), ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent(), ref.getDeepLink(),
                null, ref.getCreatedAt(), 0, false);
    }
}
