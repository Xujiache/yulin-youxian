package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.repository.RiderAlertDao;
import com.xianda.freshdelivery.delivery.repository.RiderOpenTaskDao;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConsistencyJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryConsistencyJob.class);
    private static final Set<String> ACCEPTABLE_ORDER_STATUSES = Set.of("备货中", "配送中");
    private static final int SCAN_LIMIT = 5000;

    private final RiderOpenTaskDao riderOpenTaskDao;
    private final RiderAlertDao riderAlertDao;
    private final OrderSnapshotLookup orderSnapshotLookup;

    @Autowired
    public DeliveryConsistencyJob(
            RiderOpenTaskDao riderOpenTaskDao,
            RiderAlertDao riderAlertDao,
            StorefrontService storefrontService
    ) {
        this(riderOpenTaskDao, riderAlertDao, storefrontService::adminOrder);
    }

    public DeliveryConsistencyJob(
            RiderOpenTaskDao riderOpenTaskDao,
            RiderAlertDao riderAlertDao,
            OrderSnapshotLookup orderSnapshotLookup
    ) {
        this.riderOpenTaskDao = riderOpenTaskDao;
        this.riderAlertDao = riderAlertDao;
        this.orderSnapshotLookup = orderSnapshotLookup;
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Shanghai")
    public int inspect() {
        List<RiderOpenTaskDao.TaskRef> tasks = riderOpenTaskDao.findNonTerminalTasks(SCAN_LIMIT);
        int inconsistent = 0;
        for (RiderOpenTaskDao.TaskRef task : tasks) {
            String problem = inspectTask(task);
            if (problem == null) {
                continue;
            }
            inconsistent++;
            if (riderAlertDao.countSystemAlertsWithTarget(task.taskNo()) > 0) {
                continue;
            }
            riderAlertDao.insertSystemAlert(
                    task.riderId(),
                    "配送任务与订单不一致",
                    "任务 " + task.taskNo() + "（订单 " + task.orderNo() + "）" + problem + "，请人工核对",
                    "HIGH",
                    task.taskNo());
        }
        if (inconsistent > 0) {
            LOGGER.warn("Delivery consistency inspection found {} inconsistent tasks out of {}", inconsistent, tasks.size());
        }
        return inconsistent;
    }

    private String inspectTask(RiderOpenTaskDao.TaskRef task) {
        if (task.orderId() == null) {
            return "缺少订单软引用";
        }
        OrderDetailDto order;
        try {
            order = orderSnapshotLookup.find(task.orderId());
        } catch (RuntimeException exception) {
            return "订单快照中不存在该订单";
        }
        if (order == null) {
            return "订单快照中不存在该订单";
        }
        if (task.orderNo() != null && order.orderNo() != null && !task.orderNo().equals(order.orderNo())) {
            return "订单号不一致（快照为 " + order.orderNo() + "）";
        }
        if (order.status() != null && !ACCEPTABLE_ORDER_STATUSES.contains(order.status())) {
            return "订单状态为「" + order.status() + "」，与进行中的配送任务不匹配";
        }
        return null;
    }

    @FunctionalInterface
    public interface OrderSnapshotLookup {
        OrderDetailDto find(Long orderId);
    }
}
