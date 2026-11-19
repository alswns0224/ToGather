package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;

/** 목록과 진행 현황이 공유하는 조회 조건. 담당자와 미지정 조건은 동시에 지정하지 않는다. */
public record TaskFilter(Long assigneeId, boolean unassigned, TaskStatus status, String query,
                         LocalDate dueBefore, TaskSort sort) {
}
