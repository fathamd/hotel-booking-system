package com.rupeek.hotel_booking_system.service.payment.gateway;

/**
 * @param refundReference the gateway's reference for the refund; {@code null} when rejected
 * @param rejectionReason why the refund was rejected; {@code null} when approved
 */
public record GatewayRefundResponse(boolean approved, String refundReference, String rejectionReason) {

    public static GatewayRefundResponse approved(String refundReference) {
        return new GatewayRefundResponse(true, refundReference, null);
    }

    public static GatewayRefundResponse rejected(String reason) {
        return new GatewayRefundResponse(false, null, reason);
    }
}
