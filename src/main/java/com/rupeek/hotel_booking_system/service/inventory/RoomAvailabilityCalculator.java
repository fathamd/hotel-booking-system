package com.rupeek.hotel_booking_system.service.inventory;

import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeAvailability;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes how many rooms of each room type can be sold for an entire stay.
 * A room type is sellable for a stay only if every night has an inventory row; the sellable count is the
 * minimum number of free rooms across those nights. Cancelled and failed bookings have already released
 * their rooms in the inventory rows, so they never reduce availability.
 * The figure is a non-locking snapshot for discovery; booking re-checks it under a row lock.
 */
@Component
public class RoomAvailabilityCalculator {

    private final RoomInventoryRepository roomInventoryRepository;

    public RoomAvailabilityCalculator(RoomInventoryRepository roomInventoryRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
    }

    /**
     * @return available rooms for the whole stay, keyed by room type id; contains every requested id
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> availableRooms(Collection<Long> roomTypeIds, StayPeriod stay) {
        Map<Long, Integer> availability = new HashMap<>();
        if (roomTypeIds.isEmpty()) {
            return availability;
        }
        roomTypeIds.forEach(id -> availability.put(id, 0));
        long nights = stay.numberOfNights();
        for (RoomTypeAvailability summary : roomInventoryRepository.summarizeAvailability(
                roomTypeIds, stay.checkIn(), stay.checkOut())) {
            availability.put(summary.roomTypeId(), sellableRooms(summary, nights));
        }
        return availability;
    }

    private static int sellableRooms(RoomTypeAvailability summary, long nights) {
        boolean everyNightCovered = summary.nightsCovered() == nights;
        return everyNightCovered ? Math.max(0, summary.minAvailableRooms()) : 0;
    }
}
