package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;

public final class NonRefundableCancellationPolicy implements CancellationPolicy {

    @Override
    public CancellationPolicyType type() {
        return CancellationPolicyType.NON_REFUNDABLE;
    }

    @Override
    public RefundDecision evaluate(CancellationContext context) {
        DomainPreconditions.requireNonNull(context, "context");
        return RefundDecision.noRefund("NON_REFUNDABLE: bookings are not refundable");
    }
}
