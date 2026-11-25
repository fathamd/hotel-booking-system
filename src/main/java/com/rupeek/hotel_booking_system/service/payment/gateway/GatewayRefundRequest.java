package com.rupeek.hotel_booking_system.service.payment.gateway;

import java.math.BigDecimal;

/**
 * @param originalTransactionReference the gateway reference of the charge being refunded
 */
public record GatewayRefundRequest(String idempotencyKey,
                                   String originalTransactionReference,
                                   BigDecimal amount,
                                   String currency) {
}
