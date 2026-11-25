package com.rupeek.hotel_booking_system.domain.exception;

/**
 * Base type for all business rule violations raised by the domain layer.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
