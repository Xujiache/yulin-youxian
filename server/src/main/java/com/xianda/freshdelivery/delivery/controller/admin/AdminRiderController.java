package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.account.RiderAccountService;
import com.xianda.freshdelivery.delivery.account.RiderShiftService;
import com.xianda.freshdelivery.delivery.account.RiderTrackQueryService;
import com.xianda.freshdelivery.delivery.dto.RiderAdminDto;
import com.xianda.freshdelivery.delivery.dto.RiderCreateRequest;
import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import com.xianda.freshdelivery.delivery.dto.RiderUpdateRequest;
import com.xianda.freshdelivery.delivery.dto.ShiftHistoryItemDto;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery/riders")
public class AdminRiderController {
    private final RiderAccountService riderAccountService;
    private final RiderShiftService riderShiftService;
    private final RiderTrackQueryService riderTrackQueryService;

    public AdminRiderController(
            RiderAccountService riderAccountService,
            RiderShiftService riderShiftService,
            RiderTrackQueryService riderTrackQueryService
    ) {
        this.riderAccountService = riderAccountService;
        this.riderShiftService = riderShiftService;
        this.riderTrackQueryService = riderTrackQueryService;
    }

    @GetMapping
    public ApiResponse<PageResult<RiderAdminDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return ApiResponse.ok(riderAccountService.search(status, keyword, page, pageSize));
    }

    @PostMapping
    public ApiResponse<RiderAdminDto> create(@RequestBody RiderCreateRequest request) {
        return ApiResponse.ok(riderAccountService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RiderAdminDto> detail(@PathVariable long id) {
        return ApiResponse.ok(riderAccountService.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<RiderAdminDto> update(@PathVariable long id, @RequestBody RiderUpdateRequest request) {
        return ApiResponse.ok(riderAccountService.update(id, request));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Map<String, String>> resetPassword(@PathVariable long id) {
        return ApiResponse.ok(Map.of("initialPassword", riderAccountService.resetPassword(id)));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<RiderAdminDto> suspend(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(riderAccountService.suspend(id, body == null ? null : body.get("reason")));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<RiderAdminDto> activate(@PathVariable long id) {
        return ApiResponse.ok(riderAccountService.activate(id));
    }

    @PostMapping("/{id}/force-off-duty")
    public ApiResponse<RiderAdminDto> forceOffDuty(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(riderAccountService.forceOffDuty(id, body == null ? null : body.get("reason")));
    }

    @GetMapping("/{id}/track")
    public ApiResponse<RiderTrackDto> track(
            @PathVariable long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(riderTrackQueryService.track(
                id,
                DeliveryTimes.parseDate(date),
                DeliveryTimes.parseDateTime(from),
                DeliveryTimes.parseDateTime(to)));
    }

    @GetMapping("/{id}/shifts")
    public ApiResponse<List<ShiftHistoryItemDto>> shifts(
            @PathVariable long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(riderShiftService.history(id, DeliveryTimes.parseDate(from), DeliveryTimes.parseDate(to)));
    }
}
