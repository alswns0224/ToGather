package com.togather.task.dto;

import java.time.LocalDate;
import java.util.List;

/** 조회 조건에 맞는 모든 Task의 집계. progress는 개별 Task 진행률의 산술평균을 내림한 값이다. */
public record TaskProgressResponse(LocalDate asOfDate, Summary overall, List<AssigneeProgress> byAssignee) {

    public record Summary(long totalCount, long inProgressCount, long doneCount, int progress,
                          long normalCount, long warningCount, long dangerCount) {
    }

    /** assigneeId=null은 담당자 미지정. */
    public record AssigneeProgress(Long assigneeId, Summary summary) {
    }
}
