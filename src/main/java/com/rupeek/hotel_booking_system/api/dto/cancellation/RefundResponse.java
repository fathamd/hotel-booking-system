package com.rupeek.hotel_booking_system.api.dto.cancellation;

import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundResponse(Long id,
                             Long paymentId,
                             BigDecimal amount,
                             RefundStatus status,
                             String refundReference,
                             String failureReason,
                             LocalDateTime processedAt) {
}
