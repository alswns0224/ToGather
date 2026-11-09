package com.togather.content.domain;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * C-00-07.
 *
 * 외부 콘텐츠의 공통 참조 Entity.
 *
 * 지원 서비스:
 * - Slack 메시지
 * - Notion 페이지
 * - Google Drive 파일
 * - Google Calendar 일정
 * - Gmail 메일
 *
 * createdAt은 외부 원본의 생성 시각이므로
 * BaseTimeEntity를 상속하지 않는다.
 *
 * Gmail/Calendar 같은 개인 콘텐츠는
 * ownerUserId 조건이 있는 Repository 메서드로만 조회한다.
 *
 * 원본 삭제 및 복원 상태를 관리한다.
 */
@Getter
@Entity
@Table(name = "content_ref")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long contentRefId;

    private Long workspaceId;

    /**
     * 개인 콘텐츠(Gmail, Calendar)의 소유자.
     * 팀 콘텐츠는 null.
     */
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    private ContentType contentType;

    /**
     * 외부 서비스의 원본 ID.
     */
    private String originId;

    /**
     * Slack 채널 ID 또는
     * Google Drive 폴더 ID 등.
     */
    private String channelId;

    /**
     * 상위 콘텐츠 참조 ID.
     */
    private Long parentRefId;

    /**
     * 콘텐츠 본문 또는 메타데이터.
     */
    private String content;

    /**
     * 외부 원본 삭제 여부.
     *
     * true:
     * 원본이 삭제되었거나 휴지통으로 이동된 상태.
     *
     * false:
     * 정상적으로 존재하는 상태.
     */
    private boolean isOriginDeleted;

    private boolean driveTrashed;

    /**
     * 원본이 삭제되었을 때 보관한
     * 마지막 콘텐츠 사본.
     */
    private String snapshot;

    private String title;

    private String deepLink;

    /**
     * 외부 원본 생성 시각.
     */
    private LocalDateTime createdAt;

    /**
     * 외부 원본 최종 수정 시각.
     *
     * 알 수 없으면 createdAt과 동일.
     */
    private LocalDateTime lastModifiedAt;

    /**
     * 마지막 동기화에서 콘텐츠의
     * 저장·갱신에 실패했는지 여부.
     */
    private boolean syncFailed;

    /**
     * 작성자의 외부 서비스 ID.
     *
     * 예: Slack 사용자 ID.
     */
    private String authorExternalId;

    private String authorName;

    /**
     * ContentRef 생성자.
     *
     * 기존 Builder 구조 유지.
     */
    @Builder
    private ContentRef(
        Long workspaceId,
        Long ownerUserId,
        Provider provider,
        ContentType contentType,
        String originId,
        String channelId,
        Long parentRefId,
        String content,
        String title,
        String deepLink,
        LocalDateTime createdAt,
        LocalDateTime lastModifiedAt,
        String authorExternalId,
        String authorName
    ) {

        this.workspaceId = workspaceId;
        this.ownerUserId = ownerUserId;
        this.provider = provider;
        this.contentType = contentType;
        this.originId = originId;
        this.channelId = channelId;
        this.parentRefId = parentRefId;
        this.content = content;
        this.title = title;
        this.deepLink = deepLink;
        this.createdAt = createdAt;

        this.lastModifiedAt =
            lastModifiedAt != null
                ? lastModifiedAt
                : createdAt;

        this.authorExternalId = authorExternalId;
        this.authorName = authorName;
    }

    /**
     * 1. 콘텐츠 제목 및 본문 수정.
     *
     * 기존 기능 유지.
     */
    public void updateContent(
        String title,
        String content
    ) {

        this.title = title;
        this.content = content;
    }

    /**
     * 2. 동기화로 받은 최신 원본을 반영한다.
     *
     * 기존 동작 유지.
     *
     * deepLink, lastModifiedAt,
     * authorExternalId, authorName이 null이면
     * 기존 값을 유지한다.
     *
     * 정상 갱신 시 syncFailed를 false로 변경한다.
     *
     * 주의:
     * 이 메서드는 원본 삭제 상태를 직접 변경하지 않는다.
     *
     * 삭제 상태 해제는 restoreOriginDeleted()에서 처리한다.
     */
    public void applySync(
        String title,
        String content,
        String deepLink,
        LocalDateTime lastModifiedAt,
        String authorExternalId,
        String authorName
    ) {

        this.title = title;
        this.content = content;

        if (deepLink != null) {
            this.deepLink = deepLink;
        }

        if (lastModifiedAt != null) {
            this.lastModifiedAt = lastModifiedAt;
        }

        if (authorExternalId != null) {
            this.authorExternalId = authorExternalId;
        }

        if (authorName != null) {
            this.authorName = authorName;
        }

        this.syncFailed = false;
    }

    /**
     * 3. 상위 콘텐츠 연결.
     *
     * Slack의 "채널에도 보내기" 메시지를
     * 기존 스레드에 연결할 때 사용한다.
     *
     * 기존 상위 참조가 없다면 연결한다.
     *
     * 자기 자신을 상위 콘텐츠로 지정하지 않는다.
     */
    public void attachToParent(
        Long parentRefId
    ) {

        if (this.parentRefId == null
            && parentRefId != null
            && !parentRefId.equals(
            this.contentRefId
        )) {

            this.parentRefId = parentRefId;
        }
    }

    /**
     * 4. 동기화 실패 표시.
     *
     * 기존 기능 유지.
     */
    public void markSyncFailed() {

        this.syncFailed = true;
    }

    /**
     * 5. 외부 원본 삭제 상태 표시.
     *
     * 원본이 삭제되거나 휴지통으로 이동되면
     * 현재 콘텐츠를 snapshot에 보관한다.
     *
     * Google Drive:
     * 휴지통 이동 후 호출.
     *
     * Slack, Notion 등:
     * 외부 원본 삭제 감지 시 호출.
     *
     * 중복 호출 시 기존 스냅샷을 유지한다.
     */
    public void markOriginDeleted() {

        if (this.isOriginDeleted) {
            return;
        }

        this.isOriginDeleted = true;
        this.snapshot = this.content;
    }

    /**
     * 6. 외부 원본 삭제 상태 복원.
     *
     * 신규 추가 기능.
     *
     * Google Drive:
     * 휴지통에서 파일을 복원한 후 호출.
     *
     * 다른 서비스:
     * 삭제됐던 원본이 다시 확인된 경우 사용 가능.
     *
     * 복원 시 처리:
     * - isOriginDeleted = false
     * - snapshot 초기화
     *
     * title, content, originId, channelId,
     * parentRefId 등 기존 데이터는 유지한다.
     *
     * 최신 메타데이터는 ContentRefService.upsert()
     * 또는 applySync()를 통해 별도로 갱신한다.
     */
    public void restoreOriginDeleted() {

        if (!this.isOriginDeleted) {
            return;
        }

        this.isOriginDeleted = false;
        this.snapshot = null;
        this.driveTrashed = false;
    }

    public boolean applyDriveLocation(String folderId, DriveFileState state) {
        boolean originDeleted = state != DriveFileState.AVAILABLE;
        boolean trashed = state == DriveFileState.TRASHED;
        boolean changed = !Objects.equals(channelId, folderId)
            || isOriginDeleted != originDeleted
            || driveTrashed != trashed;

        channelId = folderId;
        if (originDeleted) {
            markOriginDeleted();
        } else {
            restoreOriginDeleted();
        }
        driveTrashed = trashed;
        return changed;
    }

    public enum DriveFileState {
        AVAILABLE,
        TRASHED,
        UNAVAILABLE
    }
}
