package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Physical location of a property. City and locality are first-class fields because search filters on them.
 */
@Embeddable
public record Location(
        @NotBlank @Size(max = Location.ADDRESS_MAX) @Column(name = "address_line", nullable = false, length = ADDRESS_MAX)
        String addressLine,
        @NotBlank @Size(max = Location.LOCALITY_MAX) @Column(name = "locality", nullable = false, length = LOCALITY_MAX)
        String locality,
        @NotBlank @Size(max = Location.CITY_MAX) @Column(name = "city", nullable = false, length = CITY_MAX)
        String city) {

    public static final int ADDRESS_MAX = 255;
    public static final int LOCALITY_MAX = 100;
    public static final int CITY_MAX = 100;

    public Location {
        addressLine = DomainPreconditions.requireText(addressLine, "addressLine", ADDRESS_MAX);
        locality = DomainPreconditions.requireText(locality, "locality", LOCALITY_MAX);
        city = DomainPreconditions.requireText(city, "city", CITY_MAX);
    }

    public boolean isInCity(String candidateCity) {
        return candidateCity != null && city.equalsIgnoreCase(candidateCity.strip());
    }
}
