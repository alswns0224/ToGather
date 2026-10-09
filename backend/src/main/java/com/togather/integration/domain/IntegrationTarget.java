package com.togather.integration.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-06. Slack 채널 / Notion 데이터베이스 / Drive 폴더 / Calendar 캘린더 등 연동 대상 */
@Getter
@Entity
@Table(name = "integration_target")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IntegrationTarget extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long targetId;

    private Long connectionId;

    private String externalTargetId;

    /** 화면 표시용 이름 (V2에서 추가) */
    private String name;

    public IntegrationTarget(Long connectionId, String externalTargetId, String name) {
        this.connectionId = connectionId;
        this.externalTargetId = externalTargetId;
        this.name = name;
    }
}
