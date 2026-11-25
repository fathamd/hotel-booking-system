package com.rupeek.hotel_booking_system.repository;

/**
 * Identifies a payment still awaiting a gateway decision, so it can be reconciled without loading the entity.
 */
public record PendingPaymentRef(Long paymentId, Long bookingId, String idempotencyKey) {
}
