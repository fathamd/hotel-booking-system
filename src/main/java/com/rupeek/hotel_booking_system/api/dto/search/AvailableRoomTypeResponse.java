package com.rupeek.hotel_booking_system.api.dto.search;

import java.math.BigDecimal;

/**
 * @param availableRooms rooms free on every night of the stay
 * @param totalPrice     price for the requested rooms over all nights of the stay
 */
public record AvailableRoomTypeResponse(Long roomTypeId,
                                        String name,
                                        String description,
                                        int maxGuests,
                                        int availableRooms,
                                        BigDecimal pricePerNight,
                                        BigDecimal totalPrice) {
}
