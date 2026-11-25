package com.rupeek.hotel_booking_system.domain;

import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.GuestDetails;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

public final class DomainFixtures {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 1, 10, 0);
    public static final LocalDate CHECK_IN = LocalDate.of(2026, 12, 20);
    public static final LocalDate CHECK_OUT = LocalDate.of(2026, 12, 23);
    public static final String FINGERPRINT = "f".repeat(Payment.FINGERPRINT_LENGTH);

    private DomainFixtures() {
    }

    public static OwnerAccount owner() {
        return new OwnerAccount("Sunrise Hotels", "owner@sunrise.example", "+91-9000000000");
    }

    public static Property property(OwnerAccount owner) {
        return property(owner, CancellationPolicyType.FLEXIBLE);
    }

    public static Property property(OwnerAccount owner, CancellationPolicyType cancellationPolicy) {
        return new Property(owner, "Sunrise Bengaluru",
                new Location("12 MG Road", "Indiranagar", "Bengaluru"),
                4, Set.of(Amenity.WIFI, Amenity.SWIMMING_POOL), cancellationPolicy);
    }

    public static RoomType roomType(Property property) {
        return new RoomType(property, "Deluxe King", "King bed, city view", 2, 5, new BigDecimal("2500.00"));
    }

    public static GuestDetails guest() {
        return new GuestDetails("Asha Rao", "asha@example.com", null);
    }

    public static StayPeriod stay() {
        return new StayPeriod(CHECK_IN, CHECK_OUT);
    }

    public static Booking pendingBooking() {
        return pendingBooking(roomType(property(owner())));
    }

    public static Booking pendingBooking(RoomType roomType) {
        return Booking.createPending(roomType, guest(), stay(), 2, 1, new BigDecimal("7500.00"), NOW.plusMinutes(15));
    }
}
