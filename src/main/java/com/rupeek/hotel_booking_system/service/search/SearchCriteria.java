package com.rupeek.hotel_booking_system.service.search;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, normalised search input shared by all search filters. Only {@code city}, {@code stay},
 * {@code guests} and {@code rooms} are mandatory; every other criterion is an optional filter.
 */
public record SearchCriteria(String city,
                             String locality,
                             StayPeriod stay,
                             int guests,
                             int rooms,
                             BigDecimal minPrice,
                             BigDecimal maxPrice,
                             Set<Amenity> amenities,
                             Integer minStarRating) {

    public static final int MAX_STAY_NIGHTS = StayPeriod.MAX_BOOKABLE_NIGHTS;
    public static final int MAX_ROOMS = 10;
    public static final int MAX_GUESTS = MAX_ROOMS * RoomType.MAX_GUESTS_PER_ROOM;

    public SearchCriteria {
        city = DomainPreconditions.requireText(city, "city", Location.CITY_MAX);
        locality = DomainPreconditions.optionalText(locality, "locality", Location.LOCALITY_MAX);
        DomainPreconditions.requireNonNull(stay, "stay");
        if (stay.numberOfNights() > MAX_STAY_NIGHTS) {
            throw new DomainValidationException("stay cannot exceed " + MAX_STAY_NIGHTS + " nights");
        }
        DomainPreconditions.requireInRange(rooms, 1, MAX_ROOMS, "rooms");
        validateGuests(guests, rooms);
        minPrice = minPrice == null ? null : MonetaryAmounts.requireNonNegative(minPrice, "minPrice");
        maxPrice = maxPrice == null ? null : MonetaryAmounts.requireNonNegative(maxPrice, "maxPrice");
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new DomainValidationException("minPrice cannot be greater than maxPrice");
        }
        if (minStarRating != null) {
            DomainPreconditions.requireInRange(minStarRating, Property.MIN_STAR_RATING, Property.MAX_STAR_RATING,
                    "minStarRating");
        }
        amenities = immutableAmenities(amenities);
    }

    public long nights() {
        return stay.numberOfNights();
    }

    private static void validateGuests(int guests, int rooms) {
        if (guests < 1) {
            throw new DomainValidationException("guests must be at least 1");
        }
        if (guests < rooms) {
            throw new DomainValidationException("guests (" + guests + ") cannot be fewer than rooms (" + rooms
                    + "); every room needs at least one guest");
        }
        if (guests > rooms * RoomType.MAX_GUESTS_PER_ROOM) {
            throw new DomainValidationException(guests + " guests cannot fit in " + rooms
                    + " room(s); at most " + RoomType.MAX_GUESTS_PER_ROOM + " guests per room are supported");
        }
    }

    private static Set<Amenity> immutableAmenities(Collection<Amenity> amenities) {
        if (amenities == null || amenities.isEmpty()) {
            return Collections.emptySet();
        }
        if (amenities.stream().anyMatch(Objects::isNull)) {
            throw new DomainValidationException("amenities cannot contain null values");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(amenities));
    }
}
