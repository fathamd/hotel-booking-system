package com.rupeek.hotel_booking_system.service.pricing;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;

import java.math.BigDecimal;

/**
 * Prices a stay. Search quotes and bookings both use this, so the price a guest sees is the price charged.
 * Seasonal, weekend or dynamic pricing can be introduced by providing another implementation.
 */
public interface PriceCalculator {

    /**
     * @return the total price for {@code rooms} rooms over every night of {@code stay}, at monetary scale
     */
    BigDecimal totalPrice(RoomType roomType, StayPeriod stay, int rooms);
}
