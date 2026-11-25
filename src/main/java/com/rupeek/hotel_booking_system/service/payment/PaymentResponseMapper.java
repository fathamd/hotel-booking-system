package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentResponse;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;

final class PaymentResponseMapper {

    private PaymentResponseMapper() {
    }

    static PaymentResponse toResponse(Payment payment) {
        Booking booking = payment.getBooking();
        return new PaymentResponse(payment.getId(), booking.getId(), booking.getStatus(), payment.getAmount(),
                payment.getMethod(), payment.getStatus(), payment.getTransactionReference(),
                payment.getFailureReason(), payment.getIdempotencyKey(), payment.getCompletedAt(),
                payment.getRefundedAmount(), payment.getCreatedAt());
    }
}
