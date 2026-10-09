package com.togather.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 로컬 실행용 내장 PostgreSQL (LocalTogatherApplication). 재시작해도 데이터가 남는다.
 * 처음 상태로 되돌리려면 backend/.local-postgres 폴더를 지운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalPostgresConfig {

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres localPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setDataDirectory(new File(".local-postgres"))
                .setCleanDataDirectory(false)
                .setPort(54329)
                .start();
    }

    @Bean
    @Primary
    public DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
