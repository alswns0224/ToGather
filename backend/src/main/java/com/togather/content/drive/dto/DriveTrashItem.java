package com.togather.content.drive.dto;

import java.time.LocalDateTime;

/**
 * 휴지통 항목.
 *
 * @param source TOGATHER: ToGather 휴지통 폴더에 있는 항목, DRIVE: Google Drive 휴지통에 있는 파일
 * @param contentRefId ToGather가 수집한 파일이면 그 ID (DRIVE 항목의 복원에 쓴다)
 * @param originPath 지우기 전 위치 (예: "11/27 Scenario1 / 발표 자료"). 알 수 없으면 null
 */
public record DriveTrashItem(
    String itemId,
    String name,
    boolean folder,
    String mimeType,
    Long fileSize,
    LocalDateTime trashedAt,
    Long trashedBy,
    String trashedByName,
    Long contentRefId,
    String source,
    String originPath
) {

    public DriveTrashItem(String itemId, String name, boolean folder, String mimeType, Long fileSize,
                          LocalDateTime trashedAt, Long trashedBy, String trashedByName, Long contentRefId,
                          String source) {
        this(itemId, name, folder, mimeType, fileSize, trashedAt, trashedBy, trashedByName, contentRefId, source, null);
    }
    public static final String TOGATHER = "TOGATHER";
    public static final String DRIVE = "DRIVE";
}
