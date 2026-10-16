package com.togather.content.slack.dto;

import java.util.List;

/**
 * 스레드 상세 (SlackContentService.getThreadDetail, 명세서 ThreadDetail)
 *
 * @param refreshFailed Slack에서 최신 답글을 가져오지 못해 저장된 내용만 보여주는 경우
 */
public record ThreadDetailResponse(SlackMessageResponse root, List<SlackMessageResponse> replies, String permalink,
                                   boolean refreshFailed) {
}
