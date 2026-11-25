package com.rupeek.hotel_booking_system.domain.exception;

/**
 * An idempotency key that was already used for a different payment request (another booking or payment method).
 */
public class IdempotencyKeyReuseException extends DomainException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used for a different payment request");
    }
}
