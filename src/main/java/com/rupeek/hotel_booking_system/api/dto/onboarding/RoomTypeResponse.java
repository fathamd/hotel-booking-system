package com.rupeek.hotel_booking_system.api.dto.onboarding;

import java.math.BigDecimal;

public record RoomTypeResponse(Long id,
                               Long propertyId,
                               String name,
                               String description,
                               int maxGuests,
                               int totalInventory,
                               BigDecimal basePrice) {
}
