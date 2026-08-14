package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按时段发车。
 *
 * 自营单店的实际流程：商家备好一个时段的货，在后台把这批单指给一个骑手，一键发车，
 * 骑手到店一次性取走全部再挨家送。和自动派单的区别在于「谁来决定」——
 * 这里店主已经决定好了，系统不打分不挑人，只负责把这批单原样落到一个波次里。
 *
 * 一次发车是一个事务：要么整批成功，要么一单都不动。半批成功最难收拾 ——
 * 骑手手上多了几单不知道从哪来的，剩下的还躺在待发池里。
 */
@Service
public class SlotDispatchService {
    /**
     * 一次发车最多带的任务数，对齐 {@code DeliveryTaskService.MAX_ORDER_IDS_PER_QUERY} 的先例。
     *
     * 没有上限时一个请求就能锁住整张 delivery_task，把发车事务撑成长事务。
     */
    public static final int MAX_TASKS_PER_DISPATCH = 100;

    private static final Logger log = LoggerFactory.getLogger(SlotDispatchService.class);

    private final DispatchDao dispatchDao;
    private final TaskAssignmentPort assignmentPort;
    private final WaveRoutingPort waveRoutingPort;
    private final TaskUnitOfWork unitOfWork;
    private final DispatchEngine dispatchEngine;
    private final RiderScoringService scoringService;
    private final DispatchSettings settings;

    public SlotDispatchService(DispatchDao dispatchDao,
                               TaskAssignmentPort assignmentPort,
                               WaveRoutingPort waveRoutingPort,
                               TaskUnitOfWork unitOfWork,
                               DispatchEngine dispatchEngine,
                               RiderScoringService scoringService,
                               DispatchSettings settings) {
        this.dispatchDao = dispatchDao;
        this.assignmentPort = assignmentPort;
        this.waveRoutingPort = waveRoutingPort;
        this.unitOfWork = unitOfWork;
        this.dispatchEngine = dispatchEngine;
        this.scoringService = scoringService;
        this.settings = settings;
    }

