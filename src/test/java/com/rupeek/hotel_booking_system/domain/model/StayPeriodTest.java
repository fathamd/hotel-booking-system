package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StayPeriodTest {

    private static final LocalDate D1 = LocalDate.of(2026, 12, 20);

    @Test
    void rejectsCheckOutOnOrBeforeCheckIn() {
        assertThatThrownBy(() -> new StayPeriod(D1, D1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new StayPeriod(D1, D1.minusDays(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new StayPeriod(null, D1)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nightsExcludeCheckOutDate() {
        StayPeriod stay = new StayPeriod(D1, D1.plusDays(3));

        assertThat(stay.numberOfNights()).isEqualTo(3);
        assertThat(stay.nights()).containsExactly(D1, D1.plusDays(1), D1.plusDays(2));
        assertThat(stay.includesNight(D1.plusDays(3))).isFalse();
    }

    @Test
    void sameDayTurnoverDoesNotOverlap() {
        StayPeriod first = new StayPeriod(D1, D1.plusDays(2));
        StayPeriod next = new StayPeriod(D1.plusDays(2), D1.plusDays(4));

        assertThat(first.overlaps(next)).isFalse();
        assertThat(next.overlaps(first)).isFalse();
    }

    @Test
    void partialAndContainedRangesOverlap() {
        StayPeriod base = new StayPeriod(D1, D1.plusDays(5));

        assertThat(base.overlaps(new StayPeriod(D1.minusDays(1), D1.plusDays(1)))).isTrue();
        assertThat(base.overlaps(new StayPeriod(D1.plusDays(1), D1.plusDays(2)))).isTrue();
        assertThat(base.overlaps(new StayPeriod(D1.minusDays(2), D1.plusDays(9)))).isTrue();
    }

    @Test
    void checkInDateTimeUsesStandardCheckInTime() {
        assertThat(new StayPeriod(D1, D1.plusDays(1)).checkInDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 20, 14, 0));
    }
}
