package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Case- and whitespace-insensitive city match on the indexed search key.
 */
@Component
public class CitySearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        String city = Property.searchKey(criteria.city());
        return Optional.of((root, query, cb) -> cb.equal(SearchPaths.property(root).get(SearchPaths.SEARCH_CITY), city));
    }
}
