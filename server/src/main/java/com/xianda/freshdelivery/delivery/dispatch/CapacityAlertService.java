package com.xianda.freshdelivery.delivery.dispatch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CapacityAlertService {
    public static final int OVERTIME_RISK_ALERT_THRESHOLD = 3;
    public static final double OVERLOAD_RATIO_THRESHOLD = 0.9d;

    private static final Logger log = LoggerFactory.getLogger(CapacityAlertService.class);

    private final DispatchDao dispatchDao;
    private final RiderScoringService scoringService;
    private final DispatchSettings settings;
    private final Map<String, LocalDateTime> lastSentAt = new ConcurrentHashMap<>();

    public CapacityAlertService(DispatchDao dispatchDao,
                                RiderScoringService scoringService,
                                DispatchSettings settings) {
        this.dispatchDao = dispatchDao;
        this.scoringService = scoringService;
        this.settings = settings;
    }

    public List<String> evaluate(DispatchContext context, int pendingCount, int overtimeRiskCount) {
        List<String> raised = new ArrayList<>();
        LocalDateTime now = context.now();
        int onDuty = context.onDutyRiderCount();
        int available = availableRiderCount(context);

        if (pendingCount > onDuty * 2) {
            raise(raised, DispatchCodes.ALERT_BACKLOG, null, "待派积压告警",
                    "当前待派 " + pendingCount + " 单，在岗骑手 " + onDuty + " 人，已超过运力两倍，请增派人手",
                    "URGENT", now);
        }
        if (available == 0 && pendingCount > 0) {
            raise(raised, DispatchCodes.ALERT_NO_CAPACITY, null, "无可用运力",
                    "当前有 " + pendingCount + " 单待派但无可用骑手，压单已暂停，任务保留在待派队列等待运力",
                    "URGENT", now);
        }
        if (overtimeRiskCount > OVERTIME_RISK_ALERT_THRESHOLD) {
            raise(raised, DispatchCodes.ALERT_OVERTIME_RISK, null, "超时风险集中",
                    "当前有 " + overtimeRiskCount + " 单处于高超时风险，请关注调度台并考虑人工干预",
                    "HIGH", now);
        }
        for (RiderCandidateRow rider : context.riders()) {
            if (!rider.onDuty() || !rider.active()) {
                continue;
            }
            if (rider.locationStale(now, settings.livenessTimeoutSeconds())) {
                raise(raised, DispatchCodes.ALERT_RIDER_OFFLINE + ":" + rider.riderId(), rider.riderId(),
                        "定位已掉线",
                        "系统已超过 " + settings.livenessTimeoutSeconds() + " 秒未收到你的位置，请检查网络与定位权限并保持 App 在后台运行",
                        "HIGH", now);
                continue;
            }
            double loadRatio = loadRatioOf(rider, context);
            if (loadRatio > OVERLOAD_RATIO_THRESHOLD) {
                raise(raised, DispatchCodes.ALERT_RIDER_OVERLOAD + ":" + rider.riderId(), null,
                        "骑手接近满载",
                        "骑手 " + rider.name() + " 当前负载率 " + Math.round(loadRatio * 100) + "%，已基本退出派单竞争",
                        "NORMAL", now);
            }
        }
        return raised;
    }

    public int availableRiderCount(DispatchContext context) {
        int available = 0;
        for (RiderCandidateRow rider : context.riders()) {
            if (!rider.onDuty() || !rider.active()) {
                continue;
            }
            if (rider.fatiguePaused(context.now())
                    || rider.locationStale(context.now(), settings.livenessTimeoutSeconds())) {
                continue;
            }
            if (loadRatioOf(rider, context) >= 1d) {
                continue;
            }
            available++;
        }
        return available;
    }

    public double loadRatioOf(RiderCandidateRow rider, DispatchContext context) {
        List<DispatchTaskRow> active = context.activeTasksOf(rider.riderId());
        int maxConcurrent = scoringService.effectiveMaxConcurrent(rider);
        double capacity = rider.capacityWeightKg() <= 0d ? 1d : rider.capacityWeightKg();
        double weight = active.stream().mapToDouble(DispatchTaskRow::totalWeightKg).sum();
        return Math.max((double) active.size() / maxConcurrent, weight / capacity);
    }

    private void raise(List<String> raised, String alertKey, Long riderId, String title, String content,
                       String priority, LocalDateTime now) {
        LocalDateTime previous = lastSentAt.get(alertKey);
        if (previous != null && previous.plusSeconds(settings.alertCooldownSeconds()).isAfter(now)) {
            return;
        }
        lastSentAt.put(alertKey, now);
        raised.add(alertKey);
        String messageType = riderId == null ? "ANNOUNCEMENT" : "SYSTEM";
        try {
            dispatchDao.insertMessage(riderId, messageType, title, content, priority, riderId != null,
                    "NONE", null);
        } catch (RuntimeException exception) {
            log.warn("运力预警 {} 写入 rider_message 失败：{}", alertKey, exception.getMessage());
        }
        log.warn("运力预警 {}：{} - {}", alertKey, title, content);
    }
}
