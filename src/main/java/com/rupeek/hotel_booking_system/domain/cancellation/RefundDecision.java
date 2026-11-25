package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;

import java.math.BigDecimal;

public record RefundDecision(BigDecimal refundPercentage, BigDecimal refundAmount, String rule) {

    public RefundDecision {
        DomainPreconditions.requireNonNull(refundPercentage, "refundPercentage");
        if (refundPercentage.signum() < 0 || refundPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new DomainValidationException("refundPercentage must be between 0 and 100");
        }
        refundAmount = MonetaryAmounts.requireNonNegative(refundAmount, "refundAmount");
        rule = DomainPreconditions.requireText(rule, "rule", 255);
    }

    public static RefundDecision noRefund(String rule) {
        return new RefundDecision(BigDecimal.ZERO, MonetaryAmounts.ZERO, rule);
    }

    public boolean isRefundable() {
        return refundAmount.signum() > 0;
    }
}
