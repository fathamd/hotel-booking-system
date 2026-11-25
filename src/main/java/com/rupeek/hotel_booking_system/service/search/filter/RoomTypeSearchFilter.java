package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import org.springframework.data.jpa.domain.Specification;

import java.util.Optional;

/**
 * Extension point for property search. Every Spring bean implementing this interface is applied
 * automatically (combined with AND), so a new filter is added by adding a new bean; the search
 * service itself does not change.
 * Filters operate on {@link RoomType} so that property-level criteria (city, rating, amenities) and
 * room-level criteria (price, capacity) compose in one query. Use {@link SearchPaths#property} to reach
 * the property without creating duplicate joins.
 */
public interface RoomTypeSearchFilter {

    /**
     * @return the restriction to apply, or empty when the criterion was not supplied
     */
    Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria);
}
