package com.xianda.freshdelivery.delivery.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeliveryTaskStateMachineTests {
    private static final Map<DeliveryTaskStatus, Set<DeliveryTaskStatus>> EXPECTED_LEGAL = Map.of(
            DeliveryTaskStatus.PENDING, Set.of(DeliveryTaskStatus.ASSIGNED, DeliveryTaskStatus.CANCELLED),
            DeliveryTaskStatus.ASSIGNED, Set.of(DeliveryTaskStatus.ACCEPTED, DeliveryTaskStatus.PENDING,
                    DeliveryTaskStatus.EXCEPTION, DeliveryTaskStatus.CANCELLED),
            DeliveryTaskStatus.ACCEPTED, Set.of(DeliveryTaskStatus.PICKED_UP, DeliveryTaskStatus.PENDING,
                    DeliveryTaskStatus.EXCEPTION, DeliveryTaskStatus.CANCELLED),
            DeliveryTaskStatus.PICKED_UP, Set.of(DeliveryTaskStatus.DELIVERING, DeliveryTaskStatus.EXCEPTION),
            DeliveryTaskStatus.DELIVERING, Set.of(DeliveryTaskStatus.ARRIVED, DeliveryTaskStatus.DELIVERED,
                    DeliveryTaskStatus.EXCEPTION),
            DeliveryTaskStatus.ARRIVED, Set.of(DeliveryTaskStatus.DELIVERED, DeliveryTaskStatus.EXCEPTION),
            DeliveryTaskStatus.EXCEPTION, Set.of(DeliveryTaskStatus.DELIVERING, DeliveryTaskStatus.RETURNED,
                    DeliveryTaskStatus.CANCELLED),
            DeliveryTaskStatus.DELIVERED, Set.of(),
            DeliveryTaskStatus.RETURNED, Set.of(),
            DeliveryTaskStatus.CANCELLED, Set.of()
    );

    private final DeliveryTaskStateMachine stateMachine = new DeliveryTaskStateMachine();

    @Test
    void fullTransitionMatrixMatchesFrozenStateMachine() {
        List<String> mismatches = new ArrayList<>();
        for (DeliveryTaskStatus from : DeliveryTaskStatus.values()) {
            for (DeliveryTaskStatus to : DeliveryTaskStatus.values()) {
                boolean expected = EXPECTED_LEGAL.get(from).contains(to);
                boolean actual = stateMachine.canTransit(from, to);
                if (expected != actual) {
                    mismatches.add(from + "->" + to + " expected=" + expected + " actual=" + actual);
                }
                if (expected) {
                    assertDoesNotThrow(() -> stateMachine.ensureTransit(from, to), from + "->" + to);
                } else {
                    DeliveryException exception = assertThrows(
                            DeliveryException.class,
                            () -> stateMachine.ensureTransit(from, to),
                            from + "->" + to
                    );
                    assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "状态机矩阵不一致：" + mismatches);
    }

    @Test
    void matrixCovers100Combinations() {
        int combinations = DeliveryTaskStatus.values().length * DeliveryTaskStatus.values().length;
        assertEquals(100, combinations);
    }

    @Test
    void selfTransitionIsAlwaysIllegal() {
        for (DeliveryTaskStatus status : DeliveryTaskStatus.values()) {
            assertFalse(stateMachine.canTransit(status, status), status + " 不应允许自转");
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransition() {
        for (DeliveryTaskStatus status : DeliveryTaskStatus.values()) {
            if (!status.isTerminal()) {
                continue;
            }
            for (DeliveryTaskStatus target : DeliveryTaskStatus.values()) {
                assertFalse(stateMachine.canTransit(status, target), status + "->" + target);
            }
        }
    }

    @Test
    void resolvePathUsesExceptionHubForInProgressReturn() {
        assertEquals(
                List.of(DeliveryTaskStatus.EXCEPTION, DeliveryTaskStatus.RETURNED),
                stateMachine.resolvePath(DeliveryTaskStatus.DELIVERING, DeliveryTaskStatus.RETURNED)
        );
        assertEquals(
                List.of(DeliveryTaskStatus.RETURNED),
                stateMachine.resolvePath(DeliveryTaskStatus.EXCEPTION, DeliveryTaskStatus.RETURNED)
        );
        assertEquals(
                List.of(DeliveryTaskStatus.EXCEPTION, DeliveryTaskStatus.CANCELLED),
                stateMachine.resolvePath(DeliveryTaskStatus.PICKED_UP, DeliveryTaskStatus.CANCELLED)
        );
    }

    @Test
    void resolvePathRejectsPickedUpTransferBackToPending() {
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> stateMachine.resolvePath(DeliveryTaskStatus.PICKED_UP, DeliveryTaskStatus.PENDING)
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    @Test
    void ownershipCheckThrows1012ForOtherRider() {
        DeliveryTask task = taskOwnedBy(7L);
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> stateMachine.ensureOwnedBy(task, 8L)
        );
        assertEquals(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, exception.code());
        assertDoesNotThrow(() -> stateMachine.ensureOwnedBy(task, 7L));
    }

    @Test
    void ownershipCheckThrows1012ForUnassignedTask() {
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> stateMachine.ensureOwnedBy(taskOwnedBy(null), 7L)
        );
        assertEquals(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, exception.code());
    }

    @Test
    void parseRejectsUnknownStatus() {
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> stateMachine.parse("NOT_A_STATUS")
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    private static DeliveryTask taskOwnedBy(Long riderId) {
        return new DeliveryTask(
                1L, "PS20260811000001", 1001L, "XD001", null, riderId, "ASSIGNED",
                "王女士", "13800005678", "138****5678", "阳光小区 3号楼", null, null, null, null, null, null,
                null, null, null, 1, null, "NORMAL", 1, null, null, null,
                java.time.LocalDate.now(), null, null, null, null, null, null, null, null,
                0, null, null, null, null, null, null, null, null, null,
                0, 0, null, null, 0, null, null, 0, null, 0, null, 0, 0, null, null, null
        );
    }
}
