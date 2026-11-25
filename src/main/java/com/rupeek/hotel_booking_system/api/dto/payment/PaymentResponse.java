package com.rupeek.hotel_booking_system.api.dto.payment;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public record PaymentResponse(Long id,
                              Long bookingId,
                              BookingStatus bookingStatus,
                              BigDecimal amount,
                              PaymentMethod method,
                              PaymentStatus status,
                              String transactionReference,
                              String failureReason,
                              String idempotencyKey,
                              LocalDateTime completedAt,
                              BigDecimal refundedAmount,
                              Instant createdAt) {
}
