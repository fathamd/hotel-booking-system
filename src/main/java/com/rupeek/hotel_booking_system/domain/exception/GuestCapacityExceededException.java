package com.rupeek.hotel_booking_system.domain.exception;

/**
 * The requested number of guests cannot be hosted in the requested rooms of a room type.
 */
public class GuestCapacityExceededException extends DomainValidationException {

    public GuestCapacityExceededException(String message) {
        super(message);
    }
}
