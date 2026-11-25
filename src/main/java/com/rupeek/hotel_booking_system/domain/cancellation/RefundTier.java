package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Refund {@code refundPercentage} of the paid amount when cancelled at least {@code minimumNotice} before check-in.
 */
public record RefundTier(Duration minimumNotice, BigDecimal refundPercentage) {

    public RefundTier {
        DomainPreconditions.requireNonNull(minimumNotice, "minimumNotice");
        DomainPreconditions.requireNonNull(refundPercentage, "refundPercentage");
        if (minimumNotice.isNegative()) {
            throw new DomainValidationException("minimumNotice must not be negative");
        }
        if (refundPercentage.signum() < 0 || refundPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new DomainValidationException("refundPercentage must be between 0 and 100");
        }
    }

    public boolean appliesTo(Duration notice) {
        return notice.compareTo(minimumNotice) >= 0;
    }
}
