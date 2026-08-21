package com.xianda.freshdelivery.delivery.controller.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.app.RiderAppReleaseService;
import com.xianda.freshdelivery.delivery.dto.RiderAppReleaseDto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class InternalRiderAppControllerTests {
    private static final RiderAppReleaseDto SAMPLE = new RiderAppReleaseDto(
            1L, "production", "2026.08.15.1", 26081501, "本机", "notes", "OPTIONAL",
            "com.yulin.rider", "26081501-aaaaaaaaaaaaaaaa.apk",
            "https://hqhjxt.vip/uploads/apk/production/26081501-aaaaaaaaaaaaaaaa.apk",
            12L, "a".repeat(64), "b".repeat(64), "deadbeef", "PUBLISHED",
            "server-local", "2026-08-15 10:00:00", 0, null, "2026-08-15 10:00:00",
            true, true
    );

    @Test
    void missingTokenIsUnauthorized() {
        InternalRiderAppController controller = new InternalRiderAppController(mock(RiderAppReleaseService.class), "secret");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.publishAutomated(null, apk(), null, "t", "n", "OPTIONAL", "sha", null));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void blankConfiguredTokenIsUnavailable() {
        InternalRiderAppController controller = new InternalRiderAppController(mock(RiderAppReleaseService.class), "  ");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.publishAutomated("secret", apk(), null, "t", "n", "OPTIONAL", "sha", null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void defaultOperatorIsServerLocal() {
        RiderAppReleaseService service = mock(RiderAppReleaseService.class);
        MockMultipartFile file = apk();
        when(service.publishAutomated(eq(file), isNull(), eq("本机"), eq("log"), eq("OPTIONAL"), eq("abc"), eq("server-local")))
                .thenReturn(SAMPLE);
        InternalRiderAppController controller = new InternalRiderAppController(service, "secret");
        ApiResponse<RiderAppReleaseDto> response = controller.publishAutomated(
                "secret", file, null, "本机", "log", "OPTIONAL", "abc", null);
        assertEquals(0, response.code());
        assertEquals("server-local", response.data().publishedBy());
        verify(service).publishAutomated(file, null, "本机", "log", "OPTIONAL", "abc", "server-local");
    }

    @Test
    void windowsEmergencyOperatorIsAllowedAndUnknownFallsBack() {
        assertEquals("windows-emergency", InternalRiderAppController.resolveOperator("windows-emergency"));
        assertEquals("server-local", InternalRiderAppController.resolveOperator("github-actions"));
        assertEquals("server-local", InternalRiderAppController.resolveOperator(""));
    }

    private static MockMultipartFile apk() {
        return new MockMultipartFile(
                "file",
                "rider.apk",
                "application/vnd.android.package-archive",
                "apk".getBytes(StandardCharsets.UTF_8)
        );
    }
}
