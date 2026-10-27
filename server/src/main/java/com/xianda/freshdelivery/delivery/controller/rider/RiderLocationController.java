package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.dto.LocationBatchRequest;
import com.xianda.freshdelivery.delivery.dto.LocationBatchResponse;
import com.xianda.freshdelivery.delivery.tracking.LocationIngestResult;
import com.xianda.freshdelivery.delivery.tracking.LocationIngestService;
import com.xianda.freshdelivery.delivery.tracking.TrackingControllerSupport;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/locations")
public class RiderLocationController extends TrackingControllerSupport {
    private final LocationIngestService locationIngestService;

    public RiderLocationController(LocationIngestService locationIngestService) {
        this.locationIngestService = locationIngestService;
    }

    @PostMapping("/batch")
    public ApiResponse<LocationBatchResponse> batch(@RequestBody(required = false) LocationBatchRequest request) {
        LocationIngestResult result = locationIngestService.ingest(CurrentRiderContext.riderId(), request);
        if (result.consentBlocked()) {
            return new ApiResponse<>(
                    DeliveryErrorCode.LOCATION_REJECTED,
                    DeliveryErrorCode.messageOf(DeliveryErrorCode.LOCATION_CONSENT_REQUIRED),
                    result.response(),
                    OffsetDateTime.now()
            );
        }
        return ApiResponse.ok(result.response());
    }
}
