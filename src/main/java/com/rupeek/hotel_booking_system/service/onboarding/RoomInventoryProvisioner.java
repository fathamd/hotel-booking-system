package com.rupeek.hotel_booking_system.service.onboarding;

import com.rupeek.hotel_booking_system.config.InventoryProperties;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Creates the per-night inventory rows of a room type, from today (in the property's time zone) through the
 * configured booking horizon. Every night starts with the room type's full inventory available.
 */
@Component
public class RoomInventoryProvisioner {

    private final RoomInventoryRepository roomInventoryRepository;
    private final InventoryProperties inventoryProperties;
    private final Clock clock;

    public RoomInventoryProvisioner(RoomInventoryRepository roomInventoryRepository,
                                    InventoryProperties inventoryProperties,
                                    Clock clock) {
        this.roomInventoryRepository = roomInventoryRepository;
        this.inventoryProperties = inventoryProperties;
        this.clock = clock;
    }

    /**
     * Provisions a newly onboarded room type.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(RoomType roomType) {
        LocalDate today = roomType.getProperty().today(clock);
        create(roomType, today, horizonEnd(today));
    }

    /**
     * Adds the nights that have come into the booking horizon since the room type was last provisioned, so the
     * horizon rolls forward day by day. Existing nights are never touched.
     *
     * @return the number of nights added
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int extendHorizon(RoomType roomType) {
        LocalDate today = roomType.getProperty().today(clock);
        LocalDate from = roomInventoryRepository.findLastStayDate(roomType.getId())
                .map(last -> last.plusDays(1))
                .filter(next -> !next.isBefore(today))
                .orElse(today);
        return create(roomType, from, horizonEnd(today));
    }

    private LocalDate horizonEnd(LocalDate today) {
        return today.plusDays(inventoryProperties.horizonDays());
    }

    private int create(RoomType roomType, LocalDate fromInclusive, LocalDate toExclusive) {
        if (!fromInclusive.isBefore(toExclusive)) {
            return 0;
        }
        List<RoomInventory> nights = fromInclusive.datesUntil(toExclusive)
                .map(night -> new RoomInventory(roomType, night))
                .toList();
        roomInventoryRepository.saveAll(nights);
        return nights.size();
    }
}
