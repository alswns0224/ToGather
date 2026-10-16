package com.togather.common.ai;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * resources/prompts/{name}.txt의 프롬프트를 읽어 {{변수}}를 채운다. (README 12번)
 */
@Component
public class PromptTemplates {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String render(String name, Map<String, String> variables) {
        String prompt = cache.computeIfAbsent(name, PromptTemplates::load);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            prompt = prompt.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return prompt;
    }

    /** 오늘 날짜와 요일. 예: 2026-10-16 (금요일). 요일이 없으면 Gemini가 "다음 주 화요일"을 하루씩 틀리게 계산한다. */
    public static String today(LocalDate today) {
        return today + " (" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN) + ")";
    }

    /** 오늘부터 days일 동안의 날짜·요일 표. 상대적인 날짜 표현을 정확히 계산하도록 프롬프트에 넣는다. */
    public static String calendar(LocalDate from, int days) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            if (i > 0) {
                sb.append(i % 7 == 0 ? "\n" : ", ");
            }
            sb.append(date).append('(').append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                    .append(')');
        }
        return sb.toString();
    }

    private static String load(String name) {
        try (InputStream in = new ClassPathResource("prompts/" + name + ".txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
