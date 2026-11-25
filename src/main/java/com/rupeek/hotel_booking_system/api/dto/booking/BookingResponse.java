package com.rupeek.hotel_booking_system.api.dto.booking;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @param holdExpiresAt until when the rooms stay reserved while payment is pending
 */
public record BookingResponse(
        Long id,
        BookingStatus status,
        Long propertyId,
        String propertyName,
        Long roomTypeId,
        String roomTypeName,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        int guests,
        int rooms,
        BigDecimal totalPrice,
        GuestResponse guest,
        LocalDateTime holdExpiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt,
        Instant createdAt) {
}
