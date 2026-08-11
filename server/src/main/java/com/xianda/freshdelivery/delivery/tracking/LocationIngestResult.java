package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.dto.LocationBatchResponse;

public record LocationIngestResult(
        LocationBatchResponse response,
        boolean consentBlocked
) {
}
