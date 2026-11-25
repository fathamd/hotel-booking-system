package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Persists small object graphs for repository tests.
 */
final class PersistenceTestData {

    private final TestEntityManager entityManager;

    PersistenceTestData(TestEntityManager entityManager) {
        this.entityManager = entityManager;
    }

    OwnerAccount owner(String email) {
        return entityManager.persist(new OwnerAccount("Owner " + email, email, null));
    }

    Property property(OwnerAccount owner, String name, String city, String locality, int stars) {
        return entityManager.persist(new Property(owner, name, new Location("1 Main Rd", locality, city),
                stars, Set.of(Amenity.WIFI), CancellationPolicyType.FLEXIBLE));
    }

    RoomType roomType(Property property, String name, String basePrice) {
        return entityManager.persist(new RoomType(property, name, null, 2, 5, new BigDecimal(basePrice)));
    }

    void inventory(RoomType roomType, LocalDate fromInclusive, LocalDate toExclusive) {
        fromInclusive.datesUntil(toExclusive).forEach(date -> entityManager.persist(new RoomInventory(roomType, date)));
    }

    Booking pendingBooking(RoomType roomType, LocalDate checkIn, LocalDate checkOut) {
        return entityManager.persist(Booking.createPending(roomType, DomainFixtures.guest(),
                new StayPeriod(checkIn, checkOut), 2, 1, new BigDecimal("1000.00"), DomainFixtures.NOW.plusMinutes(15)));
    }

    void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
