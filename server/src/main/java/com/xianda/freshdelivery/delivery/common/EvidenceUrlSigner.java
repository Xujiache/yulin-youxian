package com.xianda.freshdelivery.delivery.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 凭证文件的限时访问票据。
 *
 * 送达照片要给顾客看，但小程序的 {@code <image>} 和 {@code wx.previewImage} 都带不了请求头，
 * 靠 Authorization 鉴权的静态资源在小程序里必然是一排破图。反过来直接公开这个目录，
 * 等于谁拿到 URL 都能看别人家门口的照片。
 *
 * 折中成把「能看这个文件」这件事本身签成一张短期票据：签名绑定具体文件路径和过期时刻，
 * 用服务端密钥签，泄露出去也只在有效期内、只对那一个文件有效。
 */
@Component
public class EvidenceUrlSigner {
    /** 过期时刻参数名，取短名字是因为它会出现在每一张图片的 src 上。 */
    public static final String PARAM_EXPIRES = "e";
    public static final String PARAM_SIGNATURE = "s";

    private static final Logger log = LoggerFactory.getLogger(EvidenceUrlSigner.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String UPLOADS_PREFIX = "/uploads/";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public EvidenceUrlSigner(@Value("${delivery.upload.signing-secret:}") String signingSecret,
                             @Value("${delivery.upload.signature-ttl-seconds:1800}") long ttlSeconds) {
        this(signingSecret, ttlSeconds, Clock.systemDefaultZone());
    }

    EvidenceUrlSigner(String signingSecret, long ttlSeconds, Clock clock) {
        this.secret = resolveSecret(signingSecret);
        this.ttl = Duration.ofSeconds(Math.max(60L, ttlSeconds));
        this.clock = clock;
    }

    /**
     * 没配密钥时用进程内随机值兜底。
     *
     * 宁可让签发过的链接在重启后失效（顾客重进页面就会拿到新的），
     * 也不能退化成一个可预测的默认密钥——那等于没签。
     */
    private static byte[] resolveSecret(String signingSecret) {
        if (signingSecret != null && !signingSecret.isBlank()) {
            return signingSecret.trim().getBytes(StandardCharsets.UTF_8);
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.warn("未配置 delivery.upload.signing-secret，本次启动使用随机密钥，"
                + "已签发的凭证链接会在重启后失效；多实例部署必须显式配置");
        return random;
    }

    /**
     * 把库里存的相对路径签成带票据的地址。
     *
     * 外链（已经在对象存储上的）原样返回：那种地址的访问控制不在我们这儿。
     */
    public String sign(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank() || !fileUrl.startsWith(UPLOADS_PREFIX)) {
            return fileUrl;
        }
        String path = stripQuery(fileUrl);
        long expiresAt = clock.instant().plus(ttl).getEpochSecond();
        return path + "?" + PARAM_EXPIRES + "=" + expiresAt
                + "&" + PARAM_SIGNATURE + "=" + signature(path, expiresAt);
    }

    /**
     * 校验一次请求带的票据。
     *
     * 路径按 {@code /uploads/} 起算，避免部署时挂了 context-path 就和签发时对不上。
     */
    public boolean verify(String requestPath, String expiresAt, String signature) {
        if (requestPath == null || expiresAt == null || signature == null) {
            return false;
        }
        String path = canonicalPath(requestPath);
        if (path == null) {
            return false;
        }
        long deadline;
        try {
            deadline = Long.parseLong(expiresAt.trim());
        } catch (NumberFormatException notANumber) {
            return false;
        }
        if (clock.instant().getEpochSecond() > deadline) {
            return false;
        }
        return MessageDigest.isEqual(
                signature(path, deadline).getBytes(StandardCharsets.US_ASCII),
                signature.trim().getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(String path, long expiresAt) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            // 换行分隔，否则路径末尾的数字和时间戳会粘在一起产生同一份签名原文
            byte[] digest = mac.doFinal((path + "\n" + expiresAt).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("凭证签名失败", failure);
        }
    }

    private static String canonicalPath(String requestPath) {
        int start = requestPath.indexOf(UPLOADS_PREFIX);
        return start < 0 ? null : stripQuery(requestPath.substring(start));
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
