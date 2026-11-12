package com.togather.search.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.enums.Provider;
import com.togather.common.response.ApiResponse;
import com.togather.search.dto.SearchResponse;
import com.togather.search.service.SearchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** UI-12 통합 검색 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /** 예: GET /api/workspaces/1/search?q=발표 담당&providers=SLACK,NOTION&limit=20 */
    @GetMapping("/api/workspaces/{workspaceId}/search")
    public ApiResponse<SearchResponse> search(@LoginUser Long userId, @PathVariable Long workspaceId,
                                              @RequestParam String q,
                                              @RequestParam(required = false) List<Provider> providers,
                                              @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(searchService.search(workspaceId, userId, q, providers, limit));
    }
}
