package com.rupeek.hotel_booking_system.service.payment.gateway;

/**
 * @param transactionReference the gateway's reference for the attempt; present for approvals and declines
 * @param declineReason        why the charge was declined; {@code null} when approved
 */
public record GatewayChargeResponse(boolean approved, String transactionReference, String declineReason) {

    public static GatewayChargeResponse approved(String transactionReference) {
        return new GatewayChargeResponse(true, transactionReference, null);
    }

    public static GatewayChargeResponse declined(String transactionReference, String reason) {
        return new GatewayChargeResponse(false, transactionReference, reason);
    }
}
