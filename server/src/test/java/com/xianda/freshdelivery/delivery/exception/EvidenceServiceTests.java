package com.xianda.freshdelivery.delivery.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryEvidence;
import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import com.xianda.freshdelivery.delivery.settlement.A6Fixtures;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class EvidenceServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 16, 20, 0);

    private JdbcTemplate jdbcTemplate;
    private ExceptionEvidenceDao evidenceDao;
    private EvidenceService evidenceService;
    private Path storageRoot;
    private long riderId;
    private long taskId;

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate = A6Fixtures.database("a6_evidence_service");
        evidenceDao = new ExceptionEvidenceDao(jdbcTemplate);
        storageRoot = Files.createTempDirectory("a6-evidence");
        evidenceService = new EvidenceService(evidenceDao, new ExceptionTaskQueryDao(jdbcTemplate),
                new ImageWatermarker(), storageRoot.toString(), new MutableClock(NOW));
        riderId = A6Fixtures.insertRider(jdbcTemplate, "周九", 100);
        taskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec("PS20260811000777", riderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (Stream<Path> paths = Files.walk(storageRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        path.toFile().deleteOnExit();
                    }
                });
            }
        }
    }

    @Test
    void rejectsFileWhoseMagicNumberIsNotAnImage() {
        MockMultipartFile disguised = new MockMultipartFile("file", "evil.jpg", "image/jpeg",
                "PK\u0003\u0004 this is a zip pretending to be a photo".getBytes(StandardCharsets.UTF_8));
        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> evidenceService.upload(riderId, disguised, "EXCEPTION", taskId, null, null, null, null));
        assertEquals(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, exception.code());
        assertTrue(exception.getMessage().contains("文件内容与图片格式不符"));
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0]);
        assertThrows(DeliveryException.class,
                () -> evidenceService.upload(riderId, empty, "EXCEPTION", taskId, null, null, null, null));
    }

    @Test
    void rejectsFileLargerThanFiveMegabytes() {
        byte[] oversize = new byte[(int) EvidenceService.MAX_FILE_SIZE_BYTES + 1024];
        oversize[0] = (byte) 0xFF;
        oversize[1] = (byte) 0xD8;
        oversize[2] = (byte) 0xFF;
        MockMultipartFile big = new MockMultipartFile("file", "big.jpg", "image/jpeg", oversize);
        assertThrows(DeliveryException.class,
                () -> evidenceService.upload(riderId, big, "DELIVERED", taskId, null, null, null, null));
    }

    @Test
    void storesWatermarkedPngUnderMonthFolder() throws IOException {
        MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", pngBytes(640, 480));
        EvidenceDto dto = evidenceService.upload(riderId, png, "DELIVERED", taskId, null,
                30.1234567d, 120.7654321d, "2026-08-11T16:19:30");
        assertNotNull(dto.id());
        assertTrue(dto.fileUrl().startsWith("/uploads/delivery/202608/"), dto.fileUrl());
        assertTrue(dto.fileUrl().endsWith(".png"), dto.fileUrl());
        assertEquals("DELIVERED", dto.evidenceType());

        Path stored = storageRoot.resolve("202608").resolve(dto.fileUrl().substring(dto.fileUrl().lastIndexOf('/') + 1));
        assertTrue(Files.exists(stored));
        BufferedImage rendered = ImageIO.read(stored.toFile());
        assertNotNull(rendered);
        assertEquals(640, rendered.getWidth());

        DeliveryEvidence saved = evidenceDao.findById(dto.id()).orElseThrow();
        assertTrue(saved.watermarkText().contains("2026-08-11 16:19:30"), saved.watermarkText());
        assertTrue(saved.watermarkText().contains("30.123457"), saved.watermarkText());
        assertTrue(saved.watermarkText().contains("PS20260811000777"), saved.watermarkText());
        assertEquals(riderId, saved.riderId().longValue());
        assertEquals(taskId, saved.taskId().longValue());
    }

    @Test
    void riderCannotUploadEvidenceForAnotherRidersTaskOrException() throws IOException {
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "越权骑手", 100);
        long otherTaskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, otherRiderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
        jdbcTemplate.update("""
                INSERT INTO delivery_exception
                    (exception_no, task_id, rider_id, exception_type, severity, status, source)
                VALUES ('YC-CROSS', ?, ?, 'OTHER', 'NORMAL', 'OPEN', 'RIDER')
                """, otherTaskId, otherRiderId);
        Long exceptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM delivery_exception WHERE exception_no = 'YC-CROSS'", Long.class);
        MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", pngBytes(32, 32));

        assertThrows(DeliveryException.class,
                () -> evidenceService.upload(riderId, png, "EXCEPTION", otherTaskId,
                        null, null, null, null));
        assertThrows(DeliveryException.class,
                () -> evidenceService.upload(riderId, png, "EXCEPTION", null,
                        exceptionId, null, null, null));
        assertEquals(0, evidenceDao.search(null, null, null, 20).size());
    }

    @Test
    void acceptsJpegAndFallsBackToUploadTimeWhenCapturedAtMissing() throws IOException {
        MockMultipartFile jpeg = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes(320, 240));
        EvidenceDto dto = evidenceService.upload(riderId, jpeg, null, null, null, null, null, null);
        assertEquals("EXCEPTION", dto.evidenceType());
        assertEquals("2026-08-11T16:20:00", dto.capturedAt());
        assertTrue(dto.fileUrl().endsWith(".jpg"));
        DeliveryEvidence saved = evidenceDao.findById(dto.id()).orElseThrow();
        assertNull(saved.taskId());
        assertTrue(saved.watermarkText().contains("2026-08-11 16:20:00"));
    }

    @Test
    void keepsWebpBytesUntouchedBecauseImageIoCannotDecodeIt() {
        byte[] webp = webpBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.webp", "image/webp", webp);
        EvidenceDto dto = evidenceService.upload(riderId, file, "EXCEPTION", taskId, null, null, null, null);
        assertTrue(dto.fileUrl().endsWith(".webp"), dto.fileUrl());
        DeliveryEvidence saved = evidenceDao.findById(dto.id()).orElseThrow();
        assertEquals(webp.length, saved.fileSize().intValue());
        assertNotNull(saved.watermarkText());
    }

    @Test
    void sniffRecognisesAllowedFormatsOnly() throws IOException {
        assertEquals(ImageFormats.PNG, ImageFormats.sniff(pngBytes(8, 8)));
        assertEquals(ImageFormats.JPEG, ImageFormats.sniff(jpegBytes(8, 8)));
        assertEquals(ImageFormats.WEBP, ImageFormats.sniff(webpBytes()));
        assertNull(ImageFormats.sniff("GIF89a0000000000".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageFormats.sniff(new byte[]{1, 2, 3}));
        assertNull(ImageFormats.sniff(null));
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        return imageBytes(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] jpegBytes(int width, int height) throws IOException {
        return imageBytes(width, height, "jpg", BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] imageBytes(int width, int height, String format, int type) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(200, 220, 240));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static byte[] webpBytes() {
        byte[] bytes = new byte[64];
        byte[] riff = "RIFF".getBytes(StandardCharsets.US_ASCII);
        byte[] webp = "WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(riff, 0, bytes, 0, riff.length);
        System.arraycopy(webp, 0, bytes, 8, webp.length);
        return bytes;
    }
}
