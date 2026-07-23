package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderItemDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobResultRequest;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigUpdateRequest;
import com.xianda.freshdelivery.service.PrintJobService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintJobServiceTests {
    @TempDir
    Path tempDir;

    @Test
    void paidOrderCreatesIdempotentJobAndCompletesThroughAgentLease() {
        PrintJobService service = new PrintJobService(tempDir.resolve("printing-state.json").toString());
        service.updateConfig(new PrinterConfigUpdateRequest(true, true, 3, "XP-58III NT"));
        String accessKey = service.regenerateAccessKey().accessKey();

        OrderDetailDto order = new OrderDetailDto(
                1001L,
                "XD202607230001",
                "已支付/待接单",
                new AddressDto(10L, "测试顾客", "13800000000", "1号楼 201室", "禹邻社区", 43.8256, 87.6168, true),
                "明日 09:00-11:00",
                List.of(new OrderItemDto(1L, 101L, "有机水培西红柿", "", "斤", 399, new BigDecimal("0.5"), 200)),
                200,
                0,
                100,
                300,
                300,
                0,
                "请电话联系",
                "2026-07-23T10:00:00",
                "",
                "",
                10001L,
                List.of()
        );

        PrintJobDto queued = service.enqueuePaidOrder(order);
        assertNotNull(queued);
        assertEquals("PENDING", queued.status());
        assertEquals(queued.id(), service.enqueuePaidOrder(order).id());
        assertEquals("¥ 2.00", queued.receipt().productAmount());
        assertNull(queued.leaseToken());

        service.requireAgentKey(accessKey);
        assertThrows(BusinessException.class, () -> service.requireAgentKey("wrong-key"));

        PrintJobDto claimed = service.claimNext("TEST-PC", "NET,192.168.1.30,9100", "1.0.0");
        assertNotNull(claimed);
        assertEquals(queued.id(), claimed.id());
        assertEquals("PRINTING", claimed.status());
        assertNotNull(claimed.leaseToken());

        PrintJobDto completed = service.complete(claimed.id(), new PrintJobResultRequest(claimed.leaseToken(), true, "打印完成"));
        assertEquals("SUCCESS", completed.status());
        assertNotNull(completed.printedAt());
        assertNull(completed.leaseToken());
    }
}
