package com.rupeek.hotel_booking_system.domain.exception;

public class BookingAlreadyCancelledException extends DomainException {

    public BookingAlreadyCancelledException(Long bookingId) {
        super("Booking " + bookingId + " is already cancelled");
    }
}
