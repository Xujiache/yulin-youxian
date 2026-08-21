package com.xianda.freshdelivery.delivery.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 送达凭证的限时票据。
 *
 * 小程序渲染图片带不了请求头，这条路径是顾客能看到送达照片的唯一办法，
 * 所以它既要真的放行、又不能变成「谁拿到路径都能看」。
 */
class EvidenceUrlSignerTests {
    private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");
    private static final String PATH = "/uploads/delivery/202608/proof.jpg";

    private final MovableClock clock = new MovableClock(NOW);
    private final EvidenceUrlSigner signer = new EvidenceUrlSigner("test-secret", 1800L, clock);

    @Test
    void 签发的地址自己能验过() {
        String signed = signer.sign(PATH);

        assertTrue(signed.startsWith(PATH + "?"), signed);
        assertTrue(signer.verify(PATH, expiresOf(signed), signatureOf(signed)));
    }

    @Test
    void 过期之后不再放行() {
        String signed = signer.sign(PATH);

        clock.advance(Duration.ofSeconds(1801));

        assertFalse(signer.verify(PATH, expiresOf(signed), signatureOf(signed)));
    }

    @Test
    void 票据只对签的那个文件有效() {
        String signed = signer.sign(PATH);

        assertFalse(signer.verify("/uploads/delivery/202608/someone-else.jpg",
                expiresOf(signed), signatureOf(signed)));
    }

    @Test
    void 改过期时刻延命不了() {
        String signed = signer.sign(PATH);
        long extended = Long.parseLong(expiresOf(signed)) + 86400L;

        assertFalse(signer.verify(PATH, String.valueOf(extended), signatureOf(signed)));
    }

    @Test
    void 换个密钥签的票据验不过() {
        String signed = new EvidenceUrlSigner("another-secret", 1800L, clock).sign(PATH);

        assertFalse(signer.verify(PATH, expiresOf(signed), signatureOf(signed)));
    }

    @Test
    void 缺参数或签名不是数字都直接拒绝() {
        assertFalse(signer.verify(PATH, null, "whatever"));
        assertFalse(signer.verify(PATH, "not-a-number", "whatever"));
        assertFalse(signer.verify(PATH, "9999999999", "tampered"));
    }

    @Test
    void 挂了context_path也能按uploads起算对上() {
        String signed = signer.sign(PATH);

        assertTrue(signer.verify("/fresh" + PATH, expiresOf(signed), signatureOf(signed)));
    }

    @Test
    void 外链和空值原样返回不加签() {
        assertEquals("https://cdn.example.com/a.jpg", signer.sign("https://cdn.example.com/a.jpg"));
        assertEquals("", signer.sign(""));
        assertEquals(null, signer.sign(null));
    }

    private static String expiresOf(String signedUrl) {
        return queryValue(signedUrl, EvidenceUrlSigner.PARAM_EXPIRES);
    }

    private static String signatureOf(String signedUrl) {
        return queryValue(signedUrl, EvidenceUrlSigner.PARAM_SIGNATURE);
    }

    private static String queryValue(String signedUrl, String name) {
        for (String pair : signedUrl.substring(signedUrl.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static final class MovableClock extends Clock {
        private Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
