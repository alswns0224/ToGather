package com.togather.content.drive.dto;

import com.togather.content.drive.client.DriveApiClient.DriveFile;
import java.time.LocalDateTime;

public record DriveUploadCandidate(String fileId, String name, String mimeType, Long fileSize,
                                    LocalDateTime lastModifiedAt, String version, String deepLink,
                                    boolean overwriteAllowed) {
    public static DriveUploadCandidate from(DriveFile file) {
        return new DriveUploadCandidate(file.id(), file.name(), file.mimeType(), file.fileSize(),
                file.modifiedAt(), file.version(), file.webViewLink(), file.canOverwrite());
    }
}
