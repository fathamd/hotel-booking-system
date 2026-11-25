package com.rupeek.hotel_booking_system.api.dto.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Onboards an independent hotel in one call. It is stored as an ordinary owner account with a single property.
 */
public record CreateStandalonePropertyRequest(
        @NotNull @Valid CreateOwnerRequest owner,
        @NotNull @Valid CreatePropertyRequest property) {
}
