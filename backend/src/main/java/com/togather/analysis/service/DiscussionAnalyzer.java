package com.togather.analysis.service;

import com.togather.common.ai.GeminiClient;
import com.togather.common.ai.PromptTemplates;
import com.togather.common.enums.ItemType;
import com.togather.content.slack.service.SlackContentService.ThreadMessage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Gemini로 Slack 스레드를 분석한다. (SD-08-01) 프롬프트를 만들고, JSON 응답을 항목 후보로 바꾼다.
 * 메시지는 m1, m2 ... 번호로 보내고 응답의 근거 번호를 다시 content_ref ID로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class DiscussionAnalyzer {

    /** 프롬프트가 너무 길어지지 않도록 최근 메시지만, 메시지당 길이도 자른다. */
    static final int MAX_MESSAGES = 150;
    static final int MAX_MESSAGE_LENGTH = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd(E) HH:mm", java.util.Locale.KOREAN);

    private final GeminiClient geminiClient;
    private final PromptTemplates promptTemplates;

    /** Gemini 응답 형식 (resources/prompts/discussion-analysis.txt) */
    public record AiResult(List<AiItem> items, Boolean settled) {
    }

    public record AiItem(String type, String content, List<String> evidence, String assignee, String dueDate,
                         String datetime, List<String> participants) {
    }

    /** 변환한 항목 후보. 담당자·참여자는 아직 Slack 사용자 ID */
    public record Candidate(ItemType itemType, String content, List<Long> evidenceContentRefIds,
                            String assigneeSlackId, LocalDate dueDate, LocalDateTime datetime,
                            List<String> participantSlackIds) {
    }

    public record Result(List<Candidate> candidates, boolean settled) {
    }

    /**
     * @param messages      스레드 전체 메시지 (시간순)
     * @param newAfter      이 시각 이후 메시지가 이번 분석 대상. null이면 전체
     * @param previousItems 이전 분석에서 이미 정리한 항목 (중복 방지)
     */
    public Result analyze(String channelName, List<ThreadMessage> messages, LocalDateTime newAfter,
                          List<String> previousItems, LocalDate today) {
        List<ThreadMessage> recent = messages.size() > MAX_MESSAGES
                ? messages.subList(messages.size() - MAX_MESSAGES, messages.size())
                : messages;
        Map<String, Long> refByLabel = new HashMap<>();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            ThreadMessage m = recent.get(i);
            String label = "m" + (i + 1);
            refByLabel.put(label, m.contentRefId());
            boolean isNew = newAfter == null || isAfter(m, newAfter);
            lines.append(label).append(isNew ? " (새 메시지) " : " (이전 메시지) ")
                    .append('[').append(Objects.requireNonNullElse(m.authorExternalId(), "unknown")).append(' ')
                    .append(Objects.requireNonNullElse(m.authorName(), "")).append("] ")
                    .append(m.createdAt() == null ? "" : TIME.format(m.createdAt())).append(": ")
                    .append(truncate(m.text())).append('\n');
        }
        String prompt = promptTemplates.render("discussion-analysis", Map.of(
                "today", PromptTemplates.today(today),
                "calendar", PromptTemplates.calendar(today, 21),
                "channel", channelName == null ? "" : channelName,
                "previousItems", previousItems.isEmpty() ? "(없음)"
                        : previousItems.stream().map(s -> "- " + s).collect(Collectors.joining("\n")),
                "messages", lines.toString()));

        AiResult result = geminiClient.generateJson(prompt, AiResult.class);
        List<Candidate> candidates = new ArrayList<>();
        for (AiItem item : result.items() == null ? List.<AiItem>of() : result.items()) {
            ItemType type = parseType(item.type());
            if (type == null || item.content() == null || item.content().isBlank()) {
                continue; // 형식이 맞지 않는 항목은 버린다.
            }
            List<Long> evidence = (item.evidence() == null ? List.<String>of() : item.evidence()).stream()
                    .map(refByLabel::get).filter(Objects::nonNull).distinct().toList();
            candidates.add(new Candidate(type, item.content().strip(), evidence, blankToNull(item.assignee()),
                    parseDate(item.dueDate()), parseDateTime(item.datetime()),
                    item.participants() == null ? List.of() : item.participants().stream()
                            .filter(Objects::nonNull).distinct().toList()));
        }
        return new Result(candidates, Boolean.TRUE.equals(result.settled()));
    }

    private static boolean isAfter(ThreadMessage m, LocalDateTime after) {
        return (m.createdAt() != null && m.createdAt().isAfter(after))
                || (m.lastModifiedAt() != null && m.lastModifiedAt().isAfter(after));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= MAX_MESSAGE_LENGTH ? oneLine : oneLine.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    static ItemType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return ItemType.valueOf(type.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, Math.min(10, value.strip().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        String v = value.strip();
        try {
            return LocalDateTime.parse(v.length() == 16 ? v + ":00" : v.substring(0, Math.min(19, v.length())));
        } catch (DateTimeParseException e) {
            return null; // 날짜만 있거나 형식이 다르면 미정으로 두고 검토 때 정한다.
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? null : value.strip();
    }
}
