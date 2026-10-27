package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record BatchPickReadyRequest(
        List<Long> orderIds,
        Boolean autoDispatch
) {}
