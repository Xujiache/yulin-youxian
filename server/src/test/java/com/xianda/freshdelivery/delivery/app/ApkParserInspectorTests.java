package com.xianda.freshdelivery.delivery.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApkParserInspectorTests {
    @Test
    void readsV2CertificateFromLocalReleaseApk() {
        Path apk = Path.of("..", "rider-android", "app", "build", "outputs", "apk", "release", "app-release.apk")
                .toAbsolutePath()
                .normalize();
        assumeTrue(Files.isRegularFile(apk), "local release APK is optional in CI");
        ApkInspection inspection = new ApkParserInspector().inspect(apk);
        assertEquals("com.yulin.rider", inspection.packageName());
        assertEquals("3b88d80ccb2a239edcabc6aa43d3eea28f80ea7f50b320c9e43ca2f44a7c732b", inspection.certSha256());
    }
}
