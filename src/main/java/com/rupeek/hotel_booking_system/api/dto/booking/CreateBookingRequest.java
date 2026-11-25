package com.rupeek.hotel_booking_system.api.dto.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Request to reserve {@code rooms} rooms of one room type for {@code [checkIn, checkOut)}.
 * Cross-field rules (date order, guests per room, availability) are checked by the service and domain.
 */
public record CreateBookingRequest(
        @NotNull @Positive Long propertyId,
        @NotNull @Positive Long roomTypeId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Min(1) @Max(MAX_GUESTS) Integer guests,
        @NotNull @Min(1) @Max(MAX_ROOMS) Integer rooms,
        @NotNull @Valid GuestRequest guest) {

    public static final int MAX_ROOMS = 10;
    public static final int MAX_GUESTS = 100;
}
