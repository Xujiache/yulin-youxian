package com.xianda.freshdelivery.delivery.dispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DispatchDao {
    List<DispatchTaskRow> findPendingTasks();

    List<DispatchTaskRow> findTasksByIds(List<Long> taskIds);

    default List<DispatchTaskRow> findTasksByWave(long waveId) {
        return List.of();
    }

    Optional<DispatchTaskRow> findTask(long taskId);

    default List<DispatchTaskRow> lockPendingTasks(List<Long> taskIds) {
        return findTasksByIds(taskIds).stream()
                .filter(task -> "PENDING".equals(task.status()))
                .filter(task -> task.riderId() == null && task.waveId() == null)
                .toList();
    }

    default Optional<DispatchTaskRow> lockTask(long taskId) {
        return findTask(taskId);
    }

    default boolean lockRiders(List<Long> riderIds) {
        return riderIds != null && riderIds.stream().distinct().allMatch(id -> findRider(id).isPresent());
    }

    List<DispatchTaskRow> findActiveTasksByRider(long riderId);

    List<DispatchTaskRow> findReassignCandidates();

    List<RiderCandidateRow> findRiderPool();

    Optional<RiderCandidateRow> findRider(long riderId);

    Optional<DispatchWaveRow> findAppendableWave(long riderId);

    /**
     * 找该骑手同一天同一时段、还没发出去的波次。
     *
     * 时段批次制下同一个时段应该只有一个波次：店主分两次发同一时段的单时要并进去，
     * 而不是给骑手开两趟车。与 {@link #findAppendableWave(long)} 的区别是它不看时段，
     * 会把不同时段的单并到一起。
     */
    default Optional<DispatchWaveRow> findAppendableSlotWave(long riderId, LocalDate deliveryDate, String slotLabel) {
        return Optional.empty();
    }

    long createWave(Long riderId, LocalDate deliveryDate, LocalDateTime now);

    default long createSlotWave(Long riderId, LocalDate deliveryDate, String slotLabel, LocalDateTime now) {
        return createWave(riderId, deliveryDate, now);
    }

    default boolean bindReassignedTaskToWave(long taskId, long riderId, long waveId, LocalDateTime now) {
        return false;
    }

    void refreshWaveAggregates(long waveId, LocalDateTime now);

    default boolean deleteWaveIfEmpty(long waveId) {
        return false;
    }

    int countPendingTasks();

    void insertMessage(Long riderId, String messageType, String title, String content,
                       String priority, boolean needVoice, String linkType, String linkTarget);
}
