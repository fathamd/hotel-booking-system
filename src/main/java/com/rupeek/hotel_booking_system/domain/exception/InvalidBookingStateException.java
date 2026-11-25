package com.rupeek.hotel_booking_system.domain.exception;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;

public class InvalidBookingStateException extends DomainException {

    public InvalidBookingStateException(BookingStatus current, BookingStatus target) {
        super("Booking cannot transition from " + current + " to " + target);
    }

    public InvalidBookingStateException(String message) {
        super(message);
    }
}
