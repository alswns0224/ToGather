package com.togather.content.slack.dto;

import com.togather.content.slack.service.SlackMessageFormat.Attachment;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UI-04 메시지 한 건. 작성자, 작성 시각, 본문, 첨부 파일을 표시한다.
 *
 * @param text     멘션(&lt;@U123&gt;)을 @이름으로 바꾼 본문
 * @param edited   수정된 적이 있는지
 * @param mine     로그인 사용자가 쓴 메시지인지 (수정·삭제 버튼 표시)
 */
public record SlackMessageResponse(Long contentRefId, String channelId, String ts, String authorId,
                                   String authorName, String text, List<Attachment> attachments,
                                   LocalDateTime createdAt, boolean edited, int replyCount, String deepLink,
                                   boolean isOriginDeleted, boolean syncFailed, boolean mine) {
}
