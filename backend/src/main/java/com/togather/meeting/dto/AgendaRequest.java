package com.togather.meeting.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** 기존 팀 작업을 연결하거나, 회의에서 논의할 안건 제목을 직접 입력한다. */
public record AgendaRequest(Long taskId, @Size(max = 200) String title) {

    @AssertTrue(message = "팀 작업 또는 직접 입력 안건 중 하나를 지정해 주세요.")
    public boolean isValidContent() {
        boolean hasTitle = title != null && !title.isBlank();
        return (taskId != null) != hasTitle;
    }
}
