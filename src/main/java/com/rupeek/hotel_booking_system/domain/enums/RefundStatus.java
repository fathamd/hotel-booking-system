package com.rupeek.hotel_booking_system.domain.enums;

public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
