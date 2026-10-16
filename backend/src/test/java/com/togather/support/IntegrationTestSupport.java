package com.togather.support;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 내장 PostgreSQL로 Service를 검증하는 통합 테스트의 공통 설정 (B 파트).
 * 외부 API는 호출하지 않는다. 스케줄러는 끈다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://unused",
        "spring.datasource.username=unused",
        "spring.datasource.password=unused",
        "togather.app-url=http://localhost:5173",
        "togather.token-key=integration-test-key",
        "togather.scheduling.enabled=false",
        "togather.gemini.api-key="
})
@Import(EmbeddedPostgresConfig.class)
public abstract class IntegrationTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    protected Long createUser(String name, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (google_sub, email, name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now()) RETURNING user_id
                """, Long.class, UUID.randomUUID().toString(), email, name);
    }

    protected Long createWorkspace(Long ownerUserId) {
        Long workspaceId = jdbc.queryForObject("""
                INSERT INTO workspace (name, created_at, updated_at) VALUES ('테스트', now(), now())
                RETURNING workspace_id
                """, Long.class);
        addMember(workspaceId, ownerUserId, "OWNER");
        return workspaceId;
    }

    protected void addMember(Long workspaceId, Long userId, String role) {
        jdbc.update("""
                INSERT INTO workspace_member (workspace_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, workspaceId, userId, role);
    }
}
