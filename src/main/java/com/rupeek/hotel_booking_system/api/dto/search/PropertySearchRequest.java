package com.rupeek.hotel_booking_system.api.dto.search;

import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Query parameters of the property search. Cross-field rules (check-out after check-in, guests vs rooms,
 * minPrice not above maxPrice) are enforced by {@link SearchCriteria}.
 *
 * @param rooms     rooms required; defaults to 1
 * @param amenities every listed amenity must be offered, e.g. {@code amenities=WIFI,SPA}
 */
public record PropertySearchRequest(
        @NotBlank @Size(max = Location.CITY_MAX)
        String city,

        @Size(max = Location.LOCALITY_MAX)
        String locality,

        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate checkIn,

        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate checkOut,

        @NotNull @Min(1) @Max(SearchCriteria.MAX_GUESTS)
        Integer guests,

        @Min(1) @Max(SearchCriteria.MAX_ROOMS)
        Integer rooms,

        @DecimalMin("0.00") @Digits(integer = 10, fraction = MonetaryAmounts.SCALE)
        BigDecimal minPrice,

        @DecimalMin("0.00") @Digits(integer = 10, fraction = MonetaryAmounts.SCALE)
        BigDecimal maxPrice,

        Set<Amenity> amenities,

        @Min(Property.MIN_STAR_RATING) @Max(Property.MAX_STAR_RATING)
        Integer minStarRating) {
}
