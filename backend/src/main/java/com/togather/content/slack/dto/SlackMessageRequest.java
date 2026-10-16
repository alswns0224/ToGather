package com.togather.content.slack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 메시지 작성·수정 (UI-04). Slack 메시지 최대 길이는 40,000자지만 화면 입력은 4,000자로 제한한다. */
public record SlackMessageRequest(@NotBlank @Size(max = 4000) String text) {
}
