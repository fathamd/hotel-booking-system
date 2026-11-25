package com.rupeek.hotel_booking_system.service.cancellation;

import com.rupeek.hotel_booking_system.domain.cancellation.CancellationPolicy;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Default resolver: applies the policy type chosen by the property at onboarding.
 * Fails at startup if any policy type has no policy bean, or has more than one.
 */
@Component
public class PropertyCancellationPolicyResolver implements CancellationPolicyResolver {

    private final Map<CancellationPolicyType, CancellationPolicy> policiesByType =
            new EnumMap<>(CancellationPolicyType.class);

    public PropertyCancellationPolicyResolver(List<CancellationPolicy> policies) {
        for (CancellationPolicy policy : policies) {
            CancellationPolicy previous = policiesByType.putIfAbsent(policy.type(), policy);
            if (previous != null) {
                throw new IllegalStateException("Cancellation policy " + policy.type() + " is defined by both "
                        + previous.getClass().getSimpleName() + " and " + policy.getClass().getSimpleName());
            }
        }
        List<CancellationPolicyType> missing = Arrays.stream(CancellationPolicyType.values())
                .filter(type -> !policiesByType.containsKey(type))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No cancellation policy defined for " + missing);
        }
    }

    @Override
    public CancellationPolicy resolve(Booking booking) {
        return policiesByType.get(booking.getProperty().getCancellationPolicyType());
    }
}
