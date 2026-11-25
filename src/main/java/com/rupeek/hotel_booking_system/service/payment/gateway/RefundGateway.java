package com.rupeek.hotel_booking_system.service.payment.gateway;

/**
 * Port for returning money against an earlier charge. Like {@link PaymentGateway}, implementations must be
 * idempotent on {@link GatewayRefundRequest#idempotencyKey()} so that a retried refund never pays out twice.
 */
public interface RefundGateway {

    /**
     * @return the gateway's decision; a rejected refund is a normal outcome, not an exception
     * @throws RefundGatewayException if the gateway could not be reached or gave no decision; the request
     *                                may be retried with the same idempotency key
     */
    GatewayRefundResponse refund(GatewayRefundRequest request);
}
