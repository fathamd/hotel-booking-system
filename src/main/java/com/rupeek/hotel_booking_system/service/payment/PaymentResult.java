package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayChargeResponse;

public record PaymentResult(boolean successful, String transactionReference, String failureReason) {

    public static PaymentResult success(String transactionReference) {
        return new PaymentResult(true, transactionReference, null);
    }

    public static PaymentResult failure(String transactionReference, String failureReason) {
        return new PaymentResult(false, transactionReference, failureReason);
    }

    public static PaymentResult from(GatewayChargeResponse response) {
        return response.approved()
                ? success(response.transactionReference())
                : failure(response.transactionReference(), response.declineReason());
    }
}
