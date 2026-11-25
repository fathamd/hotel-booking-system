package com.rupeek.hotel_booking_system.api.dto.onboarding;

import com.rupeek.hotel_booking_system.domain.model.Location;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LocationRequest(
        @NotBlank @Size(max = Location.ADDRESS_MAX)
        String addressLine,

        @NotBlank @Size(max = Location.LOCALITY_MAX)
        String locality,

        @NotBlank @Size(max = Location.CITY_MAX)
        String city) {
}
