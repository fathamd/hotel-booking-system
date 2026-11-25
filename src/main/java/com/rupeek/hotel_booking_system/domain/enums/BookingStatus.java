package com.rupeek.hotel_booking_system.domain.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Booking lifecycle:
 * <pre>
 * PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED | EXPIRED | CANCELLED
 * CONFIRMED       -> CANCELLED
 * PAYMENT_FAILED, EXPIRED, CANCELLED are terminal.
 * </pre>
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED;

    public Set<BookingStatus> allowedTransitions() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(CONFIRMED, PAYMENT_FAILED, EXPIRED, CANCELLED);
            case CONFIRMED -> EnumSet.of(CANCELLED);
            case PAYMENT_FAILED, EXPIRED, CANCELLED -> EnumSet.noneOf(BookingStatus.class);
        };
    }

    public boolean canTransitionTo(BookingStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /**
     * Whether a booking in this state occupies room inventory.
     */
    public boolean holdsInventory() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }

    public static Set<BookingStatus> inventoryHoldingStatuses() {
        return Arrays.stream(values())
                .filter(BookingStatus::holdsInventory)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BookingStatus.class)));
    }
}
