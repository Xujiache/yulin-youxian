package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

/**
 * 按时段发车。
 *
 * @param riderId         收这一波单的骑手
 * @param slotLabel       期望的配送时段。可空；带上时服务端会与任务实际时段核对，
 *                        对不上直接拒绝，防止界面筛的和实际发的不是一批
 * @param taskIds         这一波要发的任务，必须全部处于待发状态且同一天同一时段
 * @param confirmOverload 明知超出并发/载重上限也照发。疲劳停派、不在岗、账号停用
 *                        不在这个开关的覆盖范围内，那三项任何情况下都拒绝
 */
public record SlotDispatchRequest(
        Long riderId,
        String slotLabel,
        List<Long> taskIds,
        Boolean confirmOverload
) {}
