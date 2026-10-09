package com.togather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.togather.support.EmbeddedPostgresConfig;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A 파트 전체 흐름을 실제 PostgreSQL(내장)로 확인한다.
 * Flyway 마이그레이션 → JPA 매핑 검증(ddl-auto=validate) → API 응답 형식·권한까지.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://unused",
        "spring.datasource.username=unused",
        "spring.datasource.password=unused",
        "togather.app-url=http://localhost:5173",
        "togather.token-key=integration-test-key",
        "togather.auth.dev-login=true",
        "togather.google.client-id=",
        "togather.google.client-secret=",
        "togather.slack.client-id=",
        "togather.slack.client-secret=",
        "togather.notion.client-id=",
        "togather.notion.client-secret="
})
@Import(EmbeddedPostgresConfig.class)
class PartAFlowIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 로그인하지_않으면_401과_공통_에러_형식() throws Exception {
        mvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 로그인_설정을_알려준다() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleEnabled").value(false))
                .andExpect(jsonPath("$.data.devLoginEnabled").value(true));
    }

    @Test
    void 워크스페이스_초대_팀원_연동_회의_흐름() throws Exception {
        MockHttpSession owner = login("owner@test.com", "팀장");
        MockHttpSession member = login("member@test.com", "팀원");
        MockHttpSession outsider = login("outsider@test.com", "외부인");

        // 내 정보
        call(get("/api/auth/me"), owner).andExpect(jsonPath("$.data.name").value("팀장"));

        // 워크스페이스 생성 (UC-02-02)
        MvcResult created = call(json(post("/api/workspaces"), "{\"name\":\"캡스톤\",\"description\":\"ToGather 팀\"}"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andReturn();
        int workspaceId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.workspaceId");
        String ws = "/api/workspaces/" + workspaceId;

        // 입력 검증 (이름 누락)
        call(json(post("/api/workspaces"), "{\"name\":\"\"}"), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        // 멤버가 아니면 403
        call(get(ws), outsider).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"));

        // 초대 링크 (UC-02-05, 06)
        MvcResult link = call(post(ws + "/invite-links"), owner).andExpect(status().isCreated()).andReturn();
        String token = JsonPath.read(link.getResponse().getContentAsString(), "$.data.token");
        call(post(ws + "/invite-links"), member).andExpect(status().isForbidden());
        call(get("/api/invites/" + token), member)
                .andExpect(jsonPath("$.data.name").value("캡스톤"))
                .andExpect(jsonPath("$.data.alreadyMember").value(false));
        call(post("/api/invites/" + token + "/join"), member)
                .andExpect(jsonPath("$.data.workspaceId").value(workspaceId));
        call(get("/api/invites/invalid-token"), member)
                .andExpect(jsonPath("$.error.code").value("INVITE_LINK_INVALID"));

        // 목록·팀원 (UC-02-01, 07)
        call(get("/api/workspaces"), member)
                .andExpect(jsonPath("$.data[0].memberCount").value(2))
                .andExpect(jsonPath("$.data[0].myRole").value("MEMBER"));
        MvcResult members = call(get(ws + "/members"), member)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("OWNER"))
                .andReturn();
        int ownerId = JsonPath.read(members.getResponse().getContentAsString(), "$.data[0].userId");
        int memberId = JsonPath.read(members.getResponse().getContentAsString(), "$.data[1].userId");

        // 팀원은 수정·삭제·내보내기 불가, 팀장은 내보낼 수 없음
        call(json(patch(ws), "{\"name\":\"변경\"}"), member).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_OWNER_REQUIRED"));
        call(delete(ws + "/members/" + ownerId), owner).andExpect(jsonPath("$.error.code").value("CANNOT_REMOVE_OWNER"));
        call(json(patch(ws), "{\"name\":\"캡스톤 디자인\"}"), owner).andExpect(jsonPath("$.data.name").value("캡스톤 디자인"));

        // 연동 상태 (UC-03-02): 5개 서비스, 서버 설정이 없으면 configured=false
        call(get(ws + "/integrations"), member)
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].provider").value("SLACK"))
                .andExpect(jsonPath("$.data[0].configured").value(false))
                .andExpect(jsonPath("$.data[0].connections.length()").value(0));
        // 설정이 없으면 인증 화면 대신 연동 관리 화면으로 오류와 함께 돌아간다
        call(get(ws + "/integrations/SLACK/connect"), member)
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        "http://localhost:5173/workspaces/" + workspaceId + "/integrations?error=OAUTH_NOT_CONFIGURED"));
        // Calendar 미연동이면 일정 조회는 CONNECTION_NOT_FOUND
        call(get(ws + "/calendar/events?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00"), member)
                .andExpect(jsonPath("$.error.code").value("CONNECTION_NOT_FOUND"));

        // 회의 (UC-10): 다음 주 월요일 09~18시 후보
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDateTime from = monday.atTime(9, 0);
        String meetingBody = """
                {"title":"주간 회의","durationMinutes":60,"candidateStartAt":"%s","candidateEndAt":"%s",
                 "participantIds":[%d],"meetingMode":"온라인","agendaTaskIds":[]}
                """.formatted(from, monday.atTime(18, 0), memberId);
        MvcResult meeting = call(json(post(ws + "/meetings"), meetingBody), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SCHEDULING"))
                .andExpect(jsonPath("$.data.participants.length()").value(2))
                .andExpect(jsonPath("$.data.canManage").value(true))
                .andReturn();
        int meetingId = JsonPath.read(meeting.getResponse().getContentAsString(), "$.data.meetingId");
        String mt = ws + "/meetings/" + meetingId;

        // 가용 시간: 팀장 10:00~12:00, 팀원 11:00~12:00(11:30은 비선호)
        call(get(mt + "/availability/me"), owner)
                .andExpect(jsonPath("$.data.slotMinutes").value(30))
                .andExpect(jsonPath("$.data.calendarConnected").value(false));
        call(json(post(mt + "/availability/me"), slots(monday, "10:00", "10:30", "11:00", "11:30")), owner)
                .andExpect(jsonPath("$.data.hasResponded").value(true))
                .andExpect(jsonPath("$.data.mySlots.length()").value(4));
        call(json(post(mt + "/availability/me"), """
                {"slots":[{"startAt":"%sT11:00:00","preference":"AVAILABLE"},
                          {"startAt":"%sT11:30:00","preference":"NOT_PREFERRED"}]}
                """.formatted(monday, monday)), member).andExpect(status().isOk());
        call(get(mt + "/availability/me"), outsider).andExpect(status().isForbidden());

        call(get(mt + "/responses"), owner).andExpect(jsonPath("$.data.responded.length()").value(2));

        // 추천: 둘 다 가능한 11:00이 1순위 (비선호 1명)
        call(post(mt + "/recommendations"), member)
                .andExpect(jsonPath("$.data[0].startAt").value(monday + "T11:00:00"))
                .andExpect(jsonPath("$.data[0].availableCount").value(2))
                .andExpect(jsonPath("$.data[0].nonPreferredCount").value(1));

        // 팀원은 확정 불가, 만든 사람은 확정. Calendar 미연동이라 등록 실패로 표시되지만 확정은 유지
        call(json(post(mt + "/confirm"), "{\"startAt\":\"" + monday + "T11:00:00\"}"), member)
                .andExpect(status().isForbidden());
        call(json(post(mt + "/confirm"), "{\"startAt\":\"" + monday + "T11:00:00\"}"), owner)
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.calendarSyncFailed").value(true))
                .andExpect(jsonPath("$.data.endAt").value(monday + "T12:00:00"));

        // 확정 후에는 가용 시간 수정 불가
        call(json(post(mt + "/availability/me"), slots(monday, "10:00")), owner)
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_SCHEDULING"));

        call(get(ws + "/meetings"), member).andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
        call(post(mt + "/cancel"), owner).andExpect(status().isNoContent());
        call(get(mt), member).andExpect(jsonPath("$.data.status").value("CANCELED"));

        // 팀원 내보내기 후에는 접근 불가, 워크스페이스 삭제
        call(delete(ws + "/members/" + memberId), owner).andExpect(jsonPath("$.data.length()").value(1));
        call(get(ws), member).andExpect(status().isForbidden());
        call(delete(ws), owner).andExpect(status().isNoContent());
        call(get("/api/workspaces"), owner).andExpect(jsonPath("$.data.length()").value(0));

        // 로그아웃하면 401
        call(post("/api/auth/logout"), owner).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(owner)).andExpect(status().isUnauthorized());
    }

    @Test
    void 구글_로그인은_설정이_없으면_로그인_화면으로_돌려보낸다() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/google/callback").param("code", "x").param("state", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("http://localhost:5173/login?error=OAUTH_STATE_MISMATCH");
    }

    // ===================== 도우미 =====================

    private MockHttpSession login(String email, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/dev-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"%s\"}".formatted(email, name)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        return mvc.perform(request.session(session));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String slots(LocalDate day, String... times) {
        StringBuilder sb = new StringBuilder("{\"slots\":[");
        for (int i = 0; i < times.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"startAt\":\"%sT%s:00\",\"preference\":\"AVAILABLE\"}".formatted(day, times[i]));
        }
        return sb.append("]}").toString();
    }
}
