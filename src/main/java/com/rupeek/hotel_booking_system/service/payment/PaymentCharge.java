package com.rupeek.hotel_booking_system.service.payment;

import java.math.BigDecimal;

/**
 * What to charge. The amount comes from the persisted payment (the booking total), never from the client.
 */
public record PaymentCharge(String idempotencyKey, Long bookingId, BigDecimal amount, String currency) {

    public static final String DEFAULT_CURRENCY = "INR";

    public PaymentCharge(String idempotencyKey, Long bookingId, BigDecimal amount) {
        this(idempotencyKey, bookingId, amount, DEFAULT_CURRENCY);
    }
}
