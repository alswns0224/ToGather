package com.togather.search.dto;

import com.togather.common.enums.Provider;
import com.togather.task.dto.TaskSummary;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 통합 검색 결과 (UC-12-01, SD-12-01)
 *
 * @param pending 아직 검색에 반영되지 않은 콘텐츠 수 (색인 중이면 0보다 크다)
 */
public record SearchResponse(String query, List<Item> items, long pending) {

    /**
     * @param score   관련도 (코사인 유사도, 0~1)
     * @param appPath ToGather 안에서 여는 경로 (/workspaces/{id} 뒤에 붙인다)
     * @param tasks   이 콘텐츠가 관련 자료·산출물로 연결된 Task
     */
    public record Item(Long contentRefId, Provider provider, String title, String snippet, String deepLink,
                       LocalDateTime createdAt, String authorName, double score, String appPath,
                       List<TaskSummary> tasks) {
    }
}
