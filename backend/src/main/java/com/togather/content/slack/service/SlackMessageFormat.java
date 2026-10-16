package com.togather.content.slack.service;

import com.togather.content.slack.client.SlackApiClient.SlackFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Slack 메시지를 content_ref.content 한 칸에 저장하는 형식.
 * 본문 뒤에 첨부 파일을 "[첨부] 이름 | 링크" 줄로 붙인다. (검색·AI 분석에서도 파일 이름이 보이도록)
 */
public final class SlackMessageFormat {

    static final String ATTACHMENT_PREFIX = "[첨부] ";
    private static final String SEPARATOR = " | ";

    private SlackMessageFormat() {
    }

    public record Parsed(String text, List<Attachment> attachments) {
    }

    public record Attachment(String name, String url) {
    }

    public static String toContent(String text, List<SlackFile> files) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        for (SlackFile file : files == null ? List.<SlackFile>of() : files) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(ATTACHMENT_PREFIX).append(file.name() == null ? "파일" : file.name());
            if (file.url() != null) {
                sb.append(SEPARATOR).append(file.url());
            }
        }
        return sb.toString();
    }

    public static Parsed parse(String content) {
        if (content == null) {
            return new Parsed("", List.of());
        }
        List<String> textLines = new ArrayList<>();
        List<Attachment> attachments = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith(ATTACHMENT_PREFIX)) {
                String rest = line.substring(ATTACHMENT_PREFIX.length());
                int sep = rest.lastIndexOf(SEPARATOR);
                attachments.add(sep < 0 ? new Attachment(rest, null)
                        : new Attachment(rest.substring(0, sep), rest.substring(sep + SEPARATOR.length())));
            } else {
                textLines.add(line);
            }
        }
        return new Parsed(String.join("\n", textLines).strip(), attachments);
    }

    /** content_ref.origin_id: 채널 ID와 메시지 ts (ts는 채널 안에서만 유일하다) */
    public static String originId(String channelId, String ts) {
        return channelId + ":" + ts;
    }

    public static String tsOf(String originId) {
        int idx = originId.indexOf(':');
        return idx < 0 ? originId : originId.substring(idx + 1);
    }
}
