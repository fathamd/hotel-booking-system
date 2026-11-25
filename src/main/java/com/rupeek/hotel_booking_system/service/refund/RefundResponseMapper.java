package com.rupeek.hotel_booking_system.service.refund;

import com.rupeek.hotel_booking_system.api.dto.cancellation.RefundResponse;
import com.rupeek.hotel_booking_system.domain.model.Refund;

public final class RefundResponseMapper {

    private RefundResponseMapper() {
    }

    public static RefundResponse toResponse(Refund refund) {
        if (refund == null) {
            return null;
        }
        return new RefundResponse(refund.getId(), refund.getPayment().getId(), refund.getAmount(),
                refund.getStatus(), refund.getRefundReference(), refund.getFailureReason(), refund.getProcessedAt());
    }
}
