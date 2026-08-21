package com.xianda.freshdelivery.delivery.app;

public record ApkInspection(
        String packageName,
        int versionCode,
        String versionName,
        String certSha256
) {
}
