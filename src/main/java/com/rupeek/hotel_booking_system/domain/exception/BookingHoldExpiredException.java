package com.rupeek.hotel_booking_system.domain.exception;

public class BookingHoldExpiredException extends DomainException {

    public BookingHoldExpiredException(Long bookingId) {
        super("Inventory hold for booking " + bookingId + " has expired");
    }
}
