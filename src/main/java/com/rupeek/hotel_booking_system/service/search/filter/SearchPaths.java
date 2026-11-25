package com.rupeek.hotel_booking_system.service.search.filter;

import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;

/**
 * Attribute names and join helpers shared by search filters.
 */
public final class SearchPaths {

    public static final String PROPERTY = "property";
    public static final String SEARCH_CITY = "searchCity";
    public static final String SEARCH_LOCALITY = "searchLocality";
    public static final String STAR_RATING = "starRating";
    public static final String AMENITIES = "amenities";
    public static final String BASE_PRICE = "basePrice";
    public static final String MAX_GUESTS = "maxGuests";

    private SearchPaths() {
    }

    /**
     * Returns the existing fetch or join of the room type's property, creating a join only if none exists,
     * so that all filters share a single SQL join.
     */
    @SuppressWarnings("unchecked")
    public static Join<RoomType, Property> property(Root<RoomType> root) {
        for (Fetch<RoomType, ?> fetch : root.getFetches()) {
            if (PROPERTY.equals(fetch.getAttribute().getName()) && fetch instanceof Join<?, ?> join) {
                return (Join<RoomType, Property>) join;
            }
        }
        for (Join<RoomType, ?> join : root.getJoins()) {
            if (PROPERTY.equals(join.getAttribute().getName())) {
                return (Join<RoomType, Property>) join;
            }
        }
        return root.join(PROPERTY);
    }
}
