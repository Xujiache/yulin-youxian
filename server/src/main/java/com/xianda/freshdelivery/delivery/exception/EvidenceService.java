package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryEvidence;
import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceService {
    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    public static final String URL_PREFIX = "/uploads/delivery/";

    private static final Set<String> ALLOWED_TYPES =
            Set.of("PICKUP", "DELIVERED", "EXCEPTION", "WEIGHT_SCALE", "RETURN", "SIGNATURE");
    private static final DateTimeFormatter MONTH_FOLDER = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);
    private static final DateTimeFormatter WATERMARK_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final ExceptionEvidenceDao evidenceDao;
    private final ExceptionTaskQueryDao taskQueryDao;
    private final ImageWatermarker watermarker;
    private final String storageRoot;
    private final Clock clock;
    private final TaskUnitOfWork unitOfWork;

    @Autowired
    public EvidenceService(ExceptionEvidenceDao evidenceDao,
                           ExceptionTaskQueryDao taskQueryDao,
                           ImageWatermarker watermarker,
                           @Value("${delivery.upload.delivery-path:data/uploads/delivery}") String storageRoot,
                           TaskUnitOfWork unitOfWork) {
        this(evidenceDao, taskQueryDao, watermarker, storageRoot,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public EvidenceService(ExceptionEvidenceDao evidenceDao,
                           ExceptionTaskQueryDao taskQueryDao,
                           ImageWatermarker watermarker,
                           String storageRoot) {
        this(evidenceDao, taskQueryDao, watermarker, storageRoot,
                Clock.system(DeliveryTimes.STORE_ZONE), TaskUnitOfWork.direct());
    }

    public EvidenceService(ExceptionEvidenceDao evidenceDao,
                           ExceptionTaskQueryDao taskQueryDao,
                           ImageWatermarker watermarker,
                           String storageRoot,
                           Clock clock) {
        this(evidenceDao, taskQueryDao, watermarker, storageRoot, clock, TaskUnitOfWork.direct());
    }

    EvidenceService(ExceptionEvidenceDao evidenceDao,
                    ExceptionTaskQueryDao taskQueryDao,
                    ImageWatermarker watermarker,
                    String storageRoot,
                    Clock clock,
                    TaskUnitOfWork unitOfWork) {
        this.evidenceDao = evidenceDao;
        this.taskQueryDao = taskQueryDao;
        this.watermarker = watermarker;
        this.storageRoot = storageRoot;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    public EvidenceDto upload(Long riderId, MultipartFile file, String evidenceType, Long taskId,
                              Long exceptionId, Double lat, Double lng, String capturedAt) {
        Long boundTaskId = validateBinding(riderId, taskId, exceptionId, false);
        String normalizedType = normalizeType(evidenceType);
        byte[] original = readBytes(file);
        String format = ImageFormats.sniff(original);
        if (format == null) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED,
                    "仅支持 jpg、png、webp 图片，文件内容与图片格式不符");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime captured = DeliveryTimes.parseDateTime(capturedAt);
        LocalDateTime shotAt = captured == null ? now : captured;
        String taskNo = boundTaskId == null ? null : taskQueryDao.findById(boundTaskId)
                .map(ExceptionTaskQueryDao.TaskSnapshot::taskNo)
                .orElse(null);
        String watermarkText = buildWatermarkText(shotAt, lat, lng, taskNo);
        ImageWatermarker.Result rendered = watermarker.apply(original, format, watermarkLines(shotAt, lat, lng, taskNo));
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + format;
        String monthFolder = MONTH_FOLDER.format(now);
        Path directory = Path.of(storageRoot, monthFolder);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), rendered.bytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, "凭证保存失败");
        }
        DeliveryEvidence evidence = new DeliveryEvidence(
                null,
                boundTaskId,
                exceptionId,
                null,
                riderId,
                normalizedType,
                URL_PREFIX + monthFolder + "/" + fileName,
                rendered.bytes().length,
                rendered.width(),
                rendered.height(),
                lat,
                lng,
                watermarkText,
                shotAt,
                now
        );
        long id;
        try {
            id = unitOfWork.commit(() -> {
                Long lockedTaskId = validateBinding(riderId, taskId, exceptionId, true);
                if (!Objects.equals(boundTaskId, lockedTaskId)) {
                    throw new DeliveryException(
                            DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "凭证绑定目标已发生变化，请重试");
                }
                return evidenceDao.insert(evidence, now);
            });
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(directory.resolve(fileName));
            } catch (IOException ignored) {
                // Best-effort compensation for a database failure.
            }
            throw exception;
        }
        return new EvidenceDto(id, evidence.fileUrl(), evidence.evidenceType(), DeliveryTimes.format(shotAt));
    }

    private Long validateBinding(
            Long riderId,
            Long requestedTaskId,
            Long exceptionId,
            boolean forUpdate
    ) {
        Long taskId = requestedTaskId;
        if (exceptionId != null) {
            Optional<ExceptionEvidenceDao.ExceptionBinding> bindingLookup = forUpdate
                    ? evidenceDao.findExceptionBindingForUpdate(exceptionId)
                    : evidenceDao.findExceptionBinding(exceptionId);
            ExceptionEvidenceDao.ExceptionBinding binding = bindingLookup
                    .orElseThrow(() -> new DeliveryException(404, "异常记录不存在：" + exceptionId));
            if (riderId != null && !Objects.equals(binding.riderId(), riderId)) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "异常记录不属于当前骑手");
            }
            if (taskId != null && !Objects.equals(taskId, binding.taskId())) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "凭证任务与异常任务不一致");
            }
            taskId = binding.taskId();
        }
        if (taskId == null) {
            return null;
        }
        Long resolvedTaskId = taskId;
        Optional<ExceptionTaskQueryDao.TaskSnapshot> taskLookup = forUpdate
                ? taskQueryDao.findByIdForUpdate(resolvedTaskId)
                : taskQueryDao.findById(resolvedTaskId);
        ExceptionTaskQueryDao.TaskSnapshot task = taskLookup
                .orElseThrow(() -> new DeliveryException(
                        DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + resolvedTaskId));
        if (riderId != null && !Objects.equals(task.riderId(), riderId)) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER);
        }
        return resolvedTaskId;
    }

    public List<EvidenceDto> search(Long taskId, String evidenceType, Long exceptionId, int limit) {
        return evidenceDao.search(taskId, evidenceType, exceptionId, limit).stream()
                .map(evidence -> new EvidenceDto(evidence.id(), evidence.fileUrl(), evidence.evidenceType(),
                        DeliveryTimes.format(evidence.capturedAt())))
                .toList();
    }

    static String buildWatermarkText(LocalDateTime shotAt, Double lat, Double lng, String taskNo) {
        return String.join(" | ", watermarkLines(shotAt, lat, lng, taskNo));
    }

    private static List<String> watermarkLines(LocalDateTime shotAt, Double lat, Double lng, String taskNo) {
        List<String> lines = new ArrayList<>(3);
        lines.add(WATERMARK_TIME.format(shotAt));
        if (lat != null && lng != null) {
            lines.add(String.format(Locale.ROOT, "%.6f, %.6f", lat, lng));
        }
        if (taskNo != null && !taskNo.isBlank()) {
            lines.add("任务 " + taskNo);
        }
        return lines;
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, "凭证图片不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, "凭证图片不能超过 5MB");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_FILE_SIZE_BYTES) {
                throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, "凭证图片不能超过 5MB");
            }
            return bytes;
        } catch (IOException exception) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED, "凭证读取失败");
        }
    }

    private static String normalizeType(String evidenceType) {
        if (evidenceType == null || evidenceType.isBlank()) {
            return "EXCEPTION";
        }
        String upper = evidenceType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(upper)) {
            throw new DeliveryException(DeliveryErrorCode.EVIDENCE_UPLOAD_FAILED,
                    "不支持的凭证类型：" + evidenceType);
        }
        return upper;
    }
}
