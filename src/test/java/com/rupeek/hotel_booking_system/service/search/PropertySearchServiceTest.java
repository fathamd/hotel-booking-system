package com.rupeek.hotel_booking_system.service.search;

import com.rupeek.hotel_booking_system.api.dto.search.AvailableRoomTypeResponse;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchRequest;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResponse;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResult;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.service.inventory.RoomAvailabilityCalculator;
import com.rupeek.hotel_booking_system.service.pricing.PriceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_IN;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_OUT;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the search service with its real filters and availability calculator against H2, because the
 * filters are database specifications whose behaviour cannot be meaningfully verified with mocks.
 * The clock is fixed at {@code NOW} (2026-12-01); the searched stay is 2026-12-20 to 2026-12-23 (3 nights).
 */
@DataJpaTest
@Import(PropertySearchServiceTest.SearchTestConfiguration.class)
class PropertySearchServiceTest {

    @TestConfiguration
    @ComponentScan(basePackageClasses = {PropertySearchService.class, RoomAvailabilityCalculator.class,
            PriceCalculator.class})
    static class SearchTestConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }
    }

    @Autowired
    private PropertySearchService searchService;
    @Autowired
    private TestEntityManager entityManager;

    private OwnerAccount owner;

    @BeforeEach
    void setUp() {
        owner = entityManager.persist(new OwnerAccount("Sunrise Hotels", "owner@sunrise.example", null));
    }

    @Nested
    class Location_ {

        @Test
        void searchByCityIsCaseAndWhitespaceInsensitive() {
            standardRoom(property("Bengaluru One", "Bengaluru", "Indiranagar", 4));
            standardRoom(property("Bengaluru Two", "Bengaluru", "Koramangala", 3));
            standardRoom(property("Mumbai One", "Mumbai", "Bandra", 5));

            PropertySearchResponse response = search(request().city("  BENGALURU "));

            assertThat(names(response)).containsExactlyInAnyOrder("Bengaluru One", "Bengaluru Two");
        }

        @Test
        void searchByLocalityNarrowsTheCity() {
            standardRoom(property("Indiranagar Inn", "Bengaluru", "Indiranagar", 4));
            standardRoom(property("Koramangala Inn", "Bengaluru", "Koramangala", 4));
            standardRoom(property("Other City Indiranagar", "Mysuru", "Indiranagar", 4));

            PropertySearchResponse response = search(request().locality("indiranagar"));

            assertThat(names(response)).containsExactly("Indiranagar Inn");
        }

        @Test
        void returnsNoResultsForUnknownCity() {
            standardRoom(property("Bengaluru One", "Bengaluru", "Indiranagar", 4));

            assertThat(search(request().city("Chennai")).results()).isEmpty();
        }
    }

    @Nested
    class PriceRange {

        @Test
        void returnsOnlyRoomTypesWithinTheInclusiveRange() {
            Property mixed = property("Mixed", "Bengaluru", "Indiranagar", 4);
            roomType(mixed, "Budget", 2, 5, "1500");
            roomType(mixed, "Deluxe", 2, 5, "2500");
            roomType(mixed, "Suite", 2, 5, "3000");
            roomType(property("Luxury", "Bengaluru", "Indiranagar", 5), "Presidential", 2, 5, "4000");

            PropertySearchResponse response = search(request().minPrice("2500").maxPrice("3000"));

            assertThat(names(response)).containsExactly("Mixed");
            assertThat(roomTypeNames(response.results().getFirst())).containsExactly("Deluxe", "Suite");
            assertThat(response.results().getFirst().lowestPricePerNight()).isEqualByComparingTo("2500");
        }

        @Test
        void supportsOnlyMinimumOrOnlyMaximumPrice() {
            Property property = property("Mixed", "Bengaluru", "Indiranagar", 4);
            roomType(property, "Budget", 2, 5, "1500");
            roomType(property, "Suite", 2, 5, "4000");

            assertThat(roomTypeNames(search(request().minPrice("2000")).results().getFirst())).containsExactly("Suite");
            assertThat(roomTypeNames(search(request().maxPrice("2000")).results().getFirst())).containsExactly("Budget");
        }

        @Test
        void excludesPropertyWhenNoRoomTypeIsInRange() {
            standardRoom(property("Mid", "Bengaluru", "Indiranagar", 4));

            assertThat(search(request().maxPrice("1000")).results()).isEmpty();
        }
    }

    @Nested
    class Amenities {

        @Test
        void requiresEveryRequestedAmenity() {
            standardRoom(property("Resort", "Bengaluru", "Indiranagar", 5,
                    Amenity.WIFI, Amenity.SWIMMING_POOL, Amenity.SPA));
            standardRoom(property("Business", "Bengaluru", "Indiranagar", 4, Amenity.WIFI));
            standardRoom(property("Basic", "Bengaluru", "Indiranagar", 2));

            assertThat(names(search(request().amenities(Amenity.WIFI, Amenity.SWIMMING_POOL))))
                    .containsExactly("Resort");
            assertThat(names(search(request().amenities(Amenity.WIFI))))
                    .containsExactlyInAnyOrder("Resort", "Business");
        }

        @Test
        void returnsEachPropertyOnceAndListsItsAmenities() {
            Property resort = property("Resort", "Bengaluru", "Indiranagar", 5, Amenity.WIFI, Amenity.SPA);
            standardRoom(resort);
            roomType(resort, "Suite", 2, 5, "5000");

            PropertySearchResponse response = search(request().amenities(Amenity.WIFI, Amenity.SPA));

            assertThat(response.results()).singleElement().satisfies(result -> {
                assertThat(result.amenities()).containsExactly(Amenity.WIFI, Amenity.SPA);
                assertThat(result.availableRoomTypes()).hasSize(2);
            });
        }
    }

    @Nested
    class StarRating {

        @Test
        void returnsPropertiesRatedAtLeastTheMinimum() {
            standardRoom(property("Three", "Bengaluru", "Indiranagar", 3));
            standardRoom(property("Four", "Bengaluru", "Indiranagar", 4));
            standardRoom(property("Five", "Bengaluru", "Indiranagar", 5));

            assertThat(names(search(request().minStarRating(4)))).containsExactlyInAnyOrder("Four", "Five");
            assertThat(names(search(request().minStarRating(5)))).containsExactly("Five");
        }
    }

    @Nested
    class GuestCapacity {

        @Test
        void returnsOnlyRoomTypesThatFitAllGuestsInTheRequestedRooms() {
            Property hotel = property("Hotel", "Bengaluru", "Indiranagar", 4);
            roomType(hotel, "Standard", 2, 5, "2000");
            roomType(hotel, "Family", 4, 5, "3500");
            standardRoom(property("Couples Only", "Bengaluru", "Indiranagar", 4));

            PropertySearchResponse threeGuestsOneRoom = search(request().guests(3).rooms(1));
            assertThat(names(threeGuestsOneRoom)).containsExactly("Hotel");
            assertThat(roomTypeNames(threeGuestsOneRoom.results().getFirst())).containsExactly("Family");

            PropertySearchResponse fourGuestsTwoRooms = search(request().guests(4).rooms(2));
            assertThat(names(fourGuestsTwoRooms)).containsExactlyInAnyOrder("Hotel", "Couples Only");
        }

        @Test
        void returnsNothingWhenNoRoomTypeIsLargeEnough() {
            roomType(property("Hotel", "Bengaluru", "Indiranagar", 4), "Family", 4, 5, "3500");

            assertThat(search(request().guests(5).rooms(1)).results()).isEmpty();
        }

        @Test
        void rejectsInvalidGuestCounts() {
            assertThatThrownBy(() -> search(request().guests(0)))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("guests");
            assertThatThrownBy(() -> search(request().guests(1).rooms(2)))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("every room needs at least one guest");
            assertThatThrownBy(() -> search(request().guests(11).rooms(1)))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("cannot fit");
            assertThatThrownBy(() -> search(request().guests(null)))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class DateAvailability {

        @Test
        void availableRoomsIsTheMinimumFreeAcrossAllNights() {
            RoomType deluxe = standardRoom(property("Hotel", "Bengaluru", "Indiranagar", 4));
            reserve(deluxe, CHECK_IN.plusDays(1), 3);
            reserve(deluxe, CHECK_IN.plusDays(2), 1);

            AvailableRoomTypeResponse offer = singleOffer(search(request()));

            assertThat(offer.availableRooms()).isEqualTo(2);
        }

        @Test
        void excludesRoomTypeWithFewerFreeRoomsThanRequestedOnAnyNight() {
            RoomType deluxe = standardRoom(property("Hotel", "Bengaluru", "Indiranagar", 4));
            reserve(deluxe, CHECK_IN.plusDays(2), 3);

            assertThat(search(request().guests(2).rooms(2)).results()).hasSize(1);
            assertThat(search(request().guests(3).rooms(3)).results()).isEmpty();
        }

        @Test
        void bookingsOutsideTheStayDoNotReduceAvailability() {
            RoomType deluxe = standardRoom(property("Hotel", "Bengaluru", "Indiranagar", 4));
            reserve(deluxe, CHECK_IN.minusDays(1), 5);
            reserve(deluxe, CHECK_OUT, 5);

            assertThat(singleOffer(search(request())).availableRooms()).isEqualTo(5);
        }

        @Test
        void releasedRoomsBecomeAvailableAgain() {
            RoomType deluxe = standardRoom(property("Hotel", "Bengaluru", "Indiranagar", 4));
            reserve(deluxe, CHECK_IN, 5);
            assertThat(search(request()).results()).isEmpty();

            inventoryRow(deluxe, CHECK_IN).release(5);
            entityManager.flush();

            assertThat(singleOffer(search(request())).availableRooms()).isEqualTo(5);
        }

        @Test
        void excludesRoomTypeWhenAnyNightHasNoInventoryRow() {
            Property hotel = property("Hotel", "Bengaluru", "Indiranagar", 4);
            RoomType partial = roomTypeWithoutInventory(hotel, "Partial", 2, 5, "2000");
            inventory(partial, CHECK_IN, CHECK_OUT.minusDays(1));

            assertThat(search(request()).results()).isEmpty();
        }

        @Test
        void calculatesStayPriceForAllNightsAndRooms() {
            roomType(property("Hotel", "Bengaluru", "Indiranagar", 4), "Deluxe", 2, 5, "2500");

            PropertySearchResponse response = search(request().guests(4).rooms(2));

            assertThat(response.nights()).isEqualTo(3);
            AvailableRoomTypeResponse offer = singleOffer(response);
            assertThat(offer.pricePerNight()).isEqualByComparingTo("2500.00");
            assertThat(offer.totalPrice()).isEqualByComparingTo("15000.00").hasScaleOf(2);
        }

        @Test
        void rejectsInvalidDateRanges() {
            assertThatThrownBy(() -> search(request().checkIn(CHECK_OUT).checkOut(CHECK_IN)))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("checkOut must be after checkIn");
            assertThatThrownBy(() -> search(request().checkIn(CHECK_IN).checkOut(CHECK_IN)))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> search(request().checkIn(NOW.toLocalDate().minusDays(1))))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("past");
            assertThatThrownBy(() -> search(request().checkOut(CHECK_IN.plusDays(SearchCriteria.MAX_STAY_NIGHTS + 1))))
                    .isInstanceOf(DomainValidationException.class).hasMessageContaining("nights");
        }

        @Test
        void allowsCheckInToday() {
            RoomType deluxe = roomTypeWithoutInventory(property("Hotel", "Bengaluru", "Indiranagar", 4),
                    "Deluxe", 2, 5, "2500");
            LocalDate today = NOW.toLocalDate();
            inventory(deluxe, today, today.plusDays(2));

            assertThat(search(request().checkIn(today).checkOut(today.plusDays(2))).results()).hasSize(1);
        }
    }

    @Nested
    class NoAvailableInventory {

        @Test
        void excludesPropertyFullyBookedOnOneNight() {
            RoomType deluxe = standardRoom(property("Sold Out", "Bengaluru", "Indiranagar", 4));
            reserve(deluxe, CHECK_IN.plusDays(1), 5);
            standardRoom(property("Open", "Bengaluru", "Indiranagar", 4));

            assertThat(names(search(request()))).containsExactly("Open");
        }

        @Test
        void excludesRoomTypeWithoutAnyInventory() {
            roomTypeWithoutInventory(property("Not Provisioned", "Bengaluru", "Indiranagar", 4), "Deluxe", 2, 5, "2500");

            PropertySearchResponse response = search(request());

            assertThat(response.results()).isEmpty();
            assertThat(response.resultCount()).isZero();
        }

        @Test
        void excludesPropertyWithoutRoomTypes() {
            property("Empty Shell", "Bengaluru", "Indiranagar", 4);

            assertThat(search(request()).results()).isEmpty();
        }

        @Test
        void keepsOnlyTheRoomTypesThatAreStillAvailable() {
            Property hotel = property("Hotel", "Bengaluru", "Indiranagar", 4);
            RoomType soldOut = roomType(hotel, "Sold Out", 2, 5, "1000");
            roomType(hotel, "Available", 2, 5, "3000");
            reserve(soldOut, CHECK_IN, 5);

            PropertySearchResult result = search(request()).results().getFirst();

            assertThat(roomTypeNames(result)).containsExactly("Available");
            assertThat(result.lowestPricePerNight()).isEqualByComparingTo("3000");
        }
    }

    @Nested
    class CombinedFilters {

        @Test
        void appliesAllFiltersTogetherAndOrdersByLowestPrice() {
            Property match = property("Match", "Bengaluru", "Indiranagar", 5, Amenity.WIFI, Amenity.GYM);
            roomType(match, "Deluxe", 3, 5, "3000");
            Property cheaperMatch = property("Cheaper Match", "Bengaluru", "Indiranagar", 4, Amenity.WIFI, Amenity.GYM);
            roomType(cheaperMatch, "Deluxe", 3, 5, "2000");
            roomType(property("Wrong Locality", "Bengaluru", "Whitefield", 5, Amenity.WIFI, Amenity.GYM), "D", 3, 5, "2000");
            roomType(property("Too Few Stars", "Bengaluru", "Indiranagar", 3, Amenity.WIFI, Amenity.GYM), "D", 3, 5, "2000");
            roomType(property("No Gym", "Bengaluru", "Indiranagar", 5, Amenity.WIFI), "D", 3, 5, "2000");
            roomType(property("Too Small", "Bengaluru", "Indiranagar", 5, Amenity.WIFI, Amenity.GYM), "D", 2, 5, "2000");
            roomType(property("Too Pricey", "Bengaluru", "Indiranagar", 5, Amenity.WIFI, Amenity.GYM), "D", 3, 5, "9000");

            PropertySearchResponse response = search(request().locality("Indiranagar").guests(3).minStarRating(4)
                    .amenities(Amenity.GYM).minPrice("1000").maxPrice("5000"));

            assertThat(names(response)).containsExactly("Cheaper Match", "Match");
        }
    }

    private PropertySearchResponse search(SearchRequestBuilder builder) {
        entityManager.flush();
        entityManager.clear();
        return searchService.search(builder.build());
    }

    private Property property(String name, String city, String locality, int stars, Amenity... amenities) {
        Set<Amenity> amenitySet = amenities.length == 0 ? Set.of() : EnumSet.copyOf(Arrays.asList(amenities));
        return entityManager.persist(new Property(owner, name, new Location("1 Main Rd", locality, city),
                stars, amenitySet, CancellationPolicyType.FLEXIBLE));
    }

    private RoomType standardRoom(Property property) {
        return roomType(property, "Deluxe", 2, 5, "2500");
    }

    private RoomType roomType(Property property, String name, int maxGuests, int inventory, String price) {
        RoomType roomType = roomTypeWithoutInventory(property, name, maxGuests, inventory, price);
        inventory(roomType, CHECK_IN.minusDays(5), CHECK_OUT.plusDays(5));
        return roomType;
    }

    private RoomType roomTypeWithoutInventory(Property property, String name, int maxGuests, int inventory, String price) {
        return entityManager.persist(new RoomType(property, name, null, maxGuests, inventory, new BigDecimal(price)));
    }

    private void inventory(RoomType roomType, LocalDate fromInclusive, LocalDate toExclusive) {
        fromInclusive.datesUntil(toExclusive).forEach(night -> entityManager.persist(new RoomInventory(roomType, night)));
    }

    private void reserve(RoomType roomType, LocalDate night, int rooms) {
        inventoryRow(roomType, night).reserve(rooms);
        entityManager.flush();
    }

    private RoomInventory inventoryRow(RoomType roomType, LocalDate night) {
        return entityManager.getEntityManager()
                .createQuery("select i from RoomInventory i where i.roomType.id = :id and i.stayDate = :night",
                        RoomInventory.class)
                .setParameter("id", roomType.getId())
                .setParameter("night", night)
                .getSingleResult();
    }

    private static AvailableRoomTypeResponse singleOffer(PropertySearchResponse response) {
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().availableRoomTypes()).hasSize(1);
        return response.results().getFirst().availableRoomTypes().getFirst();
    }

    private static java.util.List<String> names(PropertySearchResponse response) {
        return response.results().stream().map(PropertySearchResult::name).toList();
    }

    private static java.util.List<String> roomTypeNames(PropertySearchResult result) {
        return result.availableRoomTypes().stream().map(AvailableRoomTypeResponse::name).toList();
    }

    private static SearchRequestBuilder request() {
        return new SearchRequestBuilder();
    }

    private static final class SearchRequestBuilder {
        private String city = "Bengaluru";
        private String locality;
        private LocalDate checkIn = CHECK_IN;
        private LocalDate checkOut = CHECK_OUT;
        private Integer guests = 2;
        private Integer rooms;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private Set<Amenity> amenities;
        private Integer minStarRating;

        SearchRequestBuilder city(String value) { city = value; return this; }
        SearchRequestBuilder locality(String value) { locality = value; return this; }
        SearchRequestBuilder checkIn(LocalDate value) { checkIn = value; return this; }
        SearchRequestBuilder checkOut(LocalDate value) { checkOut = value; return this; }
        SearchRequestBuilder guests(Integer value) { guests = value; return this; }
        SearchRequestBuilder rooms(Integer value) { rooms = value; return this; }
        SearchRequestBuilder minPrice(String value) { minPrice = new BigDecimal(value); return this; }
        SearchRequestBuilder maxPrice(String value) { maxPrice = new BigDecimal(value); return this; }
        SearchRequestBuilder amenities(Amenity... values) { amenities = Set.of(values); return this; }
        SearchRequestBuilder minStarRating(Integer value) { minStarRating = value; return this; }

        PropertySearchRequest build() {
            return new PropertySearchRequest(city, locality, checkIn, checkOut, guests, rooms,
                    minPrice, maxPrice, amenities, minStarRating);
        }
    }
}
