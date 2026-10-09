package com.togather;

import com.togather.support.LocalPostgresConfig;
import org.springframework.boot.SpringApplication;

/**
 * Docker 없이 로컬에서 실행: ./gradlew bootTestRun
 * 내장 PostgreSQL을 띄우고 데이터는 backend/.local-postgres에 보관한다.
 */
public class LocalTogatherApplication {

    public static void main(String[] args) {
        SpringApplication.from(TogatherApplication::main)
                .with(LocalPostgresConfig.class)
                .run(args);
    }
}
