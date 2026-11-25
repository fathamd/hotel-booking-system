package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;

/**
 * Decides how much of the paid amount is refunded when a booking is cancelled.
 * New policies are added by implementing this interface, without changing existing code.
 */
public interface CancellationPolicy {

    CancellationPolicyType type();

    RefundDecision evaluate(CancellationContext context);
}
