package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BatchOrderActionRequest(
        @NotEmpty List<@NotNull Long> orderIds
) {
}
