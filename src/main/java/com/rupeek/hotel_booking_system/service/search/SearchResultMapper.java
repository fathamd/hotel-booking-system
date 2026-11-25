package com.rupeek.hotel_booking_system.service.search;

import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationResponse;
import com.rupeek.hotel_booking_system.api.dto.search.AvailableRoomTypeResponse;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResult;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Builds search response DTOs. Must run inside the search transaction because amenities are lazy.
 */
final class SearchResultMapper {

    private SearchResultMapper() {
    }

    static PropertySearchResult toResult(Property property, List<AvailableRoomTypeResponse> roomTypes) {
        BigDecimal lowestPrice = roomTypes.stream()
                .map(AvailableRoomTypeResponse::pricePerNight)
                .min(BigDecimal::compareTo)
                .orElseThrow();
        Location location = property.getLocation();
        return new PropertySearchResult(property.getId(), property.getName(),
                new LocationResponse(location.addressLine(), location.locality(), location.city()),
                property.getStarRating(), sortedAmenities(property.getAmenities()),
                property.getCancellationPolicyType(), lowestPrice, roomTypes);
    }

    static AvailableRoomTypeResponse toAvailableRoomType(RoomType roomType, int availableRooms, BigDecimal totalPrice) {
        return new AvailableRoomTypeResponse(roomType.getId(), roomType.getName(), roomType.getDescription(),
                roomType.getMaxGuests(), availableRooms, roomType.getBasePrice(), totalPrice);
    }

    private static Set<Amenity> sortedAmenities(Set<Amenity> amenities) {
        return amenities.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(amenities));
    }
}
