package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.dto.DispatchSuggestDto;
import java.util.List;

public record DispatchSuggestResponse(List<DispatchSuggestDto> suggestions) {
    public DispatchSuggestResponse {
        suggestions = List.copyOf(suggestions);
    }
}
