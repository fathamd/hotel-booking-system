package com.rupeek.hotel_booking_system.api.dto.onboarding;

import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;

import java.util.List;
import java.util.Set;

public record PropertyDetailsResponse(Long id,
                                      Long ownerId,
                                      String name,
                                      LocationResponse location,
                                      int starRating,
                                      Set<Amenity> amenities,
                                      CancellationPolicyType cancellationPolicy,
                                      String timeZone,
                                      List<RoomTypeResponse> roomTypes) {
}
