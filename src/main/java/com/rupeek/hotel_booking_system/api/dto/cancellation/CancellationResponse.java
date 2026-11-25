package com.rupeek.hotel_booking_system.api.dto.cancellation;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param paidAmount       amount captured for the booking; zero when it was never paid
 * @param refundPercentage share of the paid amount returned, 0-100
 * @param refundRule       the rule that produced the refund decision
 * @param refund           the refund issued; {@code null} when nothing is refunded
 */
public record CancellationResponse(Long bookingId,
                                   BookingStatus bookingStatus,
                                   LocalDateTime cancelledAt,
                                   CancellationPolicyType cancellationPolicy,
                                   BigDecimal paidAmount,
                                   BigDecimal refundPercentage,
                                   BigDecimal refundAmount,
                                   String refundRule,
                                   RefundResponse refund) {

    public CancellationResponse withRefund(RefundResponse newRefund) {
        return new CancellationResponse(bookingId, bookingStatus, cancelledAt, cancellationPolicy, paidAmount,
                refundPercentage, refundAmount, refundRule, newRefund);
    }
}
