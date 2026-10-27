package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import com.xianda.freshdelivery.delivery.common.RiderAccountStatus;
import com.xianda.freshdelivery.delivery.common.RiderRole;
import com.xianda.freshdelivery.delivery.common.RiderWorkStatus;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import com.xianda.freshdelivery.delivery.domain.Rider;
import com.xianda.freshdelivery.delivery.dto.LocationConsentRequest;
import com.xianda.freshdelivery.delivery.dto.RiderAdminDto;
import com.xianda.freshdelivery.delivery.dto.RiderCreateRequest;
import com.xianda.freshdelivery.delivery.dto.RiderProfileDto;
import com.xianda.freshdelivery.delivery.dto.RiderUpdateRequest;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderSessionDao;
import com.xianda.freshdelivery.delivery.repository.RiderShiftDao;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RiderAccountService {
    public static final int HEALTH_CERT_WARNING_DAYS = 30;
    private static final String RIDER_NO_PREFIX = "QS";
    private static final int RIDER_NO_DIGITS = 6;
    private static final BigDecimal DEFAULT_CAPACITY_WEIGHT_KG = new BigDecimal("30.000");

    private final RiderDao riderDao;
    private final RiderShiftDao riderShiftDao;
    private final RiderSessionDao riderSessionDao;
    private final RiderShiftService riderShiftService;
    private final PasswordHasher passwordHasher;
    private final String avatarStoragePath;
    private final Clock clock;

    @Autowired
    public RiderAccountService(
            RiderDao riderDao,
            RiderShiftDao riderShiftDao,
            RiderSessionDao riderSessionDao,
            RiderShiftService riderShiftService,
            PasswordHasher passwordHasher,
            @Value("${delivery.upload.delivery-path:data/uploads/delivery}") String deliveryUploadPath
    ) {
        this(riderDao, riderShiftDao, riderSessionDao, riderShiftService, passwordHasher, deliveryUploadPath,
                Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderAccountService(
            RiderDao riderDao,
            RiderShiftDao riderShiftDao,
            RiderSessionDao riderSessionDao,
            RiderShiftService riderShiftService,
            PasswordHasher passwordHasher,
            String deliveryUploadPath,
            Clock clock
    ) {
        this.riderDao = riderDao;
        this.riderShiftDao = riderShiftDao;
        this.riderSessionDao = riderSessionDao;
        this.riderShiftService = riderShiftService;
        this.passwordHasher = passwordHasher;
        this.avatarStoragePath = deliveryUploadPath;
        this.clock = clock;
    }

    public boolean isLocationConsentGranted(long riderId) {
        return riderDao.isLocationConsentGranted(riderId);
    }

    public Rider requireRider(long riderId) {
        return riderDao.findById(riderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED, "骑手不存在"));
    }

    public Rider requireActiveRider(long riderId) {
        Rider rider = requireRider(riderId);
        if (!RiderAccountStatus.ACTIVE.name().equals(rider.accountStatus())) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_SUSPENDED);
        }
        return rider;
    }

    public RiderProfileDto profile(long riderId) {
        return toProfileDto(requireRider(riderId));
    }

    public RiderProfileDto updateSelfProfile(long riderId, RiderUpdateRequest request) {
        Rider rider = requireRider(riderId);
        Rider updated = new Rider(
                rider.id(), rider.riderNo(), rider.name(), rider.phone(), rider.passwordHash(),
                rider.mustChangePassword(),
                text(request.avatarUrl(), rider.avatarUrl()),
                rider.idCardMasked(), rider.role(), rider.accountStatus(), rider.workStatus(),
                rider.healthCertNo(), rider.healthCertExpireAt(),
                text(request.vehiclePlate(), rider.vehiclePlate()),
                rider.vehicleType(), rider.maxConcurrentTask(), rider.capacityWeightKg(),
                rider.insulatedBoxCount(), rider.probation(), rider.hiredAt(), rider.serviceScore(),
                rider.levelCode(), rider.totalTaskCount(), rider.onTimeTaskCount(),
                rider.locationConsentAt(), rider.locationConsentVersion(), rider.remark(),
                rider.createdAt(), rider.updatedAt(), rider.deletedAt()
        );
        riderDao.update(updated);
        return toProfileDto(updated);
    }

    public String saveAvatar(long riderId, MultipartFile file) throws IOException {
        requireRider(riderId);
        if (file == null || file.isEmpty()) {
            throw new DeliveryException(400, "头像文件不能为空");
        }
        Path directory = Path.of(avatarStoragePath, "avatars");
        Files.createDirectories(directory);
        String fileName = "r" + riderId + "_" + UUID.randomUUID().toString().replace("-", "") + extension(file.getContentType());
        Path target = directory.resolve(fileName);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String avatarUrl = "/uploads/delivery/avatars/" + fileName;
        riderDao.updateAvatar(riderId, avatarUrl);
        return avatarUrl;
    }

    public RiderProfileDto updateLocationConsent(long riderId, LocationConsentRequest request) {
        Rider rider = requireRider(riderId);
        boolean agreed = request != null && Boolean.TRUE.equals(request.agreed());
        LocalDateTime agreedAt = null;
        String version = null;
        if (agreed) {
            agreedAt = request.agreedAt() == null ? now() : DeliveryTimes.parseDateTime(request.agreedAt());
            if (agreedAt == null) {
                agreedAt = now();
            }
            version = request.consentVersion() == null || request.consentVersion().isBlank()
                    ? "v1.0"
                    : request.consentVersion().trim();
        }
        riderDao.updateLocationConsent(rider.id(), agreedAt, version);
        return toProfileDto(requireRider(riderId));
    }

    public void changePassword(long riderId, String oldPassword, String newPassword) {
        Rider rider = requireRider(riderId);
        if (!passwordHasher.matches(oldPassword, rider.passwordHash())) {
            throw new DeliveryException(400, "原密码不正确");
        }
        RiderPasswords.validate(newPassword);
        if (passwordHasher.matches(newPassword, rider.passwordHash())) {
            throw new DeliveryException(400, "新密码不能与原密码相同");
        }
        riderDao.updatePassword(riderId, passwordHasher.hash(newPassword), false);
    }

    public PageResult<RiderAdminDto> search(String accountStatus, String keyword, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int currentSize = SqlPaging.pageSize(pageSize);
        List<RiderAdminDto> items = riderDao.search(accountStatus, keyword, currentPage, currentSize).stream()
                .map(rider -> toAdminDto(rider, null))
                .toList();
        long total = riderDao.count(accountStatus, keyword);
        return new PageResult<>(items, total, currentPage, currentSize);
    }

    public RiderAdminDto detail(long riderId) {
        Rider rider = requireRider(riderId);
        return toAdminDto(rider, null);
    }

    public RiderAdminDto create(RiderCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new DeliveryException(400, "骑手姓名不能为空");
        }
        String phone = request.phone() == null ? "" : request.phone().trim();
        if (!phone.matches("\\d{11}")) {
            throw new DeliveryException(400, "手机号格式不正确");
        }
        if (riderDao.existsByPhone(phone)) {
            throw new DeliveryException(400, "手机号已被占用");
        }
        String initialPassword = RiderPasswords.generate();
        Rider draft = new Rider(
                null,
                nextRiderNo(),
                request.name().trim(),
                phone,
                passwordHasher.hash(initialPassword),
                true,
                null,
                request.idCardMasked(),
                enumOrDefault(request.role(), RiderRole.RIDER.name()),
                RiderAccountStatus.ACTIVE.name(),
                RiderWorkStatus.OFF_DUTY.name(),
                request.healthCertNo(),
                DeliveryTimes.parseDate(request.healthCertExpireAt()),
                request.vehiclePlate(),
                enumOrDefault(request.vehicleType(), TravelMode.EBIKE.name()),
                request.maxConcurrentTask() == null ? 8 : request.maxConcurrentTask(),
                request.capacityWeightKg() == null
                        ? DEFAULT_CAPACITY_WEIGHT_KG
                        : BigDecimal.valueOf(request.capacityWeightKg()).setScale(3, RoundingMode.HALF_UP),
                request.insulatedBoxCount() == null ? 1 : request.insulatedBoxCount(),
                true,
                DeliveryTimes.parseDate(request.hiredAt()),
                100,
                "L1",
                0,
                0,
                null,
                null,
                request.remark(),
                null,
                null,
                null
        );
        long riderId;
        try {
            riderId = riderDao.insert(draft);
        } catch (DuplicateKeyException exception) {
            riderId = riderDao.insert(new Rider(
                    null, nextRiderNo(), draft.name(), draft.phone(), draft.passwordHash(), draft.mustChangePassword(),
                    draft.avatarUrl(), draft.idCardMasked(), draft.role(), draft.accountStatus(), draft.workStatus(),
                    draft.healthCertNo(), draft.healthCertExpireAt(), draft.vehiclePlate(), draft.vehicleType(),
                    draft.maxConcurrentTask(), draft.capacityWeightKg(), draft.insulatedBoxCount(), draft.probation(),
                    draft.hiredAt(), draft.serviceScore(), draft.levelCode(), draft.totalTaskCount(),
                    draft.onTimeTaskCount(), draft.locationConsentAt(), draft.locationConsentVersion(), draft.remark(),
                    null, null, null
            ));
        }
        return toAdminDto(requireRider(riderId), initialPassword);
    }

    public RiderAdminDto update(long riderId, RiderUpdateRequest request) {
        Rider rider = requireRider(riderId);
        String phone = request.phone() == null || request.phone().isBlank() ? rider.phone() : request.phone().trim();
        if (!phone.equals(rider.phone())) {
            if (!phone.matches("\\d{11}")) {
                throw new DeliveryException(400, "手机号格式不正确");
            }
            if (riderDao.existsByPhoneExcluding(phone, riderId)) {
                throw new DeliveryException(400, "手机号已被占用");
            }
        }
        Rider updated = new Rider(
                rider.id(),
                rider.riderNo(),
                text(request.name(), rider.name()),
                phone,
                rider.passwordHash(),
                rider.mustChangePassword(),
                text(request.avatarUrl(), rider.avatarUrl()),
                text(request.idCardMasked(), rider.idCardMasked()),
                text(request.role(), rider.role()),
                rider.accountStatus(),
                rider.workStatus(),
                text(request.healthCertNo(), rider.healthCertNo()),
                request.healthCertExpireAt() == null || request.healthCertExpireAt().isBlank()
                        ? rider.healthCertExpireAt()
                        : DeliveryTimes.parseDate(request.healthCertExpireAt()),
                text(request.vehiclePlate(), rider.vehiclePlate()),
                text(request.vehicleType(), rider.vehicleType()),
                request.maxConcurrentTask() == null ? rider.maxConcurrentTask() : request.maxConcurrentTask(),
                request.capacityWeightKg() == null
                        ? rider.capacityWeightKg()
                        : BigDecimal.valueOf(request.capacityWeightKg()).setScale(3, RoundingMode.HALF_UP),
                request.insulatedBoxCount() == null ? rider.insulatedBoxCount() : request.insulatedBoxCount(),
                request.probation() == null ? rider.probation() : request.probation(),
                request.hiredAt() == null || request.hiredAt().isBlank()
                        ? rider.hiredAt()
                        : DeliveryTimes.parseDate(request.hiredAt()),
                rider.serviceScore(),
                text(request.levelCode(), rider.levelCode()),
                rider.totalTaskCount(),
                rider.onTimeTaskCount(),
                rider.locationConsentAt(),
                rider.locationConsentVersion(),
                text(request.remark(), rider.remark()),
                rider.createdAt(),
                rider.updatedAt(),
                rider.deletedAt()
        );
        riderDao.update(updated);
        return toAdminDto(requireRider(riderId), null);
    }

    public String resetPassword(long riderId) {
        requireRider(riderId);
        String password = RiderPasswords.generate();
        riderDao.updatePassword(riderId, passwordHasher.hash(password), true);
        riderSessionDao.revokeAllForRider(riderId, now(), "PASSWORD_RESET");
        return password;
    }

    public RiderAdminDto suspend(long riderId, String reason) {
        Rider rider = requireRider(riderId);
        riderDao.updateAccountStatus(rider.id(), RiderAccountStatus.SUSPENDED.name());
        riderShiftService.forceOffDuty(rider.id(), "ADMIN");
        riderSessionDao.revokeAllForRider(rider.id(), now(), reason == null || reason.isBlank() ? "SUSPENDED" : reason);
        return toAdminDto(requireRider(riderId), null);
    }

    public RiderAdminDto activate(long riderId) {
        Rider rider = requireRider(riderId);
        riderDao.updateAccountStatus(rider.id(), RiderAccountStatus.ACTIVE.name());
        return toAdminDto(requireRider(riderId), null);
    }

    public RiderAdminDto forceOffDuty(long riderId, String reason) {
        Rider rider = requireRider(riderId);
        riderShiftService.forceOffDuty(rider.id(), "ADMIN");
        riderSessionDao.revokeAllForRider(rider.id(), now(), reason == null || reason.isBlank() ? "FORCE_OFF_DUTY" : reason);
        return toAdminDto(requireRider(riderId), null);
    }

    public Long healthCertRemainingDays(long riderId) {
        return healthCertRemainingDays(requireRider(riderId).healthCertExpireAt(), today());
    }

    public static Long healthCertRemainingDays(LocalDate expireAt, LocalDate today) {
        if (expireAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(today, expireAt);
    }

    public RiderProfileDto toProfileDto(Rider rider) {
        Long remainingDays = healthCertRemainingDays(rider.healthCertExpireAt(), today());
        return new RiderProfileDto(
                rider.id(),
                rider.riderNo(),
                rider.name(),
                rider.phone(),
                rider.avatarUrl(),
                rider.role(),
                rider.accountStatus(),
                rider.workStatus(),
                rider.vehicleType(),
                rider.vehiclePlate(),
                rider.maxConcurrentTask(),
                rider.capacityWeightKg() == null ? null : rider.capacityWeightKg().doubleValue(),
                rider.probation(),
                rider.serviceScore(),
                rider.levelCode(),
                rider.totalTaskCount(),
                onTimeRate(rider.totalTaskCount(), rider.onTimeTaskCount()),
                DeliveryTimes.format(rider.healthCertExpireAt()),
                remainingDays != null && remainingDays <= HEALTH_CERT_WARNING_DAYS,
                DeliveryTimes.format(rider.locationConsentAt())
        );
    }

    public RiderAdminDto toAdminDto(Rider rider, String initialPassword) {
        LocalDate today = today();
        RiderShiftDao.ShiftAggregate todayStat = riderShiftDao.aggregate(rider.id(), today, today);
        RiderShiftDao.ShiftAggregate weekStat = riderShiftDao.aggregate(rider.id(), today.minusDays(6), today);
        return new RiderAdminDto(
                rider.id(),
                rider.riderNo(),
                rider.name(),
                rider.phone(),
                rider.avatarUrl(),
                rider.idCardMasked(),
                rider.role(),
                rider.accountStatus(),
                rider.workStatus(),
                rider.healthCertNo(),
                DeliveryTimes.format(rider.healthCertExpireAt()),
                rider.vehiclePlate(),
                rider.vehicleType(),
                rider.maxConcurrentTask(),
                rider.capacityWeightKg() == null ? null : rider.capacityWeightKg().doubleValue(),
                rider.insulatedBoxCount(),
                rider.probation(),
                DeliveryTimes.format(rider.hiredAt()),
                rider.serviceScore(),
                rider.levelCode(),
                rider.totalTaskCount(),
                rider.onTimeTaskCount(),
                onTimeRate(rider.totalTaskCount(), rider.onTimeTaskCount()),
                DeliveryTimes.format(rider.locationConsentAt()),
                rider.remark(),
                DeliveryTimes.format(rider.createdAt()),
                todayStat.deliveredCount(),
                onTimeRate(todayStat.deliveredCount(), todayStat.onTimeCount()),
                weekStat.deliveredCount(),
                onTimeRate(weekStat.deliveredCount(), weekStat.onTimeCount()),
                initialPassword
        );
    }

    private String nextRiderNo() {
        long next = riderDao.findMaxRiderNo()
                .map(value -> value.replace(RIDER_NO_PREFIX, ""))
                .filter(value -> value.matches("\\d+"))
                .map(Long::parseLong)
                .orElse(0L) + 1;
        return RIDER_NO_PREFIX + String.format(Locale.ROOT, "%0" + RIDER_NO_DIGITS + "d", next);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Double onTimeRate(Integer total, Integer onTime) {
        if (total == null || total <= 0 || onTime == null) {
            return null;
        }
        return BigDecimal.valueOf(onTime)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String enumOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String extension(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
