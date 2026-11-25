package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LocalitySearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        return Optional.ofNullable(criteria.locality())
                .map(Property::searchKey)
                .map(locality -> (root, query, cb) ->
                        cb.equal(SearchPaths.property(root).get(SearchPaths.SEARCH_LOCALITY), locality));
    }
}
