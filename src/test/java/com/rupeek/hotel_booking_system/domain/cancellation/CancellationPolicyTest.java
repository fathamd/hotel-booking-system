package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationPolicyTest {

    private static final Instant CHECK_IN_AT = Instant.parse("2026-12-20T14:00:00Z");
    private static final BigDecimal PAID = new BigDecimal("1000.00");

    private static CancellationContext cancelledBefore(Duration notice) {
        return new CancellationContext(PAID, CHECK_IN_AT, CHECK_IN_AT.minus(notice));
    }

    @ParameterizedTest(name = "flexible, {0}s notice -> {1}")
    @CsvSource({
            "86400, 1000.00",
            "86399, 0.00",
            "604800, 1000.00",
            "0, 0.00",
            "-3600, 0.00"
    })
    void flexibleRefundsFullyUntil24HoursBefore(long noticeSeconds, String expectedRefund) {
        RefundDecision decision = TieredCancellationPolicy.flexible().evaluate(cancelledBefore(Duration.ofSeconds(noticeSeconds)));

        assertThat(decision.refundAmount()).isEqualByComparingTo(expectedRefund);
    }

    @ParameterizedTest(name = "moderate, {0}h notice -> {1}")
    @CsvSource({
            "168, 1000.00",
            "167, 500.00",
            "48, 500.00",
            "47, 0.00"
    })
    void moderateHasTwoTiers(long noticeHours, String expectedRefund) {
        RefundDecision decision = TieredCancellationPolicy.moderate().evaluate(cancelledBefore(Duration.ofHours(noticeHours)));

        assertThat(decision.refundAmount()).isEqualByComparingTo(expectedRefund);
    }

    @Test
    void partialRefundIsRoundedHalfUp() {
        CancellationContext context = new CancellationContext(new BigDecimal("333.33"), CHECK_IN_AT, CHECK_IN_AT.minus(Duration.ofDays(3)));

        RefundDecision decision = TieredCancellationPolicy.moderate().evaluate(context);

        assertThat(decision.refundAmount()).isEqualByComparingTo("166.67").hasScaleOf(2);
        assertThat(decision.refundPercentage()).isEqualByComparingTo("50");
        assertThat(decision.isRefundable()).isTrue();
    }

    @Test
    void nonRefundableNeverRefunds() {
        RefundDecision decision = new NonRefundableCancellationPolicy().evaluate(cancelledBefore(Duration.ofDays(30)));

        assertThat(decision.isRefundable()).isFalse();
        assertThat(decision.refundAmount()).isEqualByComparingTo("0");
    }

    @Test
    void policiesReportTheirType() {
        assertThat(TieredCancellationPolicy.flexible().type()).isEqualTo(CancellationPolicyType.FLEXIBLE);
        assertThat(TieredCancellationPolicy.moderate().type()).isEqualTo(CancellationPolicyType.MODERATE);
        assertThat(new NonRefundableCancellationPolicy().type()).isEqualTo(CancellationPolicyType.NON_REFUNDABLE);
    }

    @Test
    void invalidTiersAreRejected() {
        assertThatThrownBy(() -> new RefundTier(Duration.ofHours(1), new BigDecimal("101")))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new TieredCancellationPolicy(CancellationPolicyType.FLEXIBLE, List.of()))
                .isInstanceOf(DomainValidationException.class);
    }
}
