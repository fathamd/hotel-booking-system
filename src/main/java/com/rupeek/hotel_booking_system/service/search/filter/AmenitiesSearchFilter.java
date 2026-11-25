package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Keeps properties offering every requested amenity. Membership tests compile to sub-queries,
 * so the property row is never duplicated by joining its amenity collection.
 */
@Component
public class AmenitiesSearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        Set<Amenity> required = criteria.amenities();
        if (required.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((root, query, cb) -> {
            Join<RoomType, Property> property = SearchPaths.property(root);
            Expression<Set<Amenity>> offered = property.get(SearchPaths.AMENITIES);
            return cb.and(required.stream()
                    .map(amenity -> cb.isMember(amenity, offered))
                    .toArray(Predicate[]::new));
        });
    }
}
