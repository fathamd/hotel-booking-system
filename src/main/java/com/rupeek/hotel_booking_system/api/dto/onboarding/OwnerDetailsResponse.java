package com.rupeek.hotel_booking_system.api.dto.onboarding;

import java.util.List;

public record OwnerDetailsResponse(Long id,
                                   String name,
                                   String email,
                                   String phone,
                                   int propertyCount,
                                   List<PropertySummaryResponse> properties) {
}
