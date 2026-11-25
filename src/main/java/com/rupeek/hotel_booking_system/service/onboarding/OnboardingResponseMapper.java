package com.rupeek.hotel_booking_system.service.onboarding;

import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertyDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertySummaryResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.RoomTypeResponse;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Converts onboarding entities to response DTOs. It must be called inside a transaction
 * because it reads lazy associations such as amenities.
 */
final class OnboardingResponseMapper {

    private OnboardingResponseMapper() {
    }

    static OwnerResponse toOwnerResponse(OwnerAccount owner) {
        return new OwnerResponse(owner.getId(), owner.getName(), owner.getEmail(), owner.getPhone());
    }

    static OwnerDetailsResponse toOwnerDetails(OwnerAccount owner, List<Property> properties) {
        List<PropertySummaryResponse> summaries = properties.stream()
                .map(OnboardingResponseMapper::toPropertySummary)
                .toList();
        return new OwnerDetailsResponse(owner.getId(), owner.getName(), owner.getEmail(), owner.getPhone(),
                summaries.size(), summaries);
    }

    static PropertySummaryResponse toPropertySummary(Property property) {
        return new PropertySummaryResponse(property.getId(), property.getOwner().getId(), property.getName(),
                toLocationResponse(property.getLocation()), property.getStarRating(),
                sortedAmenities(property.getAmenities()), property.getCancellationPolicyType(),
                property.getTimeZone().getId());
    }

    static PropertyDetailsResponse toPropertyDetails(Property property, List<RoomType> roomTypes) {
        List<RoomTypeResponse> roomTypeResponses = roomTypes.stream()
                .map(OnboardingResponseMapper::toRoomTypeResponse)
                .toList();
        return new PropertyDetailsResponse(property.getId(), property.getOwner().getId(), property.getName(),
                toLocationResponse(property.getLocation()), property.getStarRating(),
                sortedAmenities(property.getAmenities()), property.getCancellationPolicyType(),
                property.getTimeZone().getId(), roomTypeResponses);
    }

    static RoomTypeResponse toRoomTypeResponse(RoomType roomType) {
        return new RoomTypeResponse(roomType.getId(), roomType.getProperty().getId(), roomType.getName(),
                roomType.getDescription(), roomType.getMaxGuests(), roomType.getTotalInventory(),
                roomType.getBasePrice());
    }

    private static LocationResponse toLocationResponse(Location location) {
        return new LocationResponse(location.addressLine(), location.locality(), location.city());
    }

    private static Set<Amenity> sortedAmenities(Set<Amenity> amenities) {
        return amenities.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(amenities));
    }
}
