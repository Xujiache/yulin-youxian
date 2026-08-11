package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record PickReadyRequest(
        Integer itemCount,
        Double totalWeightKg,
        Integer packageCount,
        String coldChainLevel,
        List<WeightCheckItemRequest> weightChecks,
        Boolean autoDispatch,
        String remark
) {
    public record WeightCheckItemRequest(
            Long orderItemId,
            String productName,
            Double orderedQty,
            Double pickedWeightKg,
            Long scaleEvidenceId
    ) {}
}
