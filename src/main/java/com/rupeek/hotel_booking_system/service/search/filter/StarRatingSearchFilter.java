package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Keeps properties rated at least {@code minStarRating} stars.
 */
@Component
public class StarRatingSearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        return Optional.ofNullable(criteria.minStarRating())
                .map(minStars -> (root, query, cb) -> cb.greaterThanOrEqualTo(
                        SearchPaths.property(root).<Integer>get(SearchPaths.STAR_RATING), minStars));
    }
}
