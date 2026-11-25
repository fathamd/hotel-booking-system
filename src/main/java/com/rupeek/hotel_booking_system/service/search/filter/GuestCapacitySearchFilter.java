package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Keeps room types where the requested rooms can host all guests: {@code maxGuests * rooms >= guests}.
 * This mirrors {@link RoomType#canAccommodate(int, int)}; the lower bound (one guest per room) and the
 * room count are guaranteed by {@link SearchCriteria} and by the availability check respectively.
 */
@Component
public class GuestCapacitySearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        int rooms = criteria.rooms();
        int guests = criteria.guests();
        return Optional.of((root, query, cb) -> cb.greaterThanOrEqualTo(
                cb.prod(root.<Integer>get(SearchPaths.MAX_GUESTS), rooms), guests));
    }
}
