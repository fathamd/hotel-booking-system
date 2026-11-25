package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * The inputs a cancellation policy needs. Keeping it independent of the Booking entity keeps policies pure and easy to test.
 * Both moments are instants, so the notice period is correct whatever the property's and server's time zones.
 */
public record CancellationContext(BigDecimal paidAmount, Instant checkInAt, Instant cancelledAt) {

    public CancellationContext {
        paidAmount = MonetaryAmounts.requireNonNegative(paidAmount, "paidAmount");
        DomainPreconditions.requireNonNull(checkInAt, "checkInAt");
        DomainPreconditions.requireNonNull(cancelledAt, "cancelledAt");
    }

    /**
     * Time left between cancellation and check-in; negative when cancelled after check-in.
     */
    public Duration noticeBeforeCheckIn() {
        return Duration.between(cancelledAt, checkInAt);
    }
}
