package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dto.DeliveryBoardDto;
import com.xianda.freshdelivery.delivery.dto.DeliveryMapDto;
import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import com.xianda.freshdelivery.delivery.tracking.DeliveryBoardService;
import com.xianda.freshdelivery.delivery.tracking.DeliveryEventStream;
import com.xianda.freshdelivery.delivery.tracking.DeliveryHealthDto;
import com.xianda.freshdelivery.delivery.tracking.DeliveryHealthService;
import com.xianda.freshdelivery.delivery.tracking.LocationQueryService;
import com.xianda.freshdelivery.delivery.tracking.TrackingControllerSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminDeliveryBoardController extends TrackingControllerSupport {
    private final DeliveryBoardService boardService;
    private final DeliveryHealthService healthService;
    private final LocationQueryService locationQueryService;
    private final DeliveryEventStream eventStream;

    public AdminDeliveryBoardController(
            DeliveryBoardService boardService,
            DeliveryHealthService healthService,
            LocationQueryService locationQueryService,
            DeliveryEventStream eventStream
    ) {
        this.boardService = boardService;
        this.healthService = healthService;
        this.locationQueryService = locationQueryService;
        this.eventStream = eventStream;
    }

    @GetMapping("/board")
    public ApiResponse<DeliveryBoardDto> board() {
        return ApiResponse.ok(boardService.board());
    }

    @GetMapping("/board/stream")
    public SseEmitter boardStream() {
        return eventStream.open();
    }

    @GetMapping("/map")
    public ApiResponse<DeliveryMapDto> map() {
        return ApiResponse.ok(boardService.map());
    }

    @GetMapping("/health")
    public ApiResponse<DeliveryHealthDto> health() {
        return ApiResponse.ok(healthService.health());
    }

    @GetMapping("/tracks/riders/{riderId}")
    public ApiResponse<RiderTrackDto> riderTrack(
            @PathVariable Long riderId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(locationQueryService.history(
                riderId,
                parseDateTime(from, null),
                parseDateTime(to, null)
        ));
    }
}
