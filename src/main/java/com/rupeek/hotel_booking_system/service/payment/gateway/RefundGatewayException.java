package com.rupeek.hotel_booking_system.service.payment.gateway;

public class RefundGatewayException extends PaymentGatewayException {

    public RefundGatewayException(String message) {
        super(message);
    }
}
