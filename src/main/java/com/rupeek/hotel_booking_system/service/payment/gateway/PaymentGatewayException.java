package com.rupeek.hotel_booking_system.service.payment.gateway;

/**
 * Technical gateway failure (timeout, outage). The outcome of the charge is unknown, so the payment
 * transaction is rolled back and the client should retry with the same idempotency key.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
