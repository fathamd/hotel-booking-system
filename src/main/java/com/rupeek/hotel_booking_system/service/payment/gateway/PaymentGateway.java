package com.rupeek.hotel_booking_system.service.payment.gateway;

import java.util.Optional;

/**
 * Port to an external payment gateway. Implementations must be idempotent on
 * {@link GatewayChargeRequest#idempotencyKey()}: repeating a request with the same key must return the original
 * outcome without charging the customer again. Real gateways (Razorpay, Stripe, ...) offer this natively.
 */
public interface PaymentGateway {

    /**
     * @return the gateway's decision; a decline is a normal outcome, not an exception
     * @throws PaymentGatewayException if the gateway could not be reached or gave no decision; the request
     *                                 may be retried with the same idempotency key
     */
    GatewayChargeResponse charge(GatewayChargeRequest request);

    /**
     * Looks up the decision for a charge previously sent with {@code idempotencyKey}, without charging.
     * Used to reconcile payments whose outcome was never recorded.
     *
     * @return the decision, or empty if the gateway never received the charge
     * @throws PaymentGatewayException if the gateway could not be reached
     */
    Optional<GatewayChargeResponse> findCharge(String idempotencyKey);
}
