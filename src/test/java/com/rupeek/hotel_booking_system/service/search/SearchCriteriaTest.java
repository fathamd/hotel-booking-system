package com.rupeek.hotel_booking_system.service.search;

import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_IN;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCriteriaTest {

    private static final StayPeriod STAY = new StayPeriod(CHECK_IN, CHECK_OUT);

    @Test
    void normalizesOptionalValues() {
        SearchCriteria criteria = new SearchCriteria(" Bengaluru ", "  ", STAY, 2, 1,
                new BigDecimal("1000"), null, Set.of(Amenity.WIFI), null);

        assertThat(criteria.city()).isEqualTo("Bengaluru");
        assertThat(criteria.locality()).isNull();
        assertThat(criteria.minPrice()).isEqualByComparingTo("1000").hasScaleOf(2);
        assertThat(criteria.amenities()).containsExactly(Amenity.WIFI);
        assertThat(criteria.nights()).isEqualTo(3);
        assertThat(new SearchCriteria("Goa", null, STAY, 2, 1, null, null, null, null).amenities()).isEmpty();
    }

    @Test
    void rejectsInvalidGuestAndRoomCombinations() {
        assertInvalid(() -> criteria(0, 1), "guests must be at least 1");
        assertInvalid(() -> criteria(1, 2), "cannot be fewer than rooms");
        assertInvalid(() -> criteria(11, 1), "cannot fit");
        assertInvalid(() -> criteria(2, 0), "rooms");
        assertInvalid(() -> criteria(200, SearchCriteria.MAX_ROOMS + 1), "rooms");
    }

    @Test
    void rejectsTooLongStay() {
        StayPeriod tooLong = new StayPeriod(CHECK_IN, CHECK_IN.plusDays(SearchCriteria.MAX_STAY_NIGHTS + 1));

        assertInvalid(() -> new SearchCriteria("Goa", null, tooLong, 2, 1, null, null, null, null), "nights");
        assertThat(new SearchCriteria("Goa", null, new StayPeriod(CHECK_IN, CHECK_IN.plusDays(30)),
                2, 1, null, null, null, null).nights()).isEqualTo(30);
    }

    @Test
    void rejectsInvalidFilters() {
        assertInvalid(() -> new SearchCriteria(" ", null, STAY, 2, 1, null, null, null, null), "city");
        assertInvalid(() -> new SearchCriteria("Goa", null, STAY, 2, 1,
                new BigDecimal("3000"), new BigDecimal("2000"), null, null), "minPrice cannot be greater than maxPrice");
        assertInvalid(() -> new SearchCriteria("Goa", null, STAY, 2, 1,
                new BigDecimal("-1"), null, null, null), "minPrice");
        assertInvalid(() -> new SearchCriteria("Goa", null, STAY, 2, 1, null, null, null, 0), "minStarRating");
        assertInvalid(() -> new SearchCriteria("Goa", null, STAY, 2, 1, null, null, null, 6), "minStarRating");
        assertInvalid(() -> new SearchCriteria("Goa", null, STAY, 2, 1, null, null,
                new HashSet<>(Arrays.asList(Amenity.WIFI, null)), null), "amenities");
    }

    @Test
    void acceptsEqualMinAndMaxPrice() {
        SearchCriteria criteria = new SearchCriteria("Goa", null, STAY, 2, 1,
                new BigDecimal("2000"), new BigDecimal("2000.00"), null, null);

        assertThat(criteria.maxPrice()).isEqualByComparingTo(criteria.minPrice());
    }

    private static SearchCriteria criteria(int guests, int rooms) {
        return new SearchCriteria("Goa", null, STAY, guests, rooms, null, null, null, null);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(DomainValidationException.class).hasMessageContaining(message);
    }
}
