package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.account.RiderShiftService;
import com.xianda.freshdelivery.delivery.dto.FatigueConfirmRequest;
import com.xianda.freshdelivery.delivery.dto.OffDutyRequest;
import com.xianda.freshdelivery.delivery.dto.OnDutyRequest;
import com.xianda.freshdelivery.delivery.dto.OnDutyResponse;
import com.xianda.freshdelivery.delivery.dto.RestRequest;
import com.xianda.freshdelivery.delivery.dto.ShiftCurrentDto;
import com.xianda.freshdelivery.delivery.dto.ShiftHistoryItemDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/shift")
public class RiderShiftController {
    private final RiderShiftService riderShiftService;

    public RiderShiftController(RiderShiftService riderShiftService) {
        this.riderShiftService = riderShiftService;
    }

    @GetMapping("/current")
    public ApiResponse<ShiftCurrentDto> current() {
        return ApiResponse.ok(riderShiftService.current(CurrentRiderContext.riderId()));
    }

    @PostMapping("/on-duty")
    public ApiResponse<OnDutyResponse> onDuty(@RequestBody(required = false) OnDutyRequest request) {
        return ApiResponse.ok(riderShiftService.onDuty(CurrentRiderContext.riderId(), request));
    }

    @PostMapping("/off-duty")
    public ApiResponse<ShiftCurrentDto> offDuty(@RequestBody(required = false) OffDutyRequest request) {
        return ApiResponse.ok(riderShiftService.offDuty(
                CurrentRiderContext.riderId(), request == null ? null : request.reason()));
    }

    @PostMapping("/rest")
    public ApiResponse<ShiftCurrentDto> rest(@RequestBody RestRequest request) {
        return ApiResponse.ok(riderShiftService.rest(
                CurrentRiderContext.riderId(), request == null ? null : request.action()));
    }

    @PostMapping("/fatigue-confirm")
    public ApiResponse<ShiftCurrentDto> fatigueConfirm(@RequestBody(required = false) FatigueConfirmRequest request) {
        return ApiResponse.ok(riderShiftService.fatigueConfirm(CurrentRiderContext.riderId(), request));
    }

    @GetMapping("/history")
    public ApiResponse<List<ShiftHistoryItemDto>> history(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(riderShiftService.history(
                CurrentRiderContext.riderId(), DeliveryTimes.parseDate(from), DeliveryTimes.parseDate(to)));
    }
}
