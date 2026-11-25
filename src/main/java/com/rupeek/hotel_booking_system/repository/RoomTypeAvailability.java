package com.rupeek.hotel_booking_system.repository;

/**
 * Per-room-type inventory summary over a date range.
 *
 * @param nightsCovered      number of nights in the range that have an inventory row
 * @param minAvailableRooms  fewest rooms free on any covered night
 */
public record RoomTypeAvailability(Long roomTypeId, Long nightsCovered, Integer minAvailableRooms) {
}
