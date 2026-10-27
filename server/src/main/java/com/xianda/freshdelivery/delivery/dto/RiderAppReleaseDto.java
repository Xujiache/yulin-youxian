package com.xianda.freshdelivery.delivery.dto;

public record RiderAppReleaseDto(
        long id,
        String channel,
        String versionName,
        int versionCode,
        String title,
        String notes,
        String policy,
        String packageName,
        String fileName,
        String fileUrl,
        long fileSize,
        String fileSha256,
        String certSha256,
        String sourceSha,
        String status,
        String publishedBy,
        String publishedAt,
        int notifyCount,
        String lastNotifiedAt,
        String createdAt,
        boolean hasApkFile,
        boolean current
) {
}