    public SlotDispatchResult dispatch(SlotDispatchCommand command) {
        if (command == null || command.riderId() == null) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER, "riderId 不能为空");
        }
        List<Long> taskIds = command.taskIds() == null ? List.of()
                : command.taskIds().stream().filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "taskIds 不能为空");
        }
        if (taskIds.size() > MAX_TASKS_PER_DISPATCH) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "一次发车最多 " + MAX_TASKS_PER_DISPATCH + " 单，当前 " + taskIds.size() + " 单");
        }
        if (dispatchDao.findRider(command.riderId()).isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER,
                    "骑手不存在或已注销：" + command.riderId());
        }

        LocalDateTime now = dispatchEngine.now();
        AtomicReference<Long> createdWaveId = new AtomicReference<>();
        Committed committed;
        try {
            committed = unitOfWork.commit(() -> commit(command, taskIds, now, createdWaveId));
        } catch (DeliveryException exception) {
            cleanupEmptyWave(createdWaveId.get());
            throw exception;
        } catch (RuntimeException exception) {
            cleanupEmptyWave(createdWaveId.get());
            log.warn("时段发车失败，任务保留在待发池：{}", exception.getMessage(), exception);
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED,
                    "发车失败，任务已保留在待发池：" + exception.getMessage());
        }

        // 规划放在事务外：算路要调外部服务，不该把数据库事务拖在那儿等。
        // 规划失败也不回滚发车 —— 单已经在骑手手上了，路线可以后补，失败会留痕在
        // delivery_route_plan_failure 里供调度台查看。
        waveRoutingPort.planWave(committed.waveId(), !committed.waveCreated());
        waveRoutingPort.recomputeWaveEta(committed.waveId());
        announceWaveAssigned(command.riderId(), committed);

        log.info("时段发车：{} {} 共 {} 单派给骑手 {}，波次 {}{}",
                committed.deliveryDate(), committed.slotLabel(), committed.taskIds().size(),
                command.riderId(), committed.waveId(), committed.waveCreated() ? "（新建）" : "（追加）");
        return new SlotDispatchResult(
                committed.waveId(),
                committed.waveCreated(),
                command.riderId(),
                committed.deliveryDate().toString(),
                committed.slotLabel(),
                committed.taskIds());
    }

    private Committed commit(SlotDispatchCommand command, List<Long> taskIds,
                             LocalDateTime now, AtomicReference<Long> createdWaveId) {
        // lockPendingTasks 的条件是 PENDING + 无骑手 + 无波次，锁不全就说明有人抢先动过
        List<DispatchTaskRow> locked = dispatchDao.lockPendingTasks(taskIds);
        if (locked.size() != taskIds.size()) {
            Set<Long> lockedIds = new LinkedHashSet<>();
            locked.forEach(task -> lockedIds.add(task.taskId()));
            List<Long> missing = taskIds.stream().filter(id -> !lockedIds.contains(id)).toList();
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "这些任务已经不在待发状态（可能已被指派或取消），请刷新后重试：" + missing);
        }
        if (!dispatchDao.lockRiders(List.of(command.riderId()))) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER,
                    "骑手记录被并发修改，请重试：" + command.riderId());
        }
        // 锁上之后再读，才不会拿着一份「刚才还在岗」的旧快照做门禁判断
        RiderCandidateRow rider = dispatchDao.findRider(command.riderId())
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER,
                        "骑手不存在或已注销：" + command.riderId()));
        ensureRiderAssignable(rider, now);

        SlotKey slot = requireSameSlot(locked, command);

        Optional<DispatchWaveRow> appendable =
                dispatchDao.findAppendableSlotWave(command.riderId(), slot.date(), slot.label());
        boolean append = appendable.isPresent();
        List<DispatchTaskRow> waveTasks = append
                ? dispatchDao.findTasksByWave(appendable.get().waveId())
                : List.of();
        ensureCapacity(rider, locked, waveTasks, command.confirmOverload());

        long waveId = append
                ? appendable.get().waveId()
                : dispatchDao.createSlotWave(command.riderId(), slot.date(), slot.label(), now);
        if (!append) {
            createdWaveId.set(waveId);
        }

        List<Long> assigned = new ArrayList<>(locked.size());
        for (DispatchTaskRow task : locked) {
            assignmentPort.assignTaskQuietly(task.taskId(), command.riderId(), waveId,
                    DispatchCodes.MODE_MANUAL, null, detailJson(slot, command.riderId(), waveId));
            assigned.add(task.taskId());
        }
        dispatchDao.refreshWaveAggregates(waveId, now);
        return new Committed(waveId, !append, slot.date(), slot.label(), assigned);
    }

    /**
     * 「店主已经决定好给谁」不等于什么骑手都能收。
     *
     * 疲劳停派是 GB/T 46862-2025 的合规红线，自动派单和人工指派都不允许 force 绕过；
     * 停用账号和不在岗的骑手连登录取货都做不到，单发过去只会烂在那儿没人管。
     * 这三项在按时段发车这条路径上没有理由变成例外。
     */
    private void ensureRiderAssignable(RiderCandidateRow rider, LocalDateTime now) {
        if (rider.fatiguePaused(now)) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_FATIGUE_SUSPENDED,
                    "骑手处于疲劳停派期（至 " + rider.dispatchPausedUntil()
                            + "），按 GB/T 46862-2025 合规要求不能派单");
        }
        if (!rider.active()) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_SUSPENDED,
                    "骑手账号状态为 " + rider.accountStatus() + "，不能派单：" + rider.riderId());
        }
        if (!rider.onDuty()) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_OFF_DUTY,
                    "骑手当前不在岗（" + rider.workStatus() + "），不能派单：" + rider.riderId());
        }
    }

    /**
     * 超限默认拒绝，但留一个显式确认的出口。
     *
     * 一个时段备好的货本来就可能超过按即时单调出来的并发和载重上限，硬拒会把这个功能废掉；
     * 默认放行又会让骑手拿到一车装不下的货，路线、ETA 和顾客那边的「预计送达」跟着一起失真。
     * 折中成把具体数字报回去让店主自己认，认了再带 confirmOverload 发一次。
     */
    private void ensureCapacity(RiderCandidateRow rider,
                                List<DispatchTaskRow> incoming,
                                List<DispatchTaskRow> waveTasks,
                                boolean confirmed) {
        List<String> overloads = new ArrayList<>();
        int waveCount = waveTasks.size() + incoming.size();
        if (waveCount > settings.maxTasksPerWave()) {
            overloads.add("波次单量 " + waveCount + " 超过上限 " + settings.maxTasksPerWave());
        }
        double waveWeightKg = totalWeightKg(waveTasks) + totalWeightKg(incoming);
        if (waveWeightKg > settings.maxWaveWeightKg()) {
            overloads.add("波次载重 " + round1(waveWeightKg) + "kg 超过上限 "
                    + round1(settings.maxWaveWeightKg()) + "kg");
        }

        List<DispatchTaskRow> active = dispatchDao.findActiveTasksByRider(rider.riderId());
        int maxConcurrent = scoringService.effectiveMaxConcurrent(rider);
        int riderCount = active.size() + incoming.size();
        if (riderCount > maxConcurrent) {
            overloads.add("骑手在手单量 " + riderCount + " 超过并发上限 " + maxConcurrent);
        }
        double riderWeightKg = totalWeightKg(active) + totalWeightKg(incoming);
        if (rider.capacityWeightKg() > 0d && riderWeightKg > rider.capacityWeightKg()) {
            overloads.add("骑手在手载重 " + round1(riderWeightKg) + "kg 超过载具上限 "
                    + round1(rider.capacityWeightKg()) + "kg");
        }

        if (overloads.isEmpty()) {
            return;
        }
        if (!confirmed) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_CONCURRENCY_LIMIT,
                    "这一波超出承载上限：" + String.join("；", overloads)
                            + "。确认仍要这么发请带 confirmOverload=true");
        }
        log.warn("时段发车经店主确认后超限下发，骑手 {}：{}", rider.riderId(), overloads);
    }

    private static double totalWeightKg(List<DispatchTaskRow> tasks) {
        return tasks.stream().mapToDouble(DispatchTaskRow::totalWeightKg).sum();
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    /**
     * 一波车只能装同一天同一时段的单。
     *
     * 混时段发车表面上能跑，但 ETA、超时判定、顾客那边的「预计送达」全会错位：
     * 14:00 的单和 19:00 的单排在一条路线上，前面那批一定超时。
     */
    private SlotKey requireSameSlot(List<DispatchTaskRow> tasks, SlotDispatchCommand command) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        Set<String> slots = new LinkedHashSet<>();
        for (DispatchTaskRow task : tasks) {
            dates.add(task.deliveryDate());
            slots.add(normalize(task.slotLabel()));
        }
        if (dates.size() > 1) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "一次只能发同一个配送日的单，当前选中了：" + dates);
        }
        if (slots.size() > 1) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "一次只能发同一个时段的单，当前选中了：" + slots);
        }
        LocalDate date = dates.iterator().next();
        String slot = slots.iterator().next();
        if (date == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务缺少配送日期，无法发车");
        }
        // 调用方带了时段就要对得上，防止界面筛的和实际发的不是一批
        String expected = normalize(command.slotLabel());
        if (expected != null && !expected.equals(slot)) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "选中任务的时段是[" + slot + "]，与请求的[" + expected + "]不一致，请刷新后重试");
        }
        return new SlotKey(date, slot);
    }

    /**
     * 整波只推一条。逐单推的话骑手连着听十几条「新任务待接单」，
     * 而产品设计就是到店后一键全接。
     */
    private void announceWaveAssigned(long riderId, Committed committed) {
        String slot = committed.slotLabel() == null || committed.slotLabel().isBlank()
                ? "本时段" : committed.slotLabel();
        try {
            dispatchDao.insertMessage(
                    riderId,
                    "TASK_ASSIGNED",
                    slot + " 已发车",
                    slot + " 共 " + committed.taskIds().size() + " 单已发给你，请一键接单后到店取货",
                    "HIGH",
                    true,
                    "WAVE",
                    String.valueOf(committed.waveId()));
        } catch (RuntimeException exception) {
            log.warn("时段发车通知骑手 {} 失败：{}", riderId, exception.getMessage());
        }
    }

    private void cleanupEmptyWave(Long waveId) {
        if (waveId == null) {
            return;
        }
        try {
            dispatchDao.deleteWaveIfEmpty(waveId);
        } catch (RuntimeException exception) {
            log.warn("发车失败后清理空波次 {} 失败：{}", waveId, exception.getMessage());
        }
    }

    private static String detailJson(SlotKey slot, long riderId, long waveId) {
        return "{\"mode\":\"SLOT_DISPATCH\",\"deliveryDate\":\"" + slot.date()
                + "\",\"slotLabel\":" + quote(slot.label())
                + ",\"riderId\":" + riderId + ",\"waveId\":" + waveId + "}";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record SlotKey(LocalDate date, String label) {}

    private record Committed(long waveId, boolean waveCreated, LocalDate deliveryDate,
                             String slotLabel, List<Long> taskIds) {}

    public record SlotDispatchCommand(Long riderId, String slotLabel, List<Long> taskIds,
                                      boolean confirmOverload) {
        public SlotDispatchCommand(Long riderId, String slotLabel, List<Long> taskIds) {
            this(riderId, slotLabel, taskIds, false);
        }
    }

    public record SlotDispatchResult(long waveId, boolean waveCreated, long riderId,
                                     String deliveryDate, String slotLabel, List<Long> taskIds) {}
}
