package com.togather.content.dto;

import com.togather.common.enums.Provider;
import java.time.LocalDateTime;
import java.util.List;

/** CD-DTO ContentDetailDTO (docs/shared-interfaces.md, B 담당) */
public record ContentDetailDTO(
        Long contentRefId,
        Provider provider,
        String title,
        String body,
        String deepLink,
        String previewLink,
        LocalDateTime startAt,
        List<String> participants,
        String description,
        boolean isOriginDeleted
) {
}
