package com.rupeek.hotel_booking_system.service.payment.gateway;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;

import java.math.BigDecimal;

/**
 * @param instrument method-specific instrument the gateway charges (card number, VPA, wallet account);
 *                   it is excluded from {@link #toString()} so it never reaches the logs
 */
public record GatewayChargeRequest(String idempotencyKey,
                                   BigDecimal amount,
                                   String currency,
                                   PaymentMethod method,
                                   String instrument,
                                   String description) {

    @Override
    public String toString() {
        return "GatewayChargeRequest[idempotencyKey=" + idempotencyKey + ", amount=" + amount + " " + currency
                + ", method=" + method + "]";
    }
}
