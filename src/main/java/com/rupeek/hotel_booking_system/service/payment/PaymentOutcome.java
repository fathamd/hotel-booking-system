package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentResponse;

/**
 * @param replayed {@code true} if the idempotency key had been used before and the stored payment was returned
 *                 without charging again
 */
public record PaymentOutcome(PaymentResponse payment, boolean replayed) {
}
