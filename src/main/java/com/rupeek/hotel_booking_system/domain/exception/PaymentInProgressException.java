package com.rupeek.hotel_booking_system.domain.exception;

/**
 * Another payment attempt for the booking is still awaiting the gateway's answer. Only one attempt may be in flight,
 * otherwise two different idempotency keys could charge the customer twice.
 */
public class PaymentInProgressException extends DomainException {

    public PaymentInProgressException(Long bookingId) {
        super("A payment for booking " + bookingId + " is already in progress; retry with the same Idempotency-Key "
                + "or wait for it to complete");
    }
}
