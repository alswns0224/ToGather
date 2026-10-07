package com.togather.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 값 타입 (C-00-37). 컬럼명이 다르면 사용하는 엔티티에서 @AttributeOverride로 지정한다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DateRange {

    @Column(name = "start_at")
    private LocalDateTime start;

    @Column(name = "end_at")
    private LocalDateTime end;

    public DateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("start는 end보다 이전이어야 합니다.");
        }
        this.start = start;
        this.end = end;
    }

    public boolean overlaps(DateRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
