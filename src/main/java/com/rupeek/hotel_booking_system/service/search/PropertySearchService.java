package com.rupeek.hotel_booking_system.service.search;

import com.rupeek.hotel_booking_system.api.dto.search.AvailableRoomTypeResponse;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchRequest;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResponse;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResult;
import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.inventory.RoomAvailabilityCalculator;
import com.rupeek.hotel_booking_system.service.pricing.PriceCalculator;
import com.rupeek.hotel_booking_system.service.search.filter.RoomTypeSearchFilter;
import com.rupeek.hotel_booking_system.service.search.filter.SearchPaths;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds properties that have at least one room type matching every criterion and with enough rooms free
 * on every night of the stay.
 * The search runs a constant number of queries regardless of result size: one for candidate room types
 * (with their properties fetched), one aggregated availability query and one batched amenities load.
 */
@Service
public class PropertySearchService {

    private static final int DEFAULT_ROOMS = 1;
    private static final Comparator<PropertySearchResult> RESULT_ORDER =
            Comparator.comparing(PropertySearchResult::lowestPricePerNight)
                    .thenComparing(PropertySearchResult::starRating, Comparator.reverseOrder())
                    .thenComparing(PropertySearchResult::name)
                    .thenComparing(PropertySearchResult::propertyId);

    private final RoomTypeRepository roomTypeRepository;
    private final RoomAvailabilityCalculator availabilityCalculator;
    private final PriceCalculator priceCalculator;
    private final List<RoomTypeSearchFilter> filters;
    private final Clock clock;

    public PropertySearchService(RoomTypeRepository roomTypeRepository,
                                 RoomAvailabilityCalculator availabilityCalculator,
                                 PriceCalculator priceCalculator,
                                 List<RoomTypeSearchFilter> filters,
                                 Clock clock) {
        this.roomTypeRepository = roomTypeRepository;
        this.availabilityCalculator = availabilityCalculator;
        this.priceCalculator = priceCalculator;
        this.filters = List.copyOf(filters);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PropertySearchResponse search(PropertySearchRequest request) {
        SearchCriteria criteria = toCriteria(request);
        List<RoomType> candidates = roomTypeRepository.findAll(specificationFor(criteria),
                Sort.by(SearchPaths.BASE_PRICE, "id"));
        Map<Long, Integer> availability = availabilityCalculator.availableRooms(
                candidates.stream().map(RoomType::getId).toList(), criteria.stay());

        Map<Property, List<AvailableRoomTypeResponse>> offersByProperty = new LinkedHashMap<>();
        for (RoomType roomType : candidates) {
            int availableRooms = availability.getOrDefault(roomType.getId(), 0);
            if (availableRooms >= criteria.rooms()) {
                offersByProperty.computeIfAbsent(roomType.getProperty(), property -> new ArrayList<>())
                        .add(SearchResultMapper.toAvailableRoomType(roomType, availableRooms,
                                priceCalculator.totalPrice(roomType, criteria.stay(), criteria.rooms())));
            }
        }

        List<PropertySearchResult> results = offersByProperty.entrySet().stream()
                .map(entry -> SearchResultMapper.toResult(entry.getKey(), entry.getValue()))
                .sorted(RESULT_ORDER)
                .toList();
        StayPeriod stay = criteria.stay();
        return new PropertySearchResponse(stay.checkIn(), stay.checkOut(), criteria.nights(),
                criteria.guests(), criteria.rooms(), results.size(), results);
    }

    private SearchCriteria toCriteria(PropertySearchRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        StayPeriod stay = new StayPeriod(request.checkIn(), request.checkOut());
        stay.requireBookableFrom(LocalDate.now(clock));
        int guests = DomainPreconditions.requireNonNull(request.guests(), "guests");
        int rooms = Optional.ofNullable(request.rooms()).orElse(DEFAULT_ROOMS);
        return new SearchCriteria(request.city(), request.locality(), stay, guests, rooms,
                request.minPrice(), request.maxPrice(), request.amenities(), request.minStarRating());
    }

    private Specification<RoomType> specificationFor(SearchCriteria criteria) {
        Specification<RoomType> specification = fetchProperty();
        for (RoomTypeSearchFilter filter : filters) {
            Optional<Specification<RoomType>> restriction = filter.specificationFor(criteria);
            if (restriction.isPresent()) {
                specification = specification.and(restriction.get());
            }
        }
        return specification;
    }

    /**
     * Fetch-joins the property so that results need no per-row property lookups. Filters reuse this join.
     */
    private static Specification<RoomType> fetchProperty() {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch(SearchPaths.PROPERTY);
            }
            return cb.conjunction();
        };
    }
}
