package com.rupeek.hotel_booking_system.api.dto.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Onboards several properties for one owner in a single, all-or-nothing request.
 */
public record CreatePropertiesRequest(
        @NotEmpty @Size(max = CreatePropertiesRequest.MAX_BATCH_SIZE)
        List<@NotNull @Valid CreatePropertyRequest> properties) {

    public static final int MAX_BATCH_SIZE = 50;
}
