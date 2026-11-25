package com.rupeek.hotel_booking_system.api.dto.onboarding;

import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Property;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * @param timeZone IANA time zone of the property, e.g. {@code Asia/Kolkata}; the configured default when omitted
 */
public record CreatePropertyRequest(
        @NotBlank @Size(max = Property.NAME_MAX)
        String name,

        @NotNull @Valid
        LocationRequest location,

        @NotNull @Min(Property.MIN_STAR_RATING) @Max(Property.MAX_STAR_RATING)
        Integer starRating,

        Set<@NotNull Amenity> amenities,

        @NotNull
        CancellationPolicyType cancellationPolicy,

        @Size(max = Property.TIME_ZONE_MAX)
        String timeZone) {

    public CreatePropertyRequest(String name, LocationRequest location, Integer starRating, Set<Amenity> amenities,
                                 CancellationPolicyType cancellationPolicy) {
        this(name, location, starRating, amenities, cancellationPolicy, null);
    }
}
