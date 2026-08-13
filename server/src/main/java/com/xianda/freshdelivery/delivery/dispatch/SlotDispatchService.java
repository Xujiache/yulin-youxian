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
    private static final Logger log = LoggerFactory.getLogger(SlotDispatchService.class);

    private final DispatchDao dispatchDao;
    private final TaskAssignmentPort assignmentPort;
    private final WaveRoutingPort waveRoutingPort;
    private final TaskUnitOfWork unitOfWork;
    private final DispatchEngine dispatchEngine;

    public SlotDispatchService(DispatchDao dispatchDao,
                               TaskAssignmentPort assignmentPort,
                               WaveRoutingPort waveRoutingPort,
                               TaskUnitOfWork unitOfWork,
                               DispatchEngine dispatchEngine) {
        this.dispatchDao = dispatchDao;
        this.assignmentPort = assignmentPort;
        this.waveRoutingPort = waveRoutingPort;
        this.unitOfWork = unitOfWork;
        this.dispatchEngine = dispatchEngine;
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

        SlotKey slot = requireSameSlot(locked, command);

        Optional<DispatchWaveRow> appendable =
                dispatchDao.findAppendableSlotWave(command.riderId(), slot.date(), slot.label());
        boolean append = appendable.isPresent();
        long waveId = append
                ? appendable.get().waveId()
                : dispatchDao.createSlotWave(command.riderId(), slot.date(), slot.label(), now);
        if (!append) {
            createdWaveId.set(waveId);
        }

        List<Long> assigned = new ArrayList<>(locked.size());
        for (DispatchTaskRow task : locked) {
            assignmentPort.assignTask(task.taskId(), command.riderId(), waveId,
                    DispatchCodes.MODE_MANUAL, null, detailJson(slot, command.riderId(), waveId));
            assigned.add(task.taskId());
        }
        dispatchDao.refreshWaveAggregates(waveId, now);
        return new Committed(waveId, !append, slot.date(), slot.label(), assigned);
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

    public record SlotDispatchCommand(Long riderId, String slotLabel, List<Long> taskIds) {}

    public record SlotDispatchResult(long waveId, boolean waveCreated, long riderId,
                                     String deliveryDate, String slotLabel, List<Long> taskIds) {}
}
