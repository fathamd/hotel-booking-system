package com.rupeek.hotel_booking_system.domain.exception;

/**
 * The payment instrument supplied for a payment method is missing or invalid, e.g. a card number failing the Luhn check.
 */
public class InvalidPaymentDetailsException extends DomainValidationException {

    public InvalidPaymentDetailsException(String message) {
        super(message);
    }
}
