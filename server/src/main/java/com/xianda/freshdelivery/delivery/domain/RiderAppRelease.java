package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderAppRelease(
        Long id,
        String channel,
        String versionName,
        int versionCode,
        String title,
        String notes,
        String policy,
        String packageName,
        String fileName,
        String filePath,
        String fileUrl,
        long fileSize,
        String fileSha256,
        String certSha256,
        String sourceSha,
        String status,
        String publishedBy,
        LocalDateTime publishedAt,
        int notifyCount,
        LocalDateTime lastNotifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
