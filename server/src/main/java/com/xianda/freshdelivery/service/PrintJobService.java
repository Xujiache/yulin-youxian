package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderItemDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintAgentHeartbeatRequest;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobResultRequest;
import com.xianda.freshdelivery.dto.PrintModels.PrintReceiptDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintReceiptItemDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterAccessKeyDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigUpdateRequest;
import com.xianda.freshdelivery.persistence.FileStateStore;
import com.xianda.freshdelivery.persistence.StateStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PrintJobService {
    private static final String STATE_KEY = "printing";
    private static final String PENDING = "PENDING";
    private static final String PRINTING = "PRINTING";
    private static final String RETRYING = "RETRYING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final int LEASE_SECONDS = 120;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final StateStore stateStore;
    private final Path storagePath;
    private final AtomicLong jobId = new AtomicLong(0);
    private final Map<Long, PrintJobState> jobs = new LinkedHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private PrintConfigState config = defaultConfig();

    @Autowired
    public PrintJobService(
            @Value("${printing.storage-path:data/printing-state.json}") String storagePath,
            StateStore stateStore
    ) {
        Path configuredPath = Path.of(storagePath);
        this.storagePath = configuredPath.isAbsolute()
                ? configuredPath
                : Path.of(System.getProperty("user.dir")).resolve(configuredPath);
        this.stateStore = stateStore;
        if (!loadState()) {
            persist();
        }
    }

    public PrintJobService(String storagePath) {
        this.storagePath = Path.of(storagePath).toAbsolutePath();
        this.stateStore = new FileStateStore();
        if (!loadState()) {
            persist();
        }
    }

    public synchronized PrinterConfigDto config() {
        recoverExpiredLeases();
        return toConfigDto();
    }

    public synchronized PrinterConfigDto updateConfig(PrinterConfigUpdateRequest request) {
        config = new PrintConfigState(
                Boolean.TRUE.equals(request.enabled()),
                Boolean.TRUE.equals(request.autoPrintOnPaid()),
                Math.max(1, Math.min(10, request.retryLimit() == null ? config.retryLimit() : request.retryLimit())),
                normalizeModel(request.printerModel()),
                config.accessKeyHash(),
                config.agentName(),
                config.agentConnection(),
                config.agentVersion(),
                config.agentLastSeen()
        );
        persist();
        return toConfigDto();
    }

    public synchronized PrinterAccessKeyDto regenerateAccessKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String accessKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        config = config.withAccessKeyHash(sha256(accessKey));
        persist();
        return new PrinterAccessKeyDto(accessKey);
    }

    public synchronized PrintJobDto enqueuePaidOrder(OrderDetailDto order) {
        if (order == null || !config.enabled() || !config.autoPrintOnPaid()) {
            return null;
        }
        String sourceKey = "ORDER:" + order.id();
        PrintJobState existing = jobs.values().stream()
                .filter(job -> sourceKey.equals(job.sourceKey()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return toDto(existing);
        }
        PrintJobState created = new PrintJobState(
                jobId.incrementAndGet(),
                order.id(),
                sourceKey,
                "ORDER",
                order.orderNo(),
                PENDING,
                0,
                now(),
                now(),
                null,
                null,
                null,
                null,
                receiptForOrder(order)
        );
        jobs.put(created.id(), created);
        persist();
        return toDto(created);
    }

    public synchronized PrintJobDto enqueueTest(String storeName) {
        if (!config.enabled()) {
            throw new BusinessException(409, "请先启用小票打印");
        }
        String createdAt = now();
        PrintReceiptDto receipt = new PrintReceiptDto(
                hasText(storeName) ? storeName.trim() : "禹邻优鲜",
                "测试打印",
                "TEST-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                createdAt,
                "测试时段",
                "门店测试",
                "",
                "打印机连接测试",
                List.of(new PrintReceiptItemDto("测试商品", "1份", "¥ 0.01", "¥ 0.01")),
                "¥ 0.01",
                "¥ 0.00",
                "¥ 0.00",
                "¥ 0.01",
                "如能看到此小票，说明自动打印已就绪。"
        );
        PrintJobState created = new PrintJobState(
                jobId.incrementAndGet(),
                null,
                "TEST:" + System.nanoTime(),
                "TEST",
                receipt.orderNo(),
                PENDING,
                0,
                createdAt,
                createdAt,
                null,
                null,
                null,
                null,
                receipt
        );
        jobs.put(created.id(), created);
        persist();
        return toDto(created);
    }

    public synchronized List<PrintJobDto> jobs() {
        recoverExpiredLeases();
        return jobs.values().stream()
                .sorted(Comparator.comparing(PrintJobState::id).reversed())
                .limit(200)
                .map(this::toDto)
                .toList();
    }

    public synchronized PrintJobDto retry(Long id) {
        PrintJobState current = job(id);
        PrintJobState next = current.withRetry(now());
        jobs.put(id, next);
        persist();
        return toDto(next);
    }

    public synchronized void requireAgentKey(String accessKey) {
        if (!hasText(config.accessKeyHash()) || !hasText(accessKey)) {
            throw new BusinessException(401, "打印代理认证失败");
        }
        byte[] actual = sha256(accessKey).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = config.accessKeyHash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(401, "打印代理认证失败");
        }
    }

    public synchronized PrinterConfigDto heartbeat(PrintAgentHeartbeatRequest request) {
        config = new PrintConfigState(
                config.enabled(),
                config.autoPrintOnPaid(),
                config.retryLimit(),
                config.printerModel(),
                config.accessKeyHash(),
                clean(request.agentName(), "Windows 小票打印代理"),
                clean(request.connection(), ""),
                clean(request.version(), ""),
                now()
        );
        persist();
        return toConfigDto();
    }

    public synchronized PrintJobDto claimNext(String agentName, String connection, String version) {
        heartbeat(new PrintAgentHeartbeatRequest(agentName, connection, version));
        recoverExpiredLeases();
        if (!config.enabled()) {
            return null;
        }
        String current = now();
        PrintJobState available = jobs.values().stream()
                .filter(job -> PENDING.equals(job.status()) || RETRYING.equals(job.status()))
                .filter(job -> job.nextAttemptAt() == null || job.nextAttemptAt().compareTo(current) <= 0)
                .sorted(Comparator.comparing(PrintJobState::id))
                .findFirst()
                .orElse(null);
        if (available == null) {
            return null;
        }
        String leaseToken = randomToken();
        PrintJobState claimed = available.withLease(
                available.attemptCount() + 1,
                LocalDateTime.now().plusSeconds(LEASE_SECONDS).format(TIME_FORMATTER),
                leaseToken
        );
        jobs.put(claimed.id(), claimed);
        persist();
        return toAgentDto(claimed);
    }

    public synchronized PrintJobDto complete(Long id, PrintJobResultRequest request) {
        PrintJobState current = job(id);
        if (!PRINTING.equals(current.status()) || !hasText(current.leaseToken()) || !current.leaseToken().equals(request.leaseToken())) {
            throw new BusinessException(409, "打印任务租约已失效，请重新获取任务");
        }
        String currentTime = now();
        PrintJobState next;
        if (Boolean.TRUE.equals(request.success())) {
            next = current.withSuccess(currentTime);
        } else if (current.attemptCount() >= config.retryLimit()) {
            next = current.withFailure(clean(request.message(), "打印失败"));
        } else {
            int delaySeconds = Math.min(120, Math.max(5, current.attemptCount() * 10));
            String nextAttemptAt = LocalDateTime.now().plusSeconds(delaySeconds).format(TIME_FORMATTER);
            next = current.withRetryAfter(nextAttemptAt, clean(request.message(), "打印失败，等待重试"));
        }
        jobs.put(id, next);
        persist();
        return toDto(next);
    }

    private boolean loadState() {
        byte[] payload = stateStore.load(STATE_KEY, storagePath).orElse(null);
        if (payload == null) {
            return false;
        }
        try {
            PrintSnapshot snapshot = objectMapper.readValue(payload, PrintSnapshot.class);
            config = configOrDefault(snapshot.config());
            jobs.clear();
            for (PrintJobState job : snapshot.jobs() == null ? List.<PrintJobState>of() : snapshot.jobs()) {
                if (job != null && job.id() != null) {
                    jobs.put(job.id(), normalizeJob(job));
                }
            }
            jobId.set(jobs.keySet().stream().mapToLong(Long::longValue).max().orElse(0L));
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("读取打印任务数据失败", exception);
        }
    }

    private void persist() {
        try {
            stateStore.save(STATE_KEY, storagePath, objectMapper.writeValueAsBytes(new PrintSnapshot(config, new ArrayList<>(jobs.values()))));
        } catch (IOException exception) {
            throw new IllegalStateException("写入打印任务数据失败", exception);
        }
    }

    private void recoverExpiredLeases() {
        String current = now();
        boolean changed = false;
        for (PrintJobState job : new ArrayList<>(jobs.values())) {
            if (PRINTING.equals(job.status()) && hasText(job.leaseUntil()) && job.leaseUntil().compareTo(current) < 0) {
                PrintJobState recovered = job.attemptCount() >= config.retryLimit()
                        ? job.withFailure("打印代理离线，任务超过重试次数")
                        : job.withRetryAfter(current, "打印代理离线，已重新排队");
                jobs.put(job.id(), recovered);
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private PrintJobState job(Long id) {
        PrintJobState job = jobs.get(id);
        if (job == null) {
            throw new BusinessException(404, "打印任务不存在");
        }
        return job;
    }

    private PrintReceiptDto receiptForOrder(OrderDetailDto order) {
        List<PrintReceiptItemDto> items = (order.items() == null ? List.<OrderItemDto>of() : order.items()).stream()
                .map(item -> new PrintReceiptItemDto(
                        clean(item.productName(), "商品"),
                        clean(formatQuantity(item.quantity(), item.saleUnit()), ""),
                        yuan(item.unitPrice()),
                        yuan(item.amount())
                ))
                .toList();
        String address = order.address() == null
                ? ""
                : clean(order.address().locationName(), "") + clean(order.address().detail(), "");
        return new PrintReceiptDto(
                "禹邻优鲜",
                "订单小票",
                order.orderNo(),
                clean(order.createdAt(), now()),
                clean(order.deliverySlot(), "尽快送达"),
                order.address() == null ? "收货人" : clean(order.address().name(), "收货人"),
                order.address() == null ? "" : clean(order.address().phone(), ""),
                address,
                items,
                yuan(order.productAmount()),
                yuan(order.deliveryFee()),
                yuan(order.packageFee()),
                yuan(order.payableAmount()),
                clean(order.remark(), "无")
        );
    }

    private PrintJobDto toDto(PrintJobState job) {
        return toDto(job, false);
    }

    private PrintJobDto toAgentDto(PrintJobState job) {
        return toDto(job, true);
    }

    private PrintJobDto toDto(PrintJobState job, boolean includeLeaseToken) {
        return new PrintJobDto(
                job.id(),
                job.orderId(),
                job.type(),
                job.orderNo(),
                job.status(),
                job.attemptCount(),
                config.retryLimit(),
                job.createdAt(),
                job.nextAttemptAt(),
                job.printedAt(),
                job.lastError(),
                includeLeaseToken ? job.leaseToken() : null,
                job.receipt()
        );
    }

    private PrinterConfigDto toConfigDto() {
        int pending = (int) jobs.values().stream().filter(job -> PENDING.equals(job.status()) || RETRYING.equals(job.status()) || PRINTING.equals(job.status())).count();
        int failed = (int) jobs.values().stream().filter(job -> FAILED.equals(job.status())).count();
        return new PrinterConfigDto(
                config.enabled(),
                config.autoPrintOnPaid(),
                config.retryLimit(),
                config.printerModel(),
                hasText(config.accessKeyHash()),
                isAgentOnline(),
                config.agentName(),
                config.agentConnection(),
                config.agentVersion(),
                config.agentLastSeen(),
                pending,
                failed
        );
    }

    private boolean isAgentOnline() {
        if (!hasText(config.agentLastSeen())) {
            return false;
        }
        try {
            return LocalDateTime.parse(config.agentLastSeen()).isAfter(LocalDateTime.now().minusSeconds(90));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PrintConfigState configOrDefault(PrintConfigState source) {
        if (source == null) {
            return defaultConfig();
        }
        return new PrintConfigState(
                source.enabled(),
                source.autoPrintOnPaid(),
                source.retryLimit() < 1 ? 3 : Math.min(source.retryLimit(), 10),
                normalizeModel(source.printerModel()),
                source.accessKeyHash(),
                clean(source.agentName(), ""),
                clean(source.agentConnection(), ""),
                clean(source.agentVersion(), ""),
                clean(source.agentLastSeen(), "")
        );
    }

    private PrintJobState normalizeJob(PrintJobState source) {
        return new PrintJobState(
                source.id(),
                source.orderId(),
                clean(source.sourceKey(), "LEGACY:" + source.id()),
                clean(source.type(), "ORDER"),
                clean(source.orderNo(), ""),
                clean(source.status(), PENDING),
                Math.max(source.attemptCount(), 0),
                clean(source.createdAt(), now()),
                clean(source.nextAttemptAt(), now()),
                source.leaseUntil(),
                source.leaseToken(),
                source.printedAt(),
                source.lastError(),
                source.receipt()
        );
    }

    private static PrintConfigState defaultConfig() {
        return new PrintConfigState(false, true, 3, "XP-58III NT", "", "", "", "", "");
    }

    private String normalizeModel(String value) {
        return clean(value, "XP-58III NT");
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成打印代理密钥失败", exception);
        }
    }

    private String formatQuantity(BigDecimal quantity, String unit) {
        if (quantity == null) {
            return "";
        }
        String normalized = quantity.stripTrailingZeros().toPlainString();
        return normalized + clean(unit, "");
    }

    private String yuan(Integer amount) {
        int value = amount == null ? 0 : amount;
        return "¥ " + BigDecimal.valueOf(value, 2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    private static String clean(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PrintSnapshot(PrintConfigState config, List<PrintJobState> jobs) {
    }

    private record PrintConfigState(
            boolean enabled,
            boolean autoPrintOnPaid,
            int retryLimit,
            String printerModel,
            String accessKeyHash,
            String agentName,
            String agentConnection,
            String agentVersion,
            String agentLastSeen
    ) {
        PrintConfigState withAccessKeyHash(String value) {
            return new PrintConfigState(enabled, autoPrintOnPaid, retryLimit, printerModel, value, agentName, agentConnection, agentVersion, agentLastSeen);
        }
    }

    private record PrintJobState(
            Long id,
            Long orderId,
            String sourceKey,
            String type,
            String orderNo,
            String status,
            int attemptCount,
            String createdAt,
            String nextAttemptAt,
            String leaseUntil,
            String leaseToken,
            String printedAt,
            String lastError,
            PrintReceiptDto receipt
    ) {
        PrintJobState withLease(int nextAttemptCount, String nextLeaseUntil, String nextLeaseToken) {
            return new PrintJobState(id, orderId, sourceKey, type, orderNo, PRINTING, nextAttemptCount, createdAt, nextAttemptAt, nextLeaseUntil, nextLeaseToken, printedAt, lastError, receipt);
        }

        PrintJobState withSuccess(String time) {
            return new PrintJobState(id, orderId, sourceKey, type, orderNo, SUCCESS, attemptCount, createdAt, nextAttemptAt, null, null, time, null, receipt);
        }

        PrintJobState withFailure(String message) {
            return new PrintJobState(id, orderId, sourceKey, type, orderNo, FAILED, attemptCount, createdAt, nextAttemptAt, null, null, null, message, receipt);
        }

        PrintJobState withRetryAfter(String time, String message) {
            return new PrintJobState(id, orderId, sourceKey, type, orderNo, RETRYING, attemptCount, createdAt, time, null, null, null, message, receipt);
        }

        PrintJobState withRetry(String time) {
            return new PrintJobState(id, orderId, sourceKey, type, orderNo, PENDING, 0, createdAt, time, null, null, null, null, receipt);
        }
    }
}
