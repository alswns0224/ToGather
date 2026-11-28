package com.togather.content.slack.dto;

import java.time.LocalDateTime;

/** 내 Slack 1:1 DM 화면 (UI-04). DM은 개인 자료라 저장하지 않고 내 토큰으로 바로 읽는다. */
public final class SlackDmResponse {

    private SlackDmResponse() {
    }

    /**
     * DM 대화 한 개: 상대 Slack 사용자
     *
     * @param unreadCount Slack에서 아직 안 읽은 메시지 수 (Slack 표시 기준, 웹훅으로 새 메시지가 오면 늘어난다)
     * @param lastRead    Slack에서 마지막으로 읽은 메시지 ts. 화면이 "ToGather에서 이미 본 수"를 뺄 기준으로 쓴다.
     */
    public record Conversation(String channelId, String userId, String name, int unreadCount, String lastRead) {
    }

    /** DM 메시지 한 개. mine이면 내가 보낸 메시지 */
    public record Message(String ts, String userId, String userName, String text, LocalDateTime createdAt,
                          boolean mine, String rawText, boolean edited) {
        public Message(String ts, String userId, String userName, String text, LocalDateTime createdAt, boolean mine) {
            this(ts, userId, userName, text, createdAt, mine, text, false);
        }
    }
}
