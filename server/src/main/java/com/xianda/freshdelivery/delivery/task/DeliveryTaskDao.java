package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.booleanValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.date;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateTimeValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.decimalValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.doubleValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.flag;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.intValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.longValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.timestamp;

import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryTaskDao {
    static final String COLUMNS = """
            id, task_no, order_id, order_no, wave_id, rider_id, status,
            receiver_name, receiver_phone, receiver_phone_masked, address_detail, address_lat, address_lng,
            geocode_source, area_label, building_label, group_key, unit_no, floor_no, room_no,
            item_count, total_weight_kg, cold_chain_level, package_count, goods_summary, customer_remark,
            delivery_instruction, delivery_date, slot_label, window_start_at, window_end_at, promised_at,
            eta_at, eta_lower_at, eta_upper_at, eta_updated_at, extra_time_seconds, extra_time_reason,
            picked_ready_at, assigned_at, accepted_at, picked_up_at, departed_at, arrived_at, delivered_at,
            closed_at, plan_distance_meters, actual_distance_meters, handoff_seconds, is_on_time,
            overtime_seconds, dispatch_mode, dispatch_score, reassign_count, hold_until_at, priority,
            current_exception_id, delivery_fee_amount, marketing_discount_amount, marketing_gift_summary,
            created_at, updated_at
            """;

    private static final RowMapper<DeliveryTask> MAPPER = (rs, rowNum) -> new DeliveryTask(
            longValue(rs, "id"),
            rs.getString("task_no"),
            longValue(rs, "order_id"),
            rs.getString("order_no"),
            longValue(rs, "wave_id"),
            longValue(rs, "rider_id"),
            rs.getString("status"),
            rs.getString("receiver_name"),
            rs.getString("receiver_phone"),
            rs.getString("receiver_phone_masked"),
            rs.getString("address_detail"),
            doubleValue(rs, "address_lat"),
            doubleValue(rs, "address_lng"),
            rs.getString("geocode_source"),
            rs.getString("area_label"),
            rs.getString("building_label"),
            rs.getString("group_key"),
            intValue(rs, "unit_no"),
            intValue(rs, "floor_no"),
            rs.getString("room_no"),
            intValue(rs, "item_count"),
            decimalValue(rs, "total_weight_kg"),
            rs.getString("cold_chain_level"),
            intValue(rs, "package_count"),
            rs.getString("goods_summary"),
            rs.getString("customer_remark"),
            rs.getString("delivery_instruction"),
            dateValue(rs, "delivery_date"),
            rs.getString("slot_label"),
            dateTimeValue(rs, "window_start_at"),
            dateTimeValue(rs, "window_end_at"),
            dateTimeValue(rs, "promised_at"),
            dateTimeValue(rs, "eta_at"),
            dateTimeValue(rs, "eta_lower_at"),
            dateTimeValue(rs, "eta_upper_at"),
            dateTimeValue(rs, "eta_updated_at"),
            intValue(rs, "extra_time_seconds"),
            rs.getString("extra_time_reason"),
            dateTimeValue(rs, "picked_ready_at"),
            dateTimeValue(rs, "assigned_at"),
            dateTimeValue(rs, "accepted_at"),
            dateTimeValue(rs, "picked_up_at"),
            dateTimeValue(rs, "departed_at"),
            dateTimeValue(rs, "arrived_at"),
            dateTimeValue(rs, "delivered_at"),
            dateTimeValue(rs, "closed_at"),
            intValue(rs, "plan_distance_meters"),
            intValue(rs, "actual_distance_meters"),
            intValue(rs, "handoff_seconds"),
            booleanValue(rs, "is_on_time"),
            intValue(rs, "overtime_seconds"),
            rs.getString("dispatch_mode"),
            decimalValue(rs, "dispatch_score"),
            intValue(rs, "reassign_count"),
            dateTimeValue(rs, "hold_until_at"),
            intValue(rs, "priority"),
            longValue(rs, "current_exception_id"),
            intValue(rs, "delivery_fee_amount"),
            intValue(rs, "marketing_discount_amount"),
            rs.getString("marketing_gift_summary"),
            dateTimeValue(rs, "created_at"),
            dateTimeValue(rs, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public DeliveryTaskDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DeliveryTask> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DeliveryTask> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE id = ? FOR UPDATE", MAPPER, id
        ).stream().findFirst();
    }

    public Optional<DeliveryTask> findByOrderId(long orderId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_task WHERE order_id = ?", MAPPER, orderId)
                .stream().findFirst();
    }

    public Optional<DeliveryTask> findByOrderIdForUpdate(long orderId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE order_id = ? FOR UPDATE", MAPPER, orderId
        ).stream().findFirst();
    }

    public List<DeliveryTask> findByOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", orderIds.stream().map(id -> "?").toList());
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE order_id IN (" + placeholders + ")",
                MAPPER,
                orderIds.toArray()
        );
    }

    public List<DeliveryTask> findByWaveId(long waveId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE wave_id = ?"
                        + " ORDER BY COALESCE((SELECT s.seq_no FROM delivery_wave_stop s"
                        + " WHERE s.wave_id = delivery_task.wave_id AND s.task_id = delivery_task.id), 9999), id",
                MAPPER,
                waveId
        );
    }

    public List<DeliveryTask> findByWaveIdForUpdate(long waveId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE wave_id = ? ORDER BY id FOR UPDATE",
                MAPPER,
                waveId
        );
    }

    public List<DeliveryTask> findByRiderAndStatuses(long riderId, Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(riderId);
        args.addAll(statuses);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE rider_id = ? AND status IN (" + placeholders + ")"
                        + " ORDER BY COALESCE(wave_id, 0), priority DESC, id",
                MAPPER,
                args.toArray()
        );
    }

    public List<DeliveryTask> findByRiderClosedOn(long riderId, LocalDate day) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task WHERE rider_id = ?"
                        + " AND status IN ('DELIVERED','RETURNED','CANCELLED')"
                        + " AND COALESCE(delivered_at, closed_at) >= ?"
                        + " AND COALESCE(delivered_at, closed_at) < ?"
                        + " ORDER BY COALESCE(delivered_at, closed_at) DESC, id DESC",
                MAPPER,
                riderId,
                timestamp(day.atStartOfDay()),
                timestamp(day.plusDays(1).atStartOfDay())
        );
    }

    public List<DeliveryTask> search(
            String status,
            Long riderId,
            Long waveId,
            LocalDate deliveryDate,
            String keyword,
            int offset,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_task WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, riderId, waveId, deliveryDate, keyword);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String status, Long riderId, Long waveId, LocalDate deliveryDate, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_task WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, riderId, waveId, deliveryDate, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> args,
            String status,
            Long riderId,
            Long waveId,
            LocalDate deliveryDate,
            String keyword
    ) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        if (waveId != null) {
            sql.append(" AND wave_id = ?");
            args.add(waveId);
        }
        if (deliveryDate != null) {
            sql.append(" AND delivery_date = ?");
            args.add(date(deliveryDate));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (task_no LIKE ? OR order_no LIKE ? OR receiver_name LIKE ? OR address_detail LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
    }

    public int countSameGroup(String groupKey, LocalDate deliveryDate) {
        if (groupKey == null || groupKey.isBlank()) {
            return 1;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE group_key = ? AND delivery_date = ?"
                        + " AND status NOT IN ('CANCELLED','RETURNED')",
                Integer.class,
                groupKey,
                date(deliveryDate)
        );
        return count == null ? 1 : Math.max(count, 1);
    }

    public String maxTaskNoWithPrefix(String prefix) {
        return jdbcTemplate.query(
                "SELECT MAX(task_no) FROM delivery_task WHERE task_no LIKE ?",
                rs -> rs.next() ? rs.getString(1) : null,
                prefix + "%"
        );
    }

    public long insert(DeliveryTask task) {
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    task_no, order_id, order_no, wave_id, rider_id, status,
                    receiver_name, receiver_phone, receiver_phone_masked, address_detail, address_lat, address_lng,
                    geocode_source, area_label, building_label, group_key, unit_no, floor_no, room_no,
                    item_count, total_weight_kg, cold_chain_level, package_count, goods_summary, customer_remark,
                    delivery_instruction, delivery_date, slot_label, window_start_at, window_end_at, promised_at,
                    extra_time_seconds, picked_ready_at, plan_distance_meters, actual_distance_meters,
                    overtime_seconds, reassign_count, hold_until_at, priority, delivery_fee_amount,
                    marketing_discount_amount, marketing_gift_summary, created_at, updated_at
                ) VALUES (
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?,?,?,?,?,
                    ?,?
                )
                """,
                task.taskNo(),
                task.orderId(),
                task.orderNo(),
                task.waveId(),
                task.riderId(),
                task.status(),
                task.receiverName(),
                task.receiverPhone(),
                task.receiverPhoneMasked(),
                task.addressDetail(),
                task.addressLat(),
                task.addressLng(),
                task.geocodeSource(),
                task.areaLabel(),
                task.buildingLabel(),
                task.groupKey(),
                task.unitNo(),
                task.floorNo(),
                task.roomNo(),
                task.itemCount() == null ? 0 : task.itemCount(),
                task.totalWeightKg() == null ? java.math.BigDecimal.ZERO : task.totalWeightKg(),
                task.coldChainLevel(),
                task.packageCount() == null ? 1 : task.packageCount(),
                task.goodsSummary(),
                task.customerRemark(),
                task.deliveryInstruction(),
                date(task.deliveryDate()),
                task.slotLabel(),
                timestamp(task.windowStartAt()),
                timestamp(task.windowEndAt()),
                timestamp(task.promisedAt()),
                task.extraTimeSeconds() == null ? 0 : task.extraTimeSeconds(),
                timestamp(task.pickedReadyAt()),
                task.planDistanceMeters() == null ? 0 : task.planDistanceMeters(),
                task.actualDistanceMeters() == null ? 0 : task.actualDistanceMeters(),
                task.overtimeSeconds() == null ? 0 : task.overtimeSeconds(),
                task.reassignCount() == null ? 0 : task.reassignCount(),
                timestamp(task.holdUntilAt()),
                task.priority() == null ? 0 : task.priority(),
                task.deliveryFeeAmount() == null ? 0 : task.deliveryFeeAmount(),
                task.marketingDiscountAmount() == null ? 0 : task.marketingDiscountAmount(),
                task.marketingGiftSummary(),
                timestamp(task.createdAt()),
                timestamp(task.updatedAt())
        );
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM delivery_task WHERE task_no = ?", Long.class, task.taskNo()
        );
        return id == null ? 0L : id;
    }

    public void resetForRedispatch(DeliveryTask task) {
        detachAttemptAssociations(task.id());
        jdbcTemplate.update("""
                UPDATE delivery_task SET
                    order_no = ?, wave_id = NULL, rider_id = NULL, status = 'PENDING',
                    receiver_name = ?, receiver_phone = ?, receiver_phone_masked = ?, address_detail = ?,
                    address_lat = ?, address_lng = ?, geocode_source = ?, area_label = ?, building_label = ?,
                    group_key = ?, unit_no = ?, floor_no = ?, room_no = ?,
                    item_count = ?, total_weight_kg = ?, cold_chain_level = ?, package_count = ?,
                    goods_summary = ?, customer_remark = ?, delivery_instruction = ?,
                    delivery_date = ?, slot_label = ?, window_start_at = ?, window_end_at = ?, promised_at = ?,
                    eta_at = NULL, eta_lower_at = NULL, eta_upper_at = NULL, eta_updated_at = NULL,
                    extra_time_seconds = 0, extra_time_reason = NULL,
                    picked_ready_at = ?, assigned_at = NULL, accepted_at = NULL, picked_up_at = NULL,
                    departed_at = NULL, arrived_at = NULL, delivered_at = NULL, closed_at = NULL,
                    plan_distance_meters = 0, actual_distance_meters = 0, handoff_seconds = NULL,
                    is_on_time = NULL, overtime_seconds = 0, dispatch_mode = NULL, dispatch_score = NULL,
                    reassign_count = 0, hold_until_at = ?, current_exception_id = NULL, delivery_fee_amount = ?,
                    marketing_discount_amount = ?, marketing_gift_summary = ?, updated_at = ?
                WHERE id = ?
                """,
                task.orderNo(),
                task.receiverName(),
                task.receiverPhone(),
                task.receiverPhoneMasked(),
                task.addressDetail(),
                task.addressLat(),
                task.addressLng(),
                task.geocodeSource(),
                task.areaLabel(),
                task.buildingLabel(),
                task.groupKey(),
                task.unitNo(),
                task.floorNo(),
                task.roomNo(),
                task.itemCount(),
                task.totalWeightKg(),
                task.coldChainLevel(),
                task.packageCount(),
                task.goodsSummary(),
                task.customerRemark(),
                task.deliveryInstruction(),
                date(task.deliveryDate()),
                task.slotLabel(),
                timestamp(task.windowStartAt()),
                timestamp(task.windowEndAt()),
                timestamp(task.promisedAt()),
                timestamp(task.pickedReadyAt()),
                timestamp(task.holdUntilAt()),
                task.deliveryFeeAmount() == null ? 0 : task.deliveryFeeAmount(),
                task.marketingDiscountAmount() == null ? 0 : task.marketingDiscountAmount(),
                task.marketingGiftSummary(),
                timestamp(task.updatedAt()),
                task.id()
        );
    }

    /**
     * V3 has a unique order_id and therefore cannot create a fresh attempt row.
     * Until V11 replaces that uniqueness with (order_id, attempt_no), redispatch
     * strictly detaches every attempt-scoped association before resetting the row.
     * Financial and score history is retained, but no longer points at the reused
     * task id; mutable evidence, ratings and task events cannot bleed into the new
     * attempt.
     */
    void detachAttemptAssociations(long taskId) {
        jdbcTemplate.update("DELETE FROM delivery_wave_stop WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM delivery_rating WHERE task_id = ?", taskId);
        jdbcTemplate.update("UPDATE delivery_evidence SET task_id = NULL WHERE task_id = ?", taskId);
        jdbcTemplate.update("UPDATE delivery_exception SET task_id = NULL WHERE task_id = ?", taskId);
        jdbcTemplate.update("UPDATE delivery_settlement_item SET task_id = NULL WHERE task_id = ?", taskId);
        jdbcTemplate.update("UPDATE rider_score_event SET task_id = NULL WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM delivery_task_event WHERE task_id = ?", taskId);
    }

    public int updateStatus(long taskId, String expectedStatus, String nextStatus, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE delivery_task SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                nextStatus, timestamp(now), taskId, expectedStatus
        );
    }

    public void updateTimestamp(long taskId, String column, LocalDateTime value) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET " + safeColumn(column) + " = ?, updated_at = ? WHERE id = ?",
                timestamp(value), timestamp(TaskTimes.now()), taskId
        );
    }

    public void updateAssignment(long taskId, Long riderId, Long waveId, String dispatchMode, Double dispatchScore, LocalDateTime assignedAt) {
        jdbcTemplate.update("""
                UPDATE delivery_task SET rider_id = ?, wave_id = ?, dispatch_mode = ?, dispatch_score = ?,
                    assigned_at = ?, updated_at = ? WHERE id = ?
                """,
                riderId, waveId, dispatchMode, dispatchScore, timestamp(assignedAt), timestamp(assignedAt), taskId);
    }

    public void clearAssignment(long taskId, boolean increaseReassignCount, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE delivery_task SET rider_id = NULL, wave_id = NULL, assigned_at = NULL, accepted_at = NULL,
                    reassign_count = reassign_count + ?, updated_at = ? WHERE id = ?
                """,
                increaseReassignCount ? 1 : 0, timestamp(now), taskId);
    }

    public void updateWave(long taskId, Long waveId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET wave_id = ?, updated_at = ? WHERE id = ?",
                waveId, timestamp(now), taskId
        );
    }

    public void updateCurrentException(long taskId, Long exceptionId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET current_exception_id = ?, updated_at = ? WHERE id = ?",
                exceptionId, timestamp(now), taskId
        );
    }

    public void updateDeliveryMetrics(long taskId, Integer handoffSeconds, Boolean onTime, Integer overtimeSeconds, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE delivery_task SET handoff_seconds = ?, is_on_time = ?, overtime_seconds = ?,
                    closed_at = ?, updated_at = ? WHERE id = ?
                """,
                handoffSeconds, flag(onTime), overtimeSeconds, timestamp(now), timestamp(now), taskId);
    }

    public void closeTask(long taskId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET closed_at = ?, updated_at = ? WHERE id = ?",
                timestamp(now), timestamp(now), taskId
        );
    }

    private static String safeColumn(String column) {
        if (!column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("非法列名：" + column);
        }
        return column;
    }
}
