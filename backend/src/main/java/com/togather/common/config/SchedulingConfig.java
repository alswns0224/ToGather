package com.togather.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 증분 동기화(B-03)와 자동 논의 분석(B-07) 스케줄러를 켠다.
 * 테스트처럼 스케줄러가 돌면 안 되는 곳에서는 togather.scheduling.enabled=false로 끈다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "togather.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
