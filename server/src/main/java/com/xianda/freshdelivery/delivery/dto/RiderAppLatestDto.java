package com.xianda.freshdelivery.delivery.dto;

public record RiderAppLatestDto(
        String policy,
        String channel,
        Integer versionCode,
        String versionName,
        String title,
        String notes,
        String fileUrl,
        Long fileSize,
        String fileSha256,
        String packageName,
        String certSha256,
        Integer minSupportedVersionCode
) {
    public static RiderAppLatestDto none(String channel) {
        return new RiderAppLatestDto("NONE", channel, null, null, null, null, null, null, null, null, null, null);
    }
}
