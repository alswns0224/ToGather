package com.togather.task.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.LinkType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-13. Task와 외부 콘텐츠(ContentRef)의 연결 */
@Getter
@Entity
@Table(name = "task_content_link")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskContentLink extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long linkId;

    private Long taskId;

    private Long contentRefId;

    @Enumerated(EnumType.STRING)
    private LinkType linkType;

    public TaskContentLink(Long taskId, Long contentRefId, LinkType linkType) {
        this.taskId = taskId;
        this.contentRefId = contentRefId;
        this.linkType = linkType;
    }
}
