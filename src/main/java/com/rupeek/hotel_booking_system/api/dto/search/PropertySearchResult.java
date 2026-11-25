package com.rupeek.hotel_booking_system.api.dto.search;

import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationResponse;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record PropertySearchResult(Long propertyId,
                                   String name,
                                   LocationResponse location,
                                   int starRating,
                                   Set<Amenity> amenities,
                                   CancellationPolicyType cancellationPolicy,
                                   BigDecimal lowestPricePerNight,
                                   List<AvailableRoomTypeResponse> availableRoomTypes) {
}
