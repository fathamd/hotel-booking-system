package com.rupeek.hotel_booking_system.config;

import com.rupeek.hotel_booking_system.domain.cancellation.CancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.NonRefundableCancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.TieredCancellationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default cancellation policies, one per {@link com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType}.
 * Changing the rules means replacing these beans; the cancellation service only sees the
 * {@link CancellationPolicy} interface.
 */
@Configuration
public class CancellationPolicyConfiguration {

    @Bean
    public CancellationPolicy flexibleCancellationPolicy() {
        return TieredCancellationPolicy.flexible();
    }

    @Bean
    public CancellationPolicy moderateCancellationPolicy() {
        return TieredCancellationPolicy.moderate();
    }

    @Bean
    public CancellationPolicy nonRefundableCancellationPolicy() {
        return new NonRefundableCancellationPolicy();
    }
}
