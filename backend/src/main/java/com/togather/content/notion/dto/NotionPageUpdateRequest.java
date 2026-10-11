package com.togather.content.notion.dto;

import jakarta.validation.constraints.Size;

/** null인 값은 바꾸지 않는다. */
public record NotionPageUpdateRequest(@Size(min = 1, max = 200) String title, @Size(max = 20000) String body) {
}
