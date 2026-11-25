package com.rupeek.hotel_booking_system.service.cancellation;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.cancellation.NonRefundableCancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.TieredCancellationPolicy;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyCancellationPolicyResolverTest {

    private final PropertyCancellationPolicyResolver resolver = new PropertyCancellationPolicyResolver(List.of(
            TieredCancellationPolicy.flexible(), TieredCancellationPolicy.moderate(),
            new NonRefundableCancellationPolicy()));

    @ParameterizedTest
    @EnumSource(CancellationPolicyType.class)
    void resolvesThePolicyChosenByTheProperty(CancellationPolicyType type) {
        Booking booking = DomainFixtures.pendingBooking(
                DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner(), type)));

        assertThat(resolver.resolve(booking).type()).isEqualTo(type);
    }

    @Test
    void rejectsTwoPoliciesForTheSameType() {
        assertThatThrownBy(() -> new PropertyCancellationPolicyResolver(List.of(
                TieredCancellationPolicy.flexible(), TieredCancellationPolicy.flexible(),
                TieredCancellationPolicy.moderate(), new NonRefundableCancellationPolicy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLEXIBLE is defined by both");
    }

    @Test
    void rejectsMissingPolicyTypes() {
        assertThatThrownBy(() -> new PropertyCancellationPolicyResolver(List.of(TieredCancellationPolicy.flexible())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No cancellation policy defined for [MODERATE, NON_REFUNDABLE]");
    }
}
