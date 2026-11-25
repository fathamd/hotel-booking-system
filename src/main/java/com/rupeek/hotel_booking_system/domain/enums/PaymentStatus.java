package com.rupeek.hotel_booking_system.domain.enums;

/**
 * Payment lifecycle: PENDING -> SUCCEEDED | FAILED, and SUCCEEDED -> REFUNDED | PARTIALLY_REFUNDED on cancellation.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
