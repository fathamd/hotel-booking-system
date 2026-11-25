package com.rupeek.hotel_booking_system.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.rupeek.hotel_booking_system.domain.enums.BookingStatus.CANCELLED;
import static com.rupeek.hotel_booking_system.domain.enums.BookingStatus.CONFIRMED;
import static com.rupeek.hotel_booking_system.domain.enums.BookingStatus.EXPIRED;
import static com.rupeek.hotel_booking_system.domain.enums.BookingStatus.PAYMENT_FAILED;
import static com.rupeek.hotel_booking_system.domain.enums.BookingStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

class BookingStatusTest {

    private static final Map<BookingStatus, Set<BookingStatus>> EXPECTED = Map.of(
            PENDING_PAYMENT, EnumSet.of(CONFIRMED, PAYMENT_FAILED, EXPIRED, CANCELLED),
            CONFIRMED, EnumSet.of(CANCELLED),
            PAYMENT_FAILED, EnumSet.noneOf(BookingStatus.class),
            EXPIRED, EnumSet.noneOf(BookingStatus.class),
            CANCELLED, EnumSet.noneOf(BookingStatus.class));

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void onlyDefinedTransitionsAreAllowed(BookingStatus from) {
        for (BookingStatus to : BookingStatus.values()) {
            assertThat(from.canTransitionTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(EXPECTED.get(from).contains(to));
        }
    }

    @Test
    void terminalStatesHaveNoTransitions() {
        assertThat(EnumSet.allOf(BookingStatus.class).stream().filter(BookingStatus::isTerminal))
                .containsExactlyInAnyOrder(PAYMENT_FAILED, EXPIRED, CANCELLED);
    }

    @Test
    void onlyPendingAndConfirmedHoldInventory() {
        assertThat(EnumSet.allOf(BookingStatus.class).stream().filter(BookingStatus::holdsInventory))
                .containsExactlyInAnyOrder(PENDING_PAYMENT, CONFIRMED);
        assertThat(BookingStatus.inventoryHoldingStatuses()).containsExactlyInAnyOrder(PENDING_PAYMENT, CONFIRMED);
    }
}
