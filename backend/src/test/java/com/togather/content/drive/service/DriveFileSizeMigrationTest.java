package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DriveFileSizeMigrationTest {
    @Test
    void 기존_DB는_크기_미확인으로_이전하고_Drive만_전체_동기화한다() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("10").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            Long workspace = jdbc.queryForObject("INSERT INTO workspace(name,created_at,updated_at) VALUES('이전 검증',now(),now()) RETURNING workspace_id", Long.class);
            for (String provider : new String[] {"GOOGLE_DRIVE", "GMAIL"}) {
                jdbc.update("INSERT INTO sync_state(workspace_id,provider,target_id,sync_cursor,created_at,updated_at) VALUES(?,?,'folder','previous',now(),now())", workspace, provider);
            }
            Long ref = jdbc.queryForObject("""
                INSERT INTO content_ref(workspace_id,provider,content_type,origin_id,created_at)
                VALUES(?,'GOOGLE_DRIVE','FILE','existing',now()) RETURNING content_ref_id
                """, Long.class, workspace);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT file_size FROM content_ref WHERE content_ref_id=?", Long.class, ref)).isNull();
            assertThat(jdbc.queryForObject("SELECT sync_cursor FROM sync_state WHERE provider='GOOGLE_DRIVE'", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT sync_cursor FROM sync_state WHERE provider='GMAIL'", String.class)).isEqualTo("previous");
            jdbc.update("UPDATE content_ref SET file_size=5000000000 WHERE content_ref_id=?", ref);
            assertThat(jdbc.queryForObject("SELECT file_size FROM content_ref WHERE content_ref_id=?", Long.class, ref)).isEqualTo(5000000000L);
            assertThatThrownBy(() -> jdbc.update("UPDATE content_ref SET file_size=-1 WHERE content_ref_id=?", ref))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
