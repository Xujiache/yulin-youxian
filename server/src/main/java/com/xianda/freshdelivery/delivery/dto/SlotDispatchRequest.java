package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

/**
 * 按时段发车。
 *
 * @param riderId   收这一波单的骑手
 * @param slotLabel 期望的配送时段。可空；带上时服务端会与任务实际时段核对，
 *                  对不上直接拒绝，防止界面筛的和实际发的不是一批
 * @param taskIds   这一波要发的任务，必须全部处于待发状态且同一天同一时段
 */
public record SlotDispatchRequest(
        Long riderId,
        String slotLabel,
        List<Long> taskIds
) {}
