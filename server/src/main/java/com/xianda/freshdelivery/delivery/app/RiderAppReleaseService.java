package com.xianda.freshdelivery.delivery.app;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.app.RiderAppReleaseDao.RiderAppCoverageCounts;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderAppChannel;
import com.xianda.freshdelivery.delivery.domain.RiderAppRelease;
import com.xianda.freshdelivery.delivery.dto.BroadcastRequest;
import com.xianda.freshdelivery.delivery.dto.RiderAppCoverageDto;
import com.xianda.freshdelivery.delivery.dto.RiderAppLatestDto;
import com.xianda.freshdelivery.delivery.dto.RiderAppReleaseDto;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RiderAppReleaseService {
    public static final String CHANNEL_PRODUCTION = "production";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String POLICY_OPTIONAL = "OPTIONAL";
    public static final String POLICY_FORCE = "FORCE";
    public static final String POLICY_NONE = "NONE";
    public static final String URL_PREFIX = "/uploads/apk/";

    private static final Logger log = LoggerFactory.getLogger(RiderAppReleaseService.class);
    private static final long DEFAULT_MAX_BYTES = 150L * 1024 * 1024;
    private static final int DEFAULT_RETAIN = 8;

    private final RiderAppReleaseDao releaseDao;
    private final RiderAppChannelDao channelDao;
    private final ApkInspector apkInspector;
    private final MessageService messageService;
    private final Clock clock;
    private final Path storageRoot;
    private final String expectedPackageName;
    private final String expectedCertSha256;
    private final String publicBaseUrl;
    private final long maxFileSizeBytes;
    private final int retainApkCount;

    @Autowired
    public RiderAppReleaseService(
            RiderAppReleaseDao releaseDao,
            RiderAppChannelDao channelDao,
            ApkInspector apkInspector,
            MessageService messageService,
            @Value("${delivery.rider-app.storage-path:../apk-releases}") String storagePath,
            @Value("${delivery.rider-app.package-name:com.yulin.rider}") String expectedPackageName,
            @Value("${delivery.rider-app.expected-cert-sha256:}") String expectedCertSha256,
            @Value("${delivery.rider-app.public-base-url:}") String publicBaseUrl,
            @Value("${delivery.rider-app.max-file-size-bytes:157286400}") long maxFileSizeBytes,
            @Value("${delivery.rider-app.retain-apk-count:8}") int retainApkCount
    ) {
        this(releaseDao, channelDao, apkInspector, messageService, Clock.system(DeliveryTimes.STORE_ZONE),
                storagePath, expectedPackageName, expectedCertSha256, publicBaseUrl, maxFileSizeBytes, retainApkCount);
    }

    public RiderAppReleaseService(
            RiderAppReleaseDao releaseDao,
            RiderAppChannelDao channelDao,
            ApkInspector apkInspector,
            MessageService messageService,
            Clock clock,
            String storagePath,
            String expectedPackageName,
            String expectedCertSha256,
            String publicBaseUrl,
            long maxFileSizeBytes,
            int retainApkCount
    ) {
        this.releaseDao = releaseDao;
        this.channelDao = channelDao;
        this.apkInspector = apkInspector;
        this.messageService = messageService;
        this.clock = clock;
        this.storageRoot = resolvePath(storagePath);
        this.expectedPackageName = expectedPackageName == null || expectedPackageName.isBlank()
                ? "com.yulin.rider" : expectedPackageName.trim();
        this.expectedCertSha256 = normalizeSha(expectedCertSha256);
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.maxFileSizeBytes = maxFileSizeBytes > 0 ? maxFileSizeBytes : DEFAULT_MAX_BYTES;
        this.retainApkCount = retainApkCount > 0 ? retainApkCount : DEFAULT_RETAIN;
        if (this.expectedCertSha256.isBlank()) {
            log.warn("RIDER_EXPECTED_CERT_SHA256 未配置，开发环境将跳过证书钉扎");
        }
    }

    public PageResult<RiderAppReleaseDto> list(String channel, Integer page, Integer pageSize) {
        String normalized = normalizeChannel(channel);
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        RiderAppChannel pointer = channelDao.require(normalized);
        List<RiderAppReleaseDto> items = releaseDao.list(normalized, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(release -> toDto(release, pointer.currentReleaseId()))
                .toList();
        return new PageResult<>(items, releaseDao.count(normalized), currentPage, size);
    }

    public RiderAppCoverageDto coverage(String channel) {
        String normalized = normalizeChannel(channel);
        RiderAppChannel pointer = channelDao.require(normalized);
        RiderAppRelease current = pointer.currentReleaseId() == null
                ? null
                : releaseDao.findById(pointer.currentReleaseId()).orElse(null);
        int versionCode = current == null ? 0 : current.versionCode();
        LocalDateTime activeSince = LocalDateTime.now(clock).minusDays(7);
        RiderAppCoverageCounts counts = releaseDao.coverage(versionCode, activeSince);
        return new RiderAppCoverageDto(
                normalized,
                current == null ? null : current.versionCode(),
                current == null ? null : current.versionName(),
                counts.activeDevices(),
                counts.onLatest(),
                counts.behind(),
                counts.unknownVersion(),
                counts.deviceOwner(),
                counts.profileOwner(),
                counts.standard()
        );
    }

    public RiderAppReleaseDto createDraft(
            MultipartFile file,
            String channel,
            String title,
            String notes,
            String policy,
            String sourceSha,
            String operator
    ) {
        StoredApk stored = storeAndInspect(file, channel);
        try {
            return insertDraft(stored, title, notes, policy, sourceSha, operator);
        } catch (RuntimeException exception) {
            deleteQuietly(stored.path());
            throw exception;
        }
    }

    public RiderAppReleaseDto publishAutomated(
            MultipartFile file,
            String channel,
            String title,
            String notes,
            String policy,
            String sourceSha,
            String operator
    ) {
        StoredApk stored = storeAndInspect(file, channel);
        OptionalRelease existing = existingOf(stored.channel(), stored.inspection().versionCode());
        if (existing.release() != null) {
            RiderAppRelease previous = existing.release();
            if (!stored.sha256().equalsIgnoreCase(previous.fileSha256())) {
                deleteQuietly(stored.path());
                throw new DeliveryException(409, "同一 versionCode 已存在不同安装包");
            }
            deleteQuietly(stored.path());
            if (STATUS_DISABLED.equals(previous.status())) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_STATUS_NOT_ALLOWED, "该版本已停用，不能重复发布");
            }
            RiderAppReleaseDto published = STATUS_PUBLISHED.equals(previous.status())
                    ? toDto(previous, channelDao.require(stored.channel()).currentReleaseId())
                    : publish(previous.id(), operator);
            notifyRiders(published.id());
            return findDto(published.id());
        }
        try {
            RiderAppReleaseDto draft = insertDraft(stored, title, notes, policy, sourceSha, operator);
            RiderAppReleaseDto published = publish(draft.id(), operator);
            notifyRiders(published.id());
            return findDto(published.id());
        } catch (RuntimeException exception) {
            deleteQuietly(stored.path());
            throw exception;
        }
    }

    public RiderAppReleaseDto publish(long id, String operator) {
        RiderAppRelease release = require(id);
        if (STATUS_DISABLED.equals(release.status())) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_STATUS_NOT_ALLOWED, "已停用的版本不能发布");
        }
        if (STATUS_PUBLISHED.equals(release.status())) {
            return toDto(release, channelDao.require(release.channel()).currentReleaseId());
        }
        if (release.filePath() == null || release.filePath().isBlank() || !Files.isRegularFile(Path.of(release.filePath()))) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "该版本的安装包已不在服务器上");
        }
        RiderAppChannel pointer = channelDao.require(release.channel());
        RiderAppRelease current = pointer.currentReleaseId() == null
                ? null
                : releaseDao.findById(pointer.currentReleaseId()).orElse(null);
        if (current != null && release.versionCode() < current.versionCode()) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "不能把 versionCode 改低或发布更旧的包");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        releaseDao.updateStatus(id, STATUS_PUBLISHED, blankTo(operator, "system"), now, now);
        Integer minSupported = POLICY_FORCE.equals(normalizePolicy(release.policy()))
                ? release.versionCode()
                : null;
        channelDao.updatePointer(release.channel(), id, minSupported, now);
        pruneOldApks(release.channel(), id, now);
        return findDto(id);
    }

    public RiderAppReleaseDto disable(long id, String operator) {
        RiderAppRelease release = require(id);
        LocalDateTime now = LocalDateTime.now(clock);
        releaseDao.updateStatus(id, STATUS_DISABLED, blankTo(operator, "system"), null, now);
        RiderAppChannel pointer = channelDao.require(release.channel());
        if (Objects.equals(pointer.currentReleaseId(), id)) {
            RiderAppRelease fallback = releaseDao.list(release.channel(), 50, 0).stream()
                    .filter(item -> STATUS_PUBLISHED.equals(item.status()) && item.id() != id)
                    .findFirst()
                    .orElse(null);
            channelDao.updatePointer(release.channel(), fallback == null ? null : fallback.id(), null, now);
        }
        return findDto(id);
    }

    public RiderAppReleaseDto activate(long id, String operator) {
        RiderAppRelease release = require(id);
        if (!STATUS_PUBLISHED.equals(release.status())) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_STATUS_NOT_ALLOWED, "只能把已发布版本设为当前");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Integer minSupported = POLICY_FORCE.equals(normalizePolicy(release.policy()))
                ? release.versionCode()
                : null;
        channelDao.updatePointer(release.channel(), id, minSupported, now);
        return findDto(id);
    }

    public int notifyRiders(long id) {
        RiderAppRelease release = require(id);
        if (!STATUS_PUBLISHED.equals(release.status())) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_STATUS_NOT_ALLOWED, "只能推送已发布版本");
        }
        String title = release.title() == null || release.title().isBlank()
                ? "骑手端有新版本 " + release.versionName()
                : release.title();
        String content = release.notes() == null || release.notes().isBlank()
                ? "请更新到 " + release.versionName() + "（" + release.versionCode() + "）"
                : release.notes();
        String policyLabel = POLICY_FORCE.equals(release.policy()) ? "FORCE" : "OPTIONAL";
        List<Long> ids = messageService.broadcast(new BroadcastRequest(
                title,
                content,
                null,
                POLICY_FORCE.equals(release.policy()) ? MessageService.PRIORITY_HIGH : MessageService.PRIORITY_NORMAL,
                false,
                "APP_UPDATE",
                "APP",
                String.valueOf(release.versionCode())
        ));
        LocalDateTime now = LocalDateTime.now(clock);
        releaseDao.recordNotify(id, ids.size(), now);
        log.info("已向 {} 名骑手推送 APP_UPDATE {} ({})", ids.size(), release.versionCode(), policyLabel);
        return ids.size();
    }

    public RiderAppLatestDto latest(String channel, Integer clientVersionCode) {
        String normalized = normalizeChannel(channel);
        RiderAppChannel pointer = channelDao.require(normalized);
        if (pointer.currentReleaseId() == null) {
            return RiderAppLatestDto.none(normalized);
        }
        RiderAppRelease release = releaseDao.findById(pointer.currentReleaseId()).orElse(null);
        if (release == null || !STATUS_PUBLISHED.equals(release.status())) {
            return RiderAppLatestDto.none(normalized);
        }
        int client = clientVersionCode == null ? 0 : clientVersionCode;
        String policy = POLICY_NONE;
        if (client < release.versionCode()) {
            if (POLICY_FORCE.equals(release.policy()) || client < pointer.minSupportedVersionCode()) {
                policy = POLICY_FORCE;
            } else {
                policy = POLICY_OPTIONAL;
            }
        }
        return new RiderAppLatestDto(
                policy,
                normalized,
                release.versionCode(),
                release.versionName(),
                release.title(),
                release.notes(),
                publicUrl(release.fileUrl()),
                release.fileSize(),
                release.fileSha256(),
                release.packageName(),
                release.certSha256(),
                pointer.minSupportedVersionCode()
        );
    }

    public RiderAppReleaseDto findDto(long id) {
        RiderAppRelease release = require(id);
        return toDto(release, channelDao.require(release.channel()).currentReleaseId());
    }

    private RiderAppReleaseDto insertDraft(
            StoredApk stored,
            String title,
            String notes,
            String policy,
            String sourceSha,
            String operator
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        String versionName = stored.inspection().versionName();
        String resolvedTitle = title == null || title.isBlank()
                ? "骑手端 " + versionName
                : title.trim();
        RiderAppRelease release = new RiderAppRelease(
                null,
                stored.channel(),
                versionName,
                stored.inspection().versionCode(),
                resolvedTitle,
                notes == null || notes.isBlank() ? null : notes.trim(),
                normalizePolicy(policy),
                stored.inspection().packageName(),
                stored.fileName(),
                stored.path().toString(),
                stored.fileUrl(),
                stored.size(),
                stored.sha256(),
                stored.inspection().certSha256(),
                sourceSha == null || sourceSha.isBlank() ? null : sourceSha.trim(),
                STATUS_DRAFT,
                blankTo(operator, null),
                null,
                0,
                null,
                now,
                now
        );
        try {
            long id = releaseDao.insert(release, now);
            return findDto(id);
        } catch (DuplicateKeyException exception) {
            throw new DeliveryException(409, "同一 versionCode 已存在不同安装包");
        }
    }

    private StoredApk storeAndInspect(MultipartFile file, String channel) {
        if (file == null || file.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "请上传 APK 文件");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 不能超过 150 MB");
        }
        String originalName = file.getOriginalFilename() == null ? "rider.apk" : file.getOriginalFilename();
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "只接受 .apk 文件");
        }
        String normalizedChannel = normalizeChannel(channel);
        Path temp = null;
        try {
            Files.createDirectories(storageRoot);
            temp = Files.createTempFile(storageRoot, "upload-", ".apk");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream input = file.getInputStream();
                 DigestInputStream digested = new DigestInputStream(input, digest)) {
                size = Files.copy(digested, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (size > maxFileSizeBytes) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 不能超过 150 MB");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            ApkInspection inspection = apkInspector.inspect(temp);
            if (!expectedPackageName.equals(inspection.packageName())) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID,
                        "包名必须是 " + expectedPackageName);
            }
            if (!expectedCertSha256.isBlank() && !expectedCertSha256.equals(inspection.certSha256())) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 签名证书与发布证书不一致");
            }
            String fileName = inspection.versionCode() + "-" + sha256.substring(0, 16) + ".apk";
            Path targetDir = storageRoot.resolve(normalizedChannel);
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(fileName).normalize();
            if (!target.startsWith(storageRoot)) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 存储路径非法");
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            String fileUrl = URL_PREFIX + normalizedChannel + "/" + fileName;
            return new StoredApk(normalizedChannel, inspection, target, fileName, fileUrl, size, sha256);
        } catch (DeliveryException exception) {
            deleteQuietly(temp);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temp);
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 保存失败");
        }
    }

    private void pruneOldApks(String channel, long currentId, LocalDateTime now) {
        List<RiderAppRelease> withFiles = releaseDao.listWithFiles(channel);
        int kept = 0;
        for (RiderAppRelease release : withFiles) {
            if (release.id() == currentId || STATUS_DRAFT.equals(release.status())) {
                kept++;
                continue;
            }
            if (kept < retainApkCount) {
                kept++;
                continue;
            }
            deleteQuietly(release.filePath() == null ? null : Path.of(release.filePath()));
            releaseDao.clearFile(release.id(), now);
        }
    }

    private RiderAppRelease require(long id) {
        return releaseDao.findById(id)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.APP_RELEASE_NOT_FOUND));
    }

    private OptionalRelease existingOf(String channel, int versionCode) {
        return new OptionalRelease(releaseDao.findByChannelAndVersion(channel, versionCode).orElse(null));
    }

    private RiderAppReleaseDto toDto(RiderAppRelease release, Long currentId) {
        boolean hasFile = release.filePath() != null && !release.filePath().isBlank()
                && Files.isRegularFile(Path.of(release.filePath()));
        return new RiderAppReleaseDto(
                release.id(),
                release.channel(),
                release.versionName(),
                release.versionCode(),
                release.title(),
                release.notes(),
                release.policy(),
                release.packageName(),
                release.fileName(),
                publicUrl(release.fileUrl()),
                release.fileSize(),
                release.fileSha256(),
                release.certSha256(),
                release.sourceSha(),
                release.status(),
                release.publishedBy(),
                DeliveryTimes.format(release.publishedAt()),
                release.notifyCount(),
                DeliveryTimes.format(release.lastNotifiedAt()),
                DeliveryTimes.format(release.createdAt()),
                hasFile,
                Objects.equals(currentId, release.id())
        );
    }

    private String publicUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return fileUrl;
        }
        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) {
            return fileUrl;
        }
        if (publicBaseUrl.isBlank()) {
            return fileUrl;
        }
        return publicBaseUrl + fileUrl;
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return CHANNEL_PRODUCTION;
        }
        return channel.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePolicy(String policy) {
        if (policy != null && POLICY_FORCE.equalsIgnoreCase(policy.trim())) {
            return POLICY_FORCE;
        }
        return POLICY_OPTIONAL;
    }

    private static String normalizeSha(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Path resolvePath(String configured) {
        Path path = Path.of(configured == null || configured.isBlank() ? "../apk-releases" : configured);
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path))
                .toAbsolutePath()
                .normalize();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private record StoredApk(
            String channel,
            ApkInspection inspection,
            Path path,
            String fileName,
            String fileUrl,
            long size,
            String sha256
    ) {
    }

    private record OptionalRelease(RiderAppRelease release) {
    }
}
