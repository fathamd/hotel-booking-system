package com.rupeek.hotel_booking_system.domain.exception;

/**
 * A booking request names a room type that is offered by a different property.
 */
public class RoomTypeNotInPropertyException extends DomainValidationException {

    public RoomTypeNotInPropertyException(Long roomTypeId, Long propertyId) {
        super("Room type " + roomTypeId + " does not belong to property " + propertyId);
    }
}
