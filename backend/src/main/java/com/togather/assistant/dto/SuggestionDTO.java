package com.togather.assistant.dto;

import com.togather.common.enums.Provider;
import com.togather.common.enums.SuggestionStatus;
import com.togather.common.enums.SuggestionType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * UI-13 후보 한 건: 감지된 내용과 원본 메시지의 출처·작성자·작성 시각
 *
 * @param timeUndetermined 일정 후보인데 일시가 명확하지 않은 경우 (시간 미정 표시)
 */
public record SuggestionDTO(Long suggestionId, SuggestionType type, SuggestionStatus status, String title,
                            LocalDateTime detectedDatetime, boolean timeUndetermined, LocalDate dueDate,
                            Long sourceContentRefId, Provider sourceProvider, String sourceTitle,
                            String sourceAuthor, LocalDateTime sourceCreatedAt, String sourceExcerpt,
                            String sourceLink) {
}
