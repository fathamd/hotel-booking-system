package com.rupeek.hotel_booking_system.api.dto.onboarding;

import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRoomTypeRequest(
        @NotBlank @Size(max = RoomType.NAME_MAX)
        String name,

        @Size(max = RoomType.DESCRIPTION_MAX)
        String description,

        @NotNull @Min(1) @Max(RoomType.MAX_GUESTS_PER_ROOM)
        Integer maxGuests,

        @NotNull @Min(1) @Max(RoomType.MAX_TOTAL_INVENTORY)
        Integer totalInventory,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = MonetaryAmounts.SCALE)
        BigDecimal basePrice) {
}
