package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.TaskStatus;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class TaskProgressReadTest extends IntegrationTestSupport {
    @Autowired private WebApplicationContext context;
    @Autowired private TaskService tasks;
    @Autowired private TaskResourceService resources;

    private MockMvc mvc;
    private MockHttpSession session;
    private Long userId;
    private Long memberId;
    private Long idleMemberId;
    private Long workspaceId;
    private LocalDate today;
    private LocalDateTime created;

    @BeforeEach
    void setUp() {
        userId = createUser("담당자 A", UUID.randomUUID() + "@test.example");
        memberId = createUser("담당자 B", UUID.randomUUID() + "@test.example");
        idleMemberId = createUser("배정 없음", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, memberId, "MEMBER");
        addMember(workspaceId, idleMemberId, "MEMBER");
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        session = sessionFor(userId);
        today = TaskService.currentTaskDate();
        created = today.atTime(10, 0);
    }

    @Test
    void 첫_20건과_100건_밖의_Task까지_집계하고_상태_필터는_페이지보다_먼저_적용한다() throws Exception {
        for (int index = 0; index < 121; index++) {
            task(index < 101 ? "진행 작업 " + index : "Old Finish " + index,
                index % 2 == 0 ? userId : memberId, index < 101 ? TaskStatus.IN_PROGRESS : TaskStatus.DONE,
                null, 0, 0, index);
        }
        mvc.perform(get(base()).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(121))
            .andExpect(jsonPath("$.data.items.length()").value(20))
            .andExpect(jsonPath("$.data.items[0].status").value("IN_PROGRESS"));
        mvc.perform(get(progress()).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.asOfDate").value(today.toString()))
            .andExpect(jsonPath("$.data.overall.totalCount").value(121))
            .andExpect(jsonPath("$.data.overall.inProgressCount").value(101))
            .andExpect(jsonPath("$.data.overall.doneCount").value(20))
            .andExpect(jsonPath("$.data.overall.progress").value(16));
        mvc.perform(get(base()).session(session).param("status", "DONE").param("size", "5").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(20))
            .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.items.length()").value(5))
            .andExpect(jsonPath("$.data.items[0].status").value("DONE"));
        mvc.perform(get(base()).session(session).param("query", "  OLD finish  "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(20));
        mvc.perform(get(progress()).session(session).param("status", "DONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.overall.totalCount").value(20))
            .andExpect(jsonPath("$.data.overall.progress").value(100));
    }

    @Test
    void 전체_평균은_산출물_개수가_아닌_Task별_진행률로_계산하고_담당자별로_분리한다() throws Exception {
        task("3개 중 1개", userId, TaskStatus.IN_PROGRESS, null, 3, 1, 0);
        task("수동 완료", memberId, TaskStatus.DONE, null, 0, 0, 1);
        task("미지정 작업", null, TaskStatus.IN_PROGRESS, null, 1, 0, 2);
        String body = mvc.perform(get(progress()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.overall.progress").value(44))
            .andExpect(jsonPath("$.data.overall.totalCount").value(3))
            .andReturn().getResponse().getContentAsString();
        assertThat(number(body, "$.data.byAssignee[?(@.assigneeId == " + userId + ")].summary.progress")).isEqualTo(33);
        assertThat(number(body, "$.data.byAssignee[?(@.assigneeId == " + memberId + ")].summary.progress")).isEqualTo(100);
        assertThat(number(body, "$.data.byAssignee[?(@.assigneeId == " + idleMemberId + ")].summary.totalCount")).isZero();
        assertThat(number(body, "$.data.byAssignee[?(@.assigneeId == null)].summary.totalCount")).isEqualTo(1);
    }

    @Test
    void 마감_위험도는_상세_도메인과_동일하고_정상_주의_위험_합계가_전체와_같다() throws Exception {
        List<Long> ids = List.of(
            task("지남", userId, TaskStatus.IN_PROGRESS, today.minusDays(1), 4, 3, 0),
            task("오늘 33%", userId, TaskStatus.IN_PROGRESS, today, 3, 1, 1),
            task("2일 50%", userId, TaskStatus.IN_PROGRESS, today.plusDays(2), 2, 1, 2),
            task("5일 75%", memberId, TaskStatus.IN_PROGRESS, today.plusDays(5), 4, 3, 3),
            task("5일 80%", memberId, TaskStatus.IN_PROGRESS, today.plusDays(5), 5, 4, 4),
            task("6일", memberId, TaskStatus.IN_PROGRESS, today.plusDays(6), 0, 0, 5),
            task("미정", null, TaskStatus.IN_PROGRESS, null, 0, 0, 6),
            task("완료", null, TaskStatus.DONE, today.minusDays(5), 0, 0, 7));
        mvc.perform(get(progress()).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.overall.normalCount").value(4))
            .andExpect(jsonPath("$.data.overall.warningCount").value(2))
            .andExpect(jsonPath("$.data.overall.dangerCount").value(2));
        String body = mvc.perform(get(base()).session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        for (Long id : ids) {
            var task = tasks.getTask(id);
            List<String> risks = JsonPath.read(body, "$.data.items[?(@.taskId == " + id + ")].riskLevel");
            assertThat(risks).containsExactly(task.riskLevel().name());
            assertThat(number(body, "$.data.items[?(@.taskId == " + id + ")].progress")).isEqualTo(task.progress());
        }
    }

    @Test
    void 담당자_내담당_미지정_상태_검색은_목록과_집계에_같이_적용한다() throws Exception {
        task("Alpha 완료", userId, TaskStatus.DONE, null, 0, 0, 0);
        task("Alpha 진행", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 1);
        task("Alpha 팀원", memberId, TaskStatus.IN_PROGRESS, null, 0, 0, 2);
        task("Alpha 미지정", null, TaskStatus.IN_PROGRESS, null, 0, 0, 3);
        task("Beta 팀원", memberId, TaskStatus.DONE, null, 0, 0, 4);
        for (String endpoint : List.of(base(), progress())) {
            String countPath = endpoint.endsWith("progress") ? "$.data.overall.totalCount" : "$.data.total";
            mvc.perform(get(endpoint).session(session).param("assigneeId", memberId.toString()).param("status", "IN_PROGRESS").param("query", "Alpha"))
                .andExpect(status().isOk()).andExpect(jsonPath(countPath).value(1));
            mvc.perform(get(endpoint).session(session).param("mine", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath(countPath).value(2));
            mvc.perform(get(endpoint).session(session).param("unassigned", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath(countPath).value(1));
        }
        mvc.perform(get(base()).session(sessionFor(memberId)).param("mine", "true").param("status", "DONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].title").value("Beta 팀원"));
    }

    @Test
    void 검색의_퍼센트_밑줄_따옴표는_일반_문자이고_SQL_조건이_되지_않는다() throws Exception {
        task("Update 100%_PDF", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 0);
        task("Update 100XXPDF", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 1);
        task("O'Reilly", userId, TaskStatus.DONE, null, 0, 0, 2);
        mvc.perform(get(base()).session(session).param("query", "100%_pdf"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get(progress()).session(session).param("query", "O'Reilly"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.overall.totalCount").value(1));
        mvc.perform(get(base()).session(session).param("query", "' OR 1=1 --"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 마감_조건은_전체에_먼저_적용하고_이전_마감부터_정렬하며_경계일은_제외한다() throws Exception {
        for (int index = 0; index < 105; index++) task("새 작업 " + index, userId, TaskStatus.IN_PROGRESS, null, 0, 0, index);
        Long oldestDue = task("오래된 마감 작업", memberId, TaskStatus.IN_PROGRESS, today.minusDays(1), 0, 0, 110);
        task("경계 전날", userId, TaskStatus.IN_PROGRESS, today.plusDays(6), 0, 0, 111);
        task("경계일", userId, TaskStatus.IN_PROGRESS, today.plusDays(7), 0, 0, 112);
        task("마감 완료", userId, TaskStatus.DONE, today.minusDays(2), 0, 0, 113);
        mvc.perform(get(base()).session(session).param("status", "IN_PROGRESS").param("dueBefore", today.plusDays(7).toString())
                .param("sort", "DUE_DATE").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].taskId").value(oldestDue));
        mvc.perform(get(progress()).session(session).param("status", "IN_PROGRESS").param("dueBefore", today.plusDays(7).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.overall.totalCount").value(2));
        mvc.perform(get(base()).session(session).param("sort", "DUE_DATE").param("size", "5"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].title").value("마감 완료"));
    }

    @Test
    void 같은_생성시각도_ID로_정렬해_페이지가_중복되거나_빠지지_않는다() throws Exception {
        Long first = task("첫 작업", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 0);
        Long second = task("다음 작업", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 0);
        mvc.perform(get(base()).session(session).param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].taskId").value(second));
        mvc.perform(get(base()).session(session).param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].taskId").value(first));
    }

    @Test
    void 빈_워크스페이스와_작업_없는_팀원도_0으로_집계한다() throws Exception {
        mvc.perform(get(progress()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.overall.totalCount").value(0))
            .andExpect(jsonPath("$.data.overall.progress").value(0))
            .andExpect(jsonPath("$.data.byAssignee.length()").value(4));
        mvc.perform(get(base()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 이전_팀원의_작업은_집계에_남고_다른_워크스페이스_작업은_섞이지_않는다() throws Exception {
        Long previous = createUser("이전 팀원", UUID.randomUUID() + "@test.example");
        task("이전 배정", previous, TaskStatus.DONE, null, 0, 0, 0);
        Long otherWorkspace = createWorkspace(userId);
        jdbc.update("INSERT INTO task (workspace_id, title, status, created_at, updated_at) VALUES (?, '다른 팀', 'DONE', now(), now())", otherWorkspace);
        String body = mvc.perform(get(progress()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.overall.totalCount").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(number(body, "$.data.byAssignee[?(@.assigneeId == " + previous + ")].summary.totalCount")).isEqualTo(1);
        mvc.perform(get(base()).session(session).param("assigneeId", previous.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get(base()).session(session).param("assigneeId", idleMemberId.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 상태와_산출물_변경은_다음_집계에_바로_반영된다() throws Exception {
        Long taskId = task("진행 상태 변경", userId, TaskStatus.IN_PROGRESS, null, 0, 0, 0);
        tasks.updateTask(workspaceId, taskId, null, null, null, null, TaskStatus.DONE);
        mvc.perform(get(progress()).session(session)).andExpect(jsonPath("$.data.overall.progress").value(100));
        var deliverable = resources.addDeliverable(workspaceId, userId, taskId,
            new com.togather.task.dto.DeliverableInput("결과", "pdf"));
        mvc.perform(get(progress()).session(session)).andExpect(jsonPath("$.data.overall.progress").value(0))
            .andExpect(jsonPath("$.data.overall.inProgressCount").value(1));
        jdbc.update("UPDATE task_deliverable SET is_submitted = true WHERE deliverable_id = ?", deliverable.deliverableId());
        tasks.updateTask(workspaceId, taskId, null, null, null, null, null);
        mvc.perform(get(progress()).session(session)).andExpect(jsonPath("$.data.overall.progress").value(100));
        resources.unsubmit(workspaceId, userId, taskId, deliverable.deliverableId());
        mvc.perform(get(progress()).session(session)).andExpect(jsonPath("$.data.overall.progress").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=UNKNOWN", "assigneeId=0", "assigneeId=-1", "dueBefore=bad-date", "mine=invalid", "mine=true&unassigned=true"})
    void 잘못된_공통_조건은_목록과_집계에서_400으로_거절한다(String query) throws Exception {
        for (String endpoint : List.of(base(), progress())) {
            mvc.perform(withQuery(endpoint, query).session(session)).andExpect(status().isBadRequest());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647&size=100", "sort=UNKNOWN"})
    void 잘못된_페이지와_정렬은_400이다(String query) throws Exception {
        mvc.perform(withQuery(base(), query).session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void 상충하는_담당자_조건과_긴_검색어를_거절한다() throws Exception {
        for (String endpoint : List.of(base(), progress())) {
            mvc.perform(get(endpoint).session(session).param("mine", "true").param("assigneeId", memberId.toString()))
                .andExpect(status().isBadRequest());
            mvc.perform(get(endpoint).session(session).param("unassigned", "true").param("assigneeId", memberId.toString()))
                .andExpect(status().isBadRequest());
            mvc.perform(get(endpoint).session(session).param("query", "a".repeat(201)))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 목록과_진행_현황은_로그인과_워크스페이스_멤버십을_검사한다() throws Exception {
        Long outsider = createUser("외부인", UUID.randomUUID() + "@test.example");
        for (String endpoint : List.of(base(), progress())) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(endpoint).session(sessionFor(outsider))).andExpect(status().isForbidden());
        }
    }

    private Long task(String title, Long assigneeId, TaskStatus status, LocalDate due, int required, int submitted, int ageSeconds) {
        Long id = jdbc.queryForObject("""
            INSERT INTO task (workspace_id, title, assignee_id, due_date, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING task_id
            """, Long.class, workspaceId, title, assigneeId, due, status.name(), created.minusSeconds(ageSeconds), created);
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < required; index++) rows.add(new Object[] {id, index < submitted});
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO task_deliverable (task_id, name, is_submitted, created_at, updated_at) VALUES (?, '자료', ?, now(), now())", rows);
        return id;
    }

    private static int number(String body, String path) {
        List<Number> values = JsonPath.read(body, path);
        assertThat(values).hasSize(1);
        return values.getFirst().intValue();
    }

    private static MockHttpServletRequestBuilder withQuery(String path, String query) {
        var request = get(path);
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            request.param(parts[0], parts[1]);
        }
        return request;
    }

    private MockHttpSession sessionFor(Long id) {
        var result = new MockHttpSession(context.getServletContext());
        result.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, id);
        return result;
    }

    private String base() { return "/api/workspaces/" + workspaceId + "/tasks"; }
    private String progress() { return base() + "/progress"; }
}
