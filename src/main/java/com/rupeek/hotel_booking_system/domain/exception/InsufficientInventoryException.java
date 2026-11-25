package com.rupeek.hotel_booking_system.domain.exception;

import java.time.LocalDate;
import java.util.List;

/**
 * Not enough rooms are free on at least one night of a stay, or some nights are not open for sale at all.
 */
public class InsufficientInventoryException extends DomainException {

    public InsufficientInventoryException(Long roomTypeId, LocalDate date, int requested, int available) {
        super("Room type " + roomTypeId + " has " + available + " room(s) available on " + date
                + " but " + requested + " requested");
    }

    public InsufficientInventoryException(Long roomTypeId, List<LocalDate> nightsNotOnSale) {
        super("Room type " + roomTypeId + " is not available for sale on " + nightsNotOnSale);
    }
}
