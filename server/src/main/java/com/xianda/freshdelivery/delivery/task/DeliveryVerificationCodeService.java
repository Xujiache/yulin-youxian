package com.xianda.freshdelivery.delivery.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeliveryVerificationCodeService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryTaskEventDao eventDao;
    private final DeliveryTaskEventRecorder eventRecorder;

    public DeliveryVerificationCodeService(
            DeliveryTaskEventDao eventDao,
            DeliveryTaskEventRecorder eventRecorder
    ) {
        this.eventDao = eventDao;
        this.eventRecorder = eventRecorder;
    }

    public IssuedCode issue(DeliveryTask task, Duration ttl, TaskOperator operator) {
        if (task == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND);
        }
        Duration safeTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(2) : ttl;
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String salt = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = TaskTimes.now().plus(safeTtl);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("salt", salt);
        detail.put("codeHash", hash(task.id(), salt, code));
        detail.put("expiresAt", TaskTimes.format(expiresAt));
        eventRecorder.record(
                task,
                DeliveryTaskEventRecorder.TYPE_VERIFY_CODE_ISSUED,
                task.status(),
                task.status(),
                operator == null ? TaskOperator.system() : operator,
                "生成送达核销码",
                null,
                null,
                null,
                detail
        );
        return new IssuedCode(code, expiresAt);
    }

    public void validate(DeliveryTask task, String suppliedCode, boolean required) {
        boolean supplied = suppliedCode != null && !suppliedCode.isBlank();
        if (!supplied) {
            if (required) {
                throw invalid("当前配置要求送达必须填写核销码");
            }
            return;
        }
        DeliveryTaskEvent issued = eventDao.findLatestByTaskAndType(
                        task.id(), DeliveryTaskEventRecorder.TYPE_VERIFY_CODE_ISSUED)
                .orElseThrow(() -> invalid("核销码不是服务端签发值，请重新获取"));
        VerificationRecord record = parse(issued.detailJson());
        if (record == null) {
            throw invalid("核销码签发记录无效，请重新获取");
        }
        LocalDateTime now = TaskTimes.now();
        if (!now.isBefore(record.expiresAt())) {
            throw invalid("核销码已过期，请重新获取");
        }
        byte[] expected = record.codeHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(task.id(), record.salt(), suppliedCode.trim())
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid("核销码不正确");
        }
    }

    private static VerificationRecord parse(String detailJson) {
        try {
            JsonNode root = JSON.readTree(detailJson);
            String salt = text(root, "salt");
            String codeHash = text(root, "codeHash");
            LocalDateTime expiresAt = TaskTimes.parse(text(root, "expiresAt"));
            if (salt == null || codeHash == null || expiresAt == null) {
                return null;
            }
            return new VerificationRecord(salt, codeHash, expiresAt);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String hash(long taskId, String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((taskId + ":" + salt + ":" + code)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static DeliveryException invalid(String message) {
        return new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, message);
    }

    public record IssuedCode(String code, LocalDateTime expiresAt) {
    }

    private record VerificationRecord(String salt, String codeHash, LocalDateTime expiresAt) {
    }
}
