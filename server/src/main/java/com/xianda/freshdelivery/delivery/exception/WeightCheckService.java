package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryWeightCheck;
import com.xianda.freshdelivery.delivery.dto.WeightCheckDto;
import com.xianda.freshdelivery.delivery.dto.WeightCheckJudgeRequest;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WeightCheckService {
    public static final String VERDICT_PASS = "PASS";
    public static final String VERDICT_AUTO_REFUND = "AUTO_REFUND";
    public static final String VERDICT_MANUAL_REVIEW = "MANUAL_REVIEW";
    public static final String VERDICT_REJECTED = "REJECTED";

    public static final String KEY_TOLERANCE_PERCENT = "weight.tolerance_percent";
    public static final String KEY_AUTO_REFUND_PERCENT = "weight.auto_refund_threshold_percent";

    private static final Set<String> VERDICTS =
            Set.of(VERDICT_PASS, VERDICT_AUTO_REFUND, VERDICT_MANUAL_REVIEW, VERDICT_REJECTED);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ExceptionWeightCheckDao weightCheckDao;
    private final DeliveryConfigService configService;
    private final Clock clock;

    @Autowired
    public WeightCheckService(ExceptionWeightCheckDao weightCheckDao, DeliveryConfigService configService) {
        this(weightCheckDao, configService, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public WeightCheckService(ExceptionWeightCheckDao weightCheckDao, DeliveryConfigService configService, Clock clock) {
        this.weightCheckDao = weightCheckDao;
        this.configService = configService;
        this.clock = clock;
    }

    public WeightCheckDto submit(Long orderId, Long taskId, Long orderItemId, String productName,
                                 BigDecimal orderedQty, BigDecimal pickedWeightKg, BigDecimal customerWeightKg,
                                 Integer unitPricePerKg, Long scaleEvidenceId, Long customerEvidenceId) {
        if (orderId == null) {
            throw new DeliveryException(400, "公平秤复核必须关联订单");
        }
        if (pickedWeightKg == null || pickedWeightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DeliveryException(400, "门店拣货重量必须大于 0");
        }
        if (customerWeightKg == null || customerWeightKg.compareTo(BigDecimal.ZERO) < 0) {
            throw new DeliveryException(400, "顾客复称重量不能为空");
        }
        BigDecimal tolerance = configService.getDecimal(KEY_TOLERANCE_PERCENT);
        BigDecimal autoRefundThreshold = configService.getDecimal(KEY_AUTO_REFUND_PERCENT);
        BigDecimal diffPercent = diffPercent(pickedWeightKg, customerWeightKg);
        String verdict = judgeVerdict(diffPercent, tolerance, autoRefundThreshold);
        int refundAmount = VERDICT_AUTO_REFUND.equals(verdict)
                ? refundAmount(pickedWeightKg, customerWeightKg, unitPricePerKg)
                : 0;
        LocalDateTime now = LocalDateTime.now(clock);
        DeliveryWeightCheck draft = new DeliveryWeightCheck(
                null, orderId, taskId, orderItemId,
                productName == null || productName.isBlank() ? "未命名商品" : productName.trim(),
                orderedQty == null ? BigDecimal.ONE : orderedQty,
                pickedWeightKg, customerWeightKg, tolerance, diffPercent,
                scaleEvidenceId, customerEvidenceId, verdict, refundAmount,
                null, null, now, now);
        long id = weightCheckDao.insert(draft, now);
        return toDto(weightCheckDao.findById(id).orElse(draft));
    }

    public WeightCheckDto judge(long id, WeightCheckJudgeRequest request, String operator) {
        if (request == null || request.verdict() == null || request.verdict().isBlank()) {
            throw new DeliveryException(400, "复核结论不能为空");
        }
        String verdict = request.verdict().trim().toUpperCase(Locale.ROOT);
        if (!VERDICTS.contains(verdict)) {
            throw new DeliveryException(400, "不支持的复核结论：" + request.verdict());
        }
        DeliveryWeightCheck check = weightCheckDao.findById(id)
                .orElseThrow(() -> new DeliveryException(404, "公平秤记录不存在：" + id));
        int refundAmount = request.refundAmount() == null
                ? (check.refundAmount() == null ? 0 : check.refundAmount())
                : Math.max(0, request.refundAmount());
        weightCheckDao.updateVerdict(id, verdict, refundAmount,
                operator == null || operator.isBlank() ? "系统" : operator.trim(), LocalDateTime.now(clock));
        return toDto(weightCheckDao.findById(id).orElse(check));
    }

    public PageResult<WeightCheckDto> search(String verdict, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<WeightCheckDto> items = weightCheckDao.search(verdict, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(WeightCheckService::toDto)
                .toList();
        return new PageResult<>(items, weightCheckDao.countSearch(verdict), currentPage, size);
    }

    static BigDecimal diffPercent(BigDecimal pickedWeightKg, BigDecimal customerWeightKg) {
        return pickedWeightKg.subtract(customerWeightKg)
                .abs()
                .multiply(HUNDRED)
                .divide(pickedWeightKg, 2, RoundingMode.HALF_UP);
    }

    static String judgeVerdict(BigDecimal diffPercent, BigDecimal tolerance, BigDecimal autoRefundThreshold) {
        if (diffPercent.compareTo(tolerance) <= 0) {
            return VERDICT_PASS;
        }
        if (diffPercent.compareTo(autoRefundThreshold) >= 0) {
            return VERDICT_AUTO_REFUND;
        }
        return VERDICT_MANUAL_REVIEW;
    }

    static int refundAmount(BigDecimal pickedWeightKg, BigDecimal customerWeightKg, Integer unitPricePerKg) {
        if (unitPricePerKg == null || unitPricePerKg <= 0) {
            return 0;
        }
        BigDecimal shortfall = pickedWeightKg.subtract(customerWeightKg);
        if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return shortfall.multiply(BigDecimal.valueOf(unitPricePerKg))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    static WeightCheckDto toDto(DeliveryWeightCheck check) {
        return new WeightCheckDto(
                check.id(),
                check.orderId(),
                check.taskId(),
                check.orderItemId(),
                check.productName(),
                toDouble(check.orderedQty()),
                toDouble(check.pickedWeightKg()),
                toDouble(check.customerWeightKg()),
                toDouble(check.tolerancePercent()),
                toDouble(check.diffPercent()),
                check.scaleEvidenceId(),
                check.customerEvidenceId(),
                check.verdict(),
                check.refundAmount(),
                check.handledBy(),
                DeliveryTimes.format(check.handledAt()),
                DeliveryTimes.format(check.createdAt())
        );
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
