package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.search.SearchCriteria;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps room types whose nightly price lies within the optional, inclusive {@code [minPrice, maxPrice]} range.
 */
@Component
public class PriceRangeSearchFilter implements RoomTypeSearchFilter {

    @Override
    public Optional<Specification<RoomType>> specificationFor(SearchCriteria criteria) {
        BigDecimal min = criteria.minPrice();
        BigDecimal max = criteria.maxPrice();
        if (min == null && max == null) {
            return Optional.empty();
        }
        return Optional.of((root, query, cb) -> {
            Expression<BigDecimal> price = root.get(SearchPaths.BASE_PRICE);
            List<Predicate> bounds = new ArrayList<>(2);
            if (min != null) {
                bounds.add(cb.greaterThanOrEqualTo(price, min));
            }
            if (max != null) {
                bounds.add(cb.lessThanOrEqualTo(price, max));
            }
            return cb.and(bounds.toArray(Predicate[]::new));
        });
    }
}
