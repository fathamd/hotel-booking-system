package com.rupeek.hotel_booking_system.service.cancellation;

import com.rupeek.hotel_booking_system.domain.cancellation.CancellationPolicy;
import com.rupeek.hotel_booking_system.domain.model.Booking;

/**
 * Chooses the cancellation policy that applies to a booking. Replace the default bean (for example with
 * {@code @Primary}) to apply different rules, such as per-room-type or promotional policies.
 */
public interface CancellationPolicyResolver {

    CancellationPolicy resolve(Booking booking);
}
