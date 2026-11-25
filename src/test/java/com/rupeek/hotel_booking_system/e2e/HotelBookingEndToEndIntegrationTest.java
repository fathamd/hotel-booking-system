package com.rupeek.hotel_booking_system.e2e;

import com.jayway.jsonpath.JsonPath;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.payment.gateway.MockPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end business flows driven exclusively through the HTTP API (MockMvc), from onboarding to cancellation.
 * <p>
 * Determinism and isolation:
 * <ul>
 *     <li>a dedicated in-memory H2 database ({@code e2e_flows}) shared by no other test context;</li>
 *     <li>a fixed {@link Clock} at {@link #NOW}, so hold expiry and refund windows never depend on wall time;</li>
 *     <li>the in-process {@link MockPaymentGateway} with deterministic approve/decline instruments;</li>
 *     <li>every test onboards its own owner and properties in a unique city, so searches and inventory
 *     never see data created by another test.</li>
 * </ul>
 * Every test starts from the same catalogue (see {@link #onboardCatalogue()}):
 * <pre>
 * Riverside Grand  4*  Indiranagar  WIFI, SWIMMING_POOL, GYM  MODERATE
 *     Deluxe   2 guests  3 rooms  4000/night
 *     Suite    4 guests  2 rooms  9000/night
 * Garden Inn       3*  Koramangala  WIFI, PARKING             FLEXIBLE
 *     Standard 2 guests  5 rooms  2500/night
 *     Family   4 guests  1 room   6000/night
 * </pre>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:e2e_flows;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "hotel.inventory.horizon-days=30"
})
class HotelBookingEndToEndIntegrationTest {

    static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
    static final LocalDate NOW_DATE = LocalDate.parse("2027-03-01");
    /** Check-in is 4 days 4 hours after {@link #NOW} (check-in time 14:00): the MODERATE 50% window. */
    static final LocalDate CHECK_IN = LocalDate.parse("2027-03-05");
    static final LocalDate CHECK_OUT = LocalDate.parse("2027-03-07");
    static final int NIGHTS = 2;

    private static final String CARD_OK = card("4111111111111111");
    private static final String UPI_OK = """
            {"method": "UPI", "upi": {"vpa": "asha@okbank"}}""";
    private static final String WALLET_OK = wallet("9876543210");

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private MockPaymentGateway gateway;

    private MockMvc mockMvc;
    private Catalogue catalogue;

    private record Catalogue(String city, long ownerId,
                             long riverside, long deluxe, long suite,
                             long garden, long standard, long family) {
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        catalogue = onboardCatalogue();
    }

    @Nested
    class Flow1_PropertyOnboarding {

        @Test
        void ownerManagesBothPropertiesAndEachPropertyItsRoomTypes() throws Exception {
            mockMvc.perform(get("/api/v1/owners/{id}", catalogue.ownerId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Sunrise Hospitality"))
                    .andExpect(jsonPath("$.propertyCount").value(2))
                    .andExpect(jsonPath("$.properties[*].id").value(containsInAnyOrder(
                            (int) catalogue.riverside(), (int) catalogue.garden())))
                    .andExpect(jsonPath("$.properties[*].name").value(containsInAnyOrder("Riverside Grand", "Garden Inn")))
                    .andExpect(jsonPath("$.properties[*].ownerId").value(everyItem(is((int) catalogue.ownerId()))));

            mockMvc.perform(get("/api/v1/properties/{id}", catalogue.riverside()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerId").value(catalogue.ownerId()))
                    .andExpect(jsonPath("$.location.city").value(catalogue.city()))
                    .andExpect(jsonPath("$.location.locality").value("Indiranagar"))
                    .andExpect(jsonPath("$.starRating").value(4))
                    .andExpect(jsonPath("$.amenities").value(containsInAnyOrder("WIFI", "SWIMMING_POOL", "GYM")))
                    .andExpect(jsonPath("$.cancellationPolicy").value("MODERATE"))
                    .andExpect(jsonPath("$.roomTypes", hasSize(2)))
                    .andExpect(jsonPath("$.roomTypes[*].name").value(containsInAnyOrder("Deluxe", "Suite")))
                    .andExpect(jsonPath("$.roomTypes[*].propertyId").value(everyItem(is((int) catalogue.riverside()))));

            mockMvc.perform(get("/api/v1/properties/{id}", catalogue.garden()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerId").value(catalogue.ownerId()))
                    .andExpect(jsonPath("$.roomTypes", hasSize(2)))
                    .andExpect(jsonPath("$.roomTypes[?(@.name == 'Standard')].totalInventory").value(5))
                    .andExpect(jsonPath("$.roomTypes[?(@.name == 'Standard')].maxGuests").value(2))
                    .andExpect(jsonPath("$.roomTypes[?(@.name == 'Family')].basePrice").value(6000.00));
        }

        @Test
        void anotherOwnersPropertiesAreNotAttachedToThisOwner() throws Exception {
            long otherOwner = createOwner("Other Owner");
            addProperty(otherOwner, "Other Hotel", "Whitefield", 2, "[]", "FLEXIBLE");

            mockMvc.perform(get("/api/v1/owners/{id}", otherOwner))
                    .andExpect(jsonPath("$.propertyCount").value(1))
                    .andExpect(jsonPath("$.properties[0].name").value("Other Hotel"));
            mockMvc.perform(get("/api/v1/owners/{id}", catalogue.ownerId()))
                    .andExpect(jsonPath("$.propertyCount").value(2));
        }

        @Test
        void standalonePropertyIsAnOwnerWithExactlyOneProperty() throws Exception {
            mockMvc.perform(postJson("/api/v1/properties/standalone", """
                            {"owner": {"name": "Solo Stay", "email": "%s"},
                             "property": {"name": "Solo Stay Homestay",
                                          "location": {"addressLine": "7 Lake Rd", "locality": "Jayanagar", "city": "%s"},
                                          "starRating": 2, "cancellationPolicy": "NON_REFUNDABLE"}}
                            """.formatted(uniqueEmail(), catalogue.city())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", startsWith("/api/v1/owners/")))
                    .andExpect(jsonPath("$.propertyCount").value(1))
                    .andExpect(jsonPath("$.properties[0].name").value("Solo Stay Homestay"));
        }

        @Test
        void invalidOnboardingRequestsAreRejected() throws Exception {
            mockMvc.perform(postJson("/api/v1/owners/{id}/properties", """
                            {"name": "Bad Stars", "location": {"addressLine": "1 Rd", "locality": "X", "city": "Y"},
                             "starRating": 6, "cancellationPolicy": "FLEXIBLE"}
                            """, catalogue.ownerId()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(postJson("/api/v1/properties/{id}/room-types", """
                            {"name": "Broken", "maxGuests": 0, "totalInventory": 0, "basePrice": 0}
                            """, catalogue.riverside()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(postJson("/api/v1/owners/{id}/properties", """
                            {"name": "Orphan", "location": {"addressLine": "1 Rd", "locality": "X", "city": "Y"},
                             "starRating": 3, "cancellationPolicy": "FLEXIBLE"}
                            """, 999_999))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/properties/{id}", catalogue.riverside()))
                    .andExpect(jsonPath("$.roomTypes", hasSize(2)));
        }
    }

    @Nested
    class Flow2_PropertyDiscovery {

        @Test
        void searchByCityAndDatesReturnsEveryPropertyWithItsAvailableRoomTypes() throws Exception {
            mockMvc.perform(search(2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checkIn").value(CHECK_IN.toString()))
                    .andExpect(jsonPath("$.checkOut").value(CHECK_OUT.toString()))
                    .andExpect(jsonPath("$.nights").value(NIGHTS))
                    .andExpect(jsonPath("$.resultCount").value(2))
                    .andExpect(jsonPath("$.results[0].propertyId").value(catalogue.garden()))
                    .andExpect(jsonPath("$.results[0].lowestPricePerNight").value(2500.00))
                    .andExpect(jsonPath("$.results[0].availableRoomTypes[*].name").value(containsInAnyOrder("Standard", "Family")))
                    .andExpect(jsonPath("$.results[1].propertyId").value(catalogue.riverside()))
                    .andExpect(jsonPath("$.results[1].starRating").value(4))
                    .andExpect(jsonPath("$.results[1].availableRoomTypes[*].name").value(containsInAnyOrder("Deluxe", "Suite")));

            assertThat(availability(search(2))).containsExactlyInAnyOrderEntriesOf(Map.of(
                    catalogue.deluxe(), 3, catalogue.suite(), 2, catalogue.standard(), 5, catalogue.family(), 1));

            String body = body(mockMvc.perform(search(2)));
            assertThat(decimal(body, roomTypePath(catalogue.standard(), "pricePerNight"))).isEqualByComparingTo("2500");
            assertThat(decimal(body, roomTypePath(catalogue.standard(), "totalPrice"))).isEqualByComparingTo("5000");
            assertThat(decimal(body, roomTypePath(catalogue.suite(), "totalPrice"))).isEqualByComparingTo("18000");
        }

        @Test
        void localityAndGuestCountNarrowTheResults() throws Exception {
            assertThat(availability(search(2).param("locality", "koramangala")).keySet())
                    .containsExactlyInAnyOrder(catalogue.standard(), catalogue.family());
            assertThat(availability(search(3)).keySet())
                    .containsExactlyInAnyOrder(catalogue.suite(), catalogue.family());
            assertThat(availability(search(3).param("rooms", "2")).keySet())
                    .containsExactlyInAnyOrder(catalogue.deluxe(), catalogue.suite(), catalogue.standard());
        }

        @Test
        void priceFilterIsAnInclusiveRangeOnTheNightlyPrice() throws Exception {
            assertThat(availability(search(2).param("maxPrice", "4000")).keySet())
                    .containsExactlyInAnyOrder(catalogue.deluxe(), catalogue.standard());
            assertThat(availability(search(2).param("minPrice", "6000").param("maxPrice", "9000")).keySet())
                    .containsExactlyInAnyOrder(catalogue.family(), catalogue.suite());
            assertThat(availability(search(2).param("minPrice", "9000.01"))).isEmpty();
            mockMvc.perform(search(2).param("minPrice", "5000").param("maxPrice", "1000"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void amenitiesFilterRequiresEveryRequestedAmenity() throws Exception {
            mockMvc.perform(search(2).param("amenities", "SWIMMING_POOL"))
                    .andExpect(jsonPath("$.resultCount").value(1))
                    .andExpect(jsonPath("$.results[0].propertyId").value(catalogue.riverside()));
            mockMvc.perform(search(2).param("amenities", "WIFI,PARKING"))
                    .andExpect(jsonPath("$.resultCount").value(1))
                    .andExpect(jsonPath("$.results[0].propertyId").value(catalogue.garden()));
            mockMvc.perform(search(2).param("amenities", "WIFI"))
                    .andExpect(jsonPath("$.resultCount").value(2));
            mockMvc.perform(search(2).param("amenities", "PARKING,GYM"))
                    .andExpect(jsonPath("$.resultCount").value(0));
        }

        @Test
        void starRatingFilterIsAMinimum() throws Exception {
            mockMvc.perform(search(2).param("minStarRating", "3"))
                    .andExpect(jsonPath("$.resultCount").value(2));
            mockMvc.perform(search(2).param("minStarRating", "4"))
                    .andExpect(jsonPath("$.resultCount").value(1))
                    .andExpect(jsonPath("$.results[0].propertyId").value(catalogue.riverside()));
            mockMvc.perform(search(2).param("minStarRating", "5"))
                    .andExpect(jsonPath("$.resultCount").value(0));
        }

        @Test
        void filtersCombine() throws Exception {
            assertThat(availability(search(2).param("amenities", "WIFI").param("minStarRating", "3")
                    .param("maxPrice", "3000")).keySet())
                    .containsExactly(catalogue.standard());
        }

        @Test
        void invalidSearchesAreRejected() throws Exception {
            mockMvc.perform(get("/api/v1/properties/search").param("city", catalogue.city())
                            .param("checkIn", CHECK_OUT.toString()).param("checkOut", CHECK_IN.toString())
                            .param("guests", "2"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(search(0)).andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Flow3_SuccessfulBooking {

        @Test
        void paymentConfirmsThePendingBookingAndTheRoomsStayConsumed() throws Exception {
            ResultActions created = book(catalogue.riverside(), catalogue.deluxe(), 4, 2);
            long bookingId = id(created);
            created.andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/api/v1/bookings/" + bookingId)))
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.nights").value(NIGHTS))
                    .andExpect(jsonPath("$.rooms").value(2))
                    .andExpect(jsonPath("$.guests").value(4))
                    .andExpect(jsonPath("$.totalPrice").value(16000.00))
                    .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty())
                    .andExpect(jsonPath("$.confirmedAt").value(nullValue()));

            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(2);
            assertThat(availability(search(2)).get(catalogue.deluxe())).isEqualTo(1);

            int chargesBefore = gateway.chargeCount();
            String key = "pay-" + UUID.randomUUID();
            pay(bookingId, key, CARD_OK)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "false"))
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.method").value("CARD"))
                    .andExpect(jsonPath("$.amount").value(16000.00))
                    .andExpect(jsonPath("$.transactionReference").value(startsWith("MOCK-CARD-")));

            mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
            mockMvc.perform(get("/api/v1/properties/{id}/bookings", catalogue.riverside()).param("status", "CONFIRMED"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(bookingId));

            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(2);
            assertThat(availability(search(2)).get(catalogue.deluxe())).isEqualTo(1);
            assertThat(availability(search(2).param("rooms", "2"))).doesNotContainKey(catalogue.deluxe());
            book(catalogue.riverside(), catalogue.deluxe(), 2, 2).andExpect(status().isConflict());

            pay(bookingId, key, CARD_OK)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
            assertThat(gateway.chargeCount()).isEqualTo(chargesBefore + 1);
            mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId))
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void confirmedBookingCannotBePaidAgainWithANewKey() throws Exception {
            long bookingId = paidBooking(catalogue.garden(), catalogue.standard(), 1, UPI_OK);

            pay(bookingId, "pay-" + UUID.randomUUID(), CARD_OK).andExpect(status().isConflict());
            mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].method").value("UPI"));
        }

        @Test
        void invalidBookingRequestsAreRejected() throws Exception {
            book(catalogue.riverside(), catalogue.deluxe(), CHECK_OUT, CHECK_IN, 2, 1).andExpect(status().isBadRequest());
            book(catalogue.riverside(), catalogue.deluxe(), CHECK_IN, CHECK_IN, 2, 1).andExpect(status().isBadRequest());
            book(catalogue.riverside(), catalogue.deluxe(), NOW_DATE.minusDays(1), CHECK_OUT, 2, 1)
                    .andExpect(status().isBadRequest());
            book(catalogue.riverside(), catalogue.deluxe(), 5, 2)
                    .andExpect(status().isBadRequest());
            book(catalogue.garden(), catalogue.deluxe(), 2, 1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("does not belong to property")));

            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(0);
        }
    }

    @Nested
    class Flow4_FailedPayment {

        @ParameterizedTest(name = "declined {0}")
        @ValueSource(strings = {"CARD", "UPI", "WALLET"})
        void declinedPaymentFailsTheBookingAndReleasesItsRooms(String method) throws Exception {
            long bookingId = id(book(catalogue.riverside(), catalogue.deluxe(), 3, 3)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT")));
            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(3);
            assertThat(availability(search(2))).doesNotContainKey(catalogue.deluxe());

            pay(bookingId, "pay-" + UUID.randomUUID(), declined(method))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.bookingStatus").value("PAYMENT_FAILED"))
                    .andExpect(jsonPath("$.failureReason").value(method + " payment declined by issuer"));

            mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                    .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                    .andExpect(jsonPath("$.confirmedAt").value(nullValue()));
            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(0);
            assertThat(availability(search(2)).get(catalogue.deluxe())).isEqualTo(3);

            pay(bookingId, "pay-" + UUID.randomUUID(), CARD_OK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("PAYMENT_FAILED")));
            mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                    .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));

            book(catalogue.riverside(), catalogue.deluxe(), 3, 3).andExpect(status().isCreated());
            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(3);
        }
    }

    @Nested
    class Flow5_Cancellation {

        /** Two suites for two nights: 2 x 2 x 9000 = 36000 paid. Cancellation happens at {@link #NOW}. */
        @ParameterizedTest(name = "check-in {0}: {2}% refund")
        @CsvSource({
                "2027-03-10, 2027-03-12, 100, 36000, REFUNDED",
                "2027-03-05, 2027-03-07,  50, 18000, PARTIALLY_REFUNDED",
                "2027-03-02, 2027-03-04,   0,     0, SUCCEEDED"
        })
        void moderatePolicyRefundDependsOnNoticeAndTheRoomsAreReleased(LocalDate checkIn, LocalDate checkOut,
                                                                       int refundPercentage, BigDecimal refundAmount,
                                                                       String paymentStatus) throws Exception {
            long bookingId = id(book(catalogue.riverside(), catalogue.suite(), checkIn, checkOut, 4, 2)
                    .andExpect(status().isCreated()));
            pay(bookingId, "pay-" + UUID.randomUUID(), UPI_OK).andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
            assertThat(bookedRooms(catalogue.suite(), checkIn, checkOut)).containsOnly(2);

            String cancellation = body(cancel(bookingId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancellationPolicy").value("MODERATE"))
                    .andExpect(jsonPath("$.refundRule").isNotEmpty()));
            assertThat(decimal(cancellation, "$.paidAmount")).isEqualByComparingTo("36000");
            assertThat(decimal(cancellation, "$.refundPercentage")).isEqualByComparingTo(BigDecimal.valueOf(refundPercentage));
            assertThat(decimal(cancellation, "$.refundAmount")).isEqualByComparingTo(refundAmount);

            mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
            String payments = body(mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId)));
            assertThat(JsonPath.<String>read(payments, "$[0].status")).isEqualTo(paymentStatus);
            assertThat(decimal(payments, "$[0].refundedAmount")).isEqualByComparingTo(refundAmount);

            assertThat(bookedRooms(catalogue.suite(), checkIn, checkOut)).containsOnly(0);
            book(catalogue.riverside(), catalogue.suite(), checkIn, checkOut, 4, 2).andExpect(status().isCreated());
        }

        @Test
        void flexibleCancellationRefundsInFullAndSoldOutRoomTypeReappearsInSearch() throws Exception {
            long bookingId = paidBooking(catalogue.garden(), catalogue.family(), 1, WALLET_OK);
            assertThat(availability(search(3))).doesNotContainKey(catalogue.family());
            int refundsBefore = gateway.refundCount();

            String cancellation = body(cancel(bookingId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancellationPolicy").value("FLEXIBLE"))
                    .andExpect(jsonPath("$.refund.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.refund.refundReference").value(startsWith("MOCK-REFUND-"))));
            assertThat(decimal(cancellation, "$.paidAmount")).isEqualByComparingTo("12000");
            assertThat(decimal(cancellation, "$.refundPercentage")).isEqualByComparingTo("100");
            assertThat(decimal(cancellation, "$.refundAmount")).isEqualByComparingTo("12000");
            assertThat(decimal(cancellation, "$.refund.amount")).isEqualByComparingTo("12000");

            mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId))
                    .andExpect(jsonPath("$[0].status").value("REFUNDED"));
            assertThat(availability(search(3)).get(catalogue.family())).isEqualTo(1);
            assertThat(gateway.refundCount()).isEqualTo(refundsBefore + 1);

            cancel(bookingId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Booking " + bookingId + " is already cancelled"));
            assertThat(gateway.refundCount()).isEqualTo(refundsBefore + 1);
            assertThat(bookedRooms(catalogue.family())).containsOnly(0);
        }

        @Test
        void unpaidBookingCancelsWithoutRefundAndFailedBookingCannotBeCancelled() throws Exception {
            long pending = id(book(catalogue.garden(), catalogue.standard(), 2, 2));
            cancel(pending)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refund").value(nullValue()));
            assertThat(bookedRooms(catalogue.standard())).containsOnly(0);

            long failed = id(book(catalogue.garden(), catalogue.standard(), 1, 1));
            pay(failed, "pay-" + UUID.randomUUID(), declined("CARD"));
            cancel(failed).andExpect(status().isConflict());
            cancel(999_999).andExpect(status().isNotFound());
        }
    }

    @Nested
    class Flow6_DoubleBooking {

        @Test
        void bookingMoreRoomsThanAvailableIsRejectedWithoutSideEffects() throws Exception {
            book(catalogue.riverside(), catalogue.suite(), 3, 3).andExpect(status().isBadRequest());

            long first = id(book(catalogue.riverside(), catalogue.deluxe(), 2, 2).andExpect(status().isCreated()));
            book(catalogue.riverside(), catalogue.deluxe(), 2, 2)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("1 room(s) available")));

            assertThat(bookedRooms(catalogue.suite())).containsOnly(0);
            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(2);
            bookingsOf(catalogue.riverside())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(first));
        }

        @Test
        void overlappingStaysCannotExceedInventoryButAdjacentStaysCan() throws Exception {
            book(catalogue.riverside(), catalogue.suite(), 2, 2).andExpect(status().isCreated());

            book(catalogue.riverside(), catalogue.suite(), 1, 1).andExpect(status().isConflict());
            book(catalogue.riverside(), catalogue.suite(), CHECK_IN.plusDays(1), CHECK_OUT.plusDays(1), 1, 1)
                    .andExpect(status().isConflict());
            book(catalogue.riverside(), catalogue.suite(), CHECK_IN.minusDays(1), CHECK_IN.plusDays(1), 1, 1)
                    .andExpect(status().isConflict());

            book(catalogue.riverside(), catalogue.suite(), CHECK_OUT, CHECK_OUT.plusDays(2), 2, 2)
                    .andExpect(status().isCreated());
            book(catalogue.riverside(), catalogue.suite(), CHECK_IN.minusDays(2), CHECK_IN, 2, 2)
                    .andExpect(status().isCreated());
            book(catalogue.riverside(), catalogue.deluxe(), 1, 1).andExpect(status().isCreated());

            assertThat(bookedRooms(catalogue.suite(), CHECK_IN.minusDays(2), CHECK_OUT.plusDays(2))).containsOnly(2);
            bookingsOf(catalogue.riverside()).andExpect(jsonPath("$.totalElements").value(4));
        }

        @Test
        void confirmedBookingAlsoBlocksTheInventory() throws Exception {
            paidBooking(catalogue.garden(), catalogue.family(), 1, CARD_OK);
            book(catalogue.garden(), catalogue.family(), 2, 1).andExpect(status().isConflict());
        }
    }

    @Nested
    class Flow7_ConcurrentBooking {

        @Test
        void simultaneousSingleRoomRequestsNeverOversell() throws Exception {
            List<Integer> statuses = concurrently(12,
                    () -> book(catalogue.riverside(), catalogue.deluxe(), 2, 1).andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED.value()).hasSize(3);
            assertThat(bookedRooms(catalogue.deluxe())).containsOnly(3);
            bookingsOf(catalogue.riverside()).andExpect(jsonPath("$.totalElements").value(3));
            assertThat(availability(search(2))).doesNotContainKey(catalogue.deluxe());
        }

        @Test
        void simultaneousMultiRoomRequestsNeverOversell() throws Exception {
            List<Integer> statuses = concurrently(6,
                    () -> book(catalogue.garden(), catalogue.standard(), 2, 2).andReturn().getResponse().getStatus());

            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED.value()).hasSize(2);
            assertThat(bookedRooms(catalogue.standard())).containsOnly(4);
            assertThat(availability(search(2)).get(catalogue.standard())).isEqualTo(1);
            book(catalogue.garden(), catalogue.standard(), 1, 1).andExpect(status().isCreated());
            book(catalogue.garden(), catalogue.standard(), 1, 1).andExpect(status().isConflict());
        }

        @Test
        void simultaneousBookAndPayFlowsConfirmOnlyAsManyBookingsAsRooms() throws Exception {
            List<Integer> statuses = concurrently(8, () -> {
                ResultActions booking = book(catalogue.riverside(), catalogue.suite(), 2, 1);
                if (booking.andReturn().getResponse().getStatus() != HttpStatus.CREATED.value()) {
                    return booking.andReturn().getResponse().getStatus();
                }
                return pay(id(booking), "pay-" + UUID.randomUUID(), CARD_OK).andReturn().getResponse().getStatus();
            });

            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED.value()).hasSize(2);
            mockMvc.perform(get("/api/v1/properties/{id}/bookings", catalogue.riverside()).param("status", "CONFIRMED"))
                    .andExpect(jsonPath("$.totalElements").value(2));
            assertThat(bookedRooms(catalogue.suite())).containsOnly(2);
        }
    }

    // ---------------------------------------------------------------- onboarding helpers

    private Catalogue onboardCatalogue() throws Exception {
        String city = "E2E-" + UUID.randomUUID();
        long ownerId = createOwner("Sunrise Hospitality");
        long riverside = addProperty(ownerId, "Riverside Grand", "Indiranagar", 4,
                "[\"WIFI\", \"SWIMMING_POOL\", \"GYM\"]", "MODERATE", city);
        long garden = addProperty(ownerId, "Garden Inn", "Koramangala", 3, "[\"WIFI\", \"PARKING\"]", "FLEXIBLE", city);
        return new Catalogue(city, ownerId,
                riverside, addRoomType(riverside, "Deluxe", 2, 3, "4000"), addRoomType(riverside, "Suite", 4, 2, "9000"),
                garden, addRoomType(garden, "Standard", 2, 5, "2500"), addRoomType(garden, "Family", 4, 1, "6000"));
    }

    private long createOwner(String name) throws Exception {
        ResultActions result = mockMvc.perform(postJson("/api/v1/owners", """
                        {"name": "%s", "email": "%s", "phone": "+91 98450 00000"}
                        """.formatted(name, uniqueEmail())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name));
        long id = id(result);
        result.andExpect(header().string("Location", "/api/v1/owners/" + id));
        return id;
    }

    private long addProperty(long ownerId, String name, String locality, int stars, String amenities, String policy)
            throws Exception {
        return addProperty(ownerId, name, locality, stars, amenities, policy, "E2E-" + UUID.randomUUID());
    }

    private long addProperty(long ownerId, String name, String locality, int stars, String amenities, String policy,
                             String city) throws Exception {
        return id(mockMvc.perform(postJson("/api/v1/owners/{id}/properties", """
                        {"name": "%s",
                         "location": {"addressLine": "1 Main Rd", "locality": "%s", "city": "%s"},
                         "starRating": %d, "amenities": %s, "cancellationPolicy": "%s"}
                        """.formatted(name, locality, city, stars, amenities, policy), ownerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(ownerId)));
    }

    private long addRoomType(long propertyId, String name, int maxGuests, int inventory, String price) throws Exception {
        return id(mockMvc.perform(postJson("/api/v1/properties/{id}/room-types", """
                        {"name": "%s", "description": "%s room", "maxGuests": %d, "totalInventory": %d, "basePrice": %s}
                        """.formatted(name, name, maxGuests, inventory, price), propertyId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propertyId)));
    }

    // ---------------------------------------------------------------- booking, payment and cancellation helpers

    private ResultActions book(long propertyId, long roomTypeId, int guests, int rooms) throws Exception {
        return book(propertyId, roomTypeId, CHECK_IN, CHECK_OUT, guests, rooms);
    }

    private ResultActions book(long propertyId, long roomTypeId, LocalDate checkIn, LocalDate checkOut,
                               int guests, int rooms) throws Exception {
        return mockMvc.perform(postJson("/api/v1/bookings", """
                {"propertyId": %d, "roomTypeId": %d, "checkIn": "%s", "checkOut": "%s",
                 "guests": %d, "rooms": %d,
                 "guest": {"fullName": "Asha Rao", "email": "asha@example.com", "phone": "+919845000000"}}
                """.formatted(propertyId, roomTypeId, checkIn, checkOut, guests, rooms)));
    }

    private long paidBooking(long propertyId, long roomTypeId, int rooms, String payment) throws Exception {
        long bookingId = id(book(propertyId, roomTypeId, rooms, rooms).andExpect(status().isCreated()));
        pay(bookingId, "pay-" + UUID.randomUUID(), payment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
        return bookingId;
    }

    private ResultActions pay(long bookingId, String idempotencyKey, String payment) throws Exception {
        return mockMvc.perform(postJson("/api/v1/bookings/{id}/payments", payment, bookingId)
                .header("Idempotency-Key", idempotencyKey));
    }

    private ResultActions cancel(long bookingId) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/{id}/cancellation", bookingId));
    }

    private ResultActions bookingsOf(long propertyId) throws Exception {
        return mockMvc.perform(get("/api/v1/properties/{id}/bookings", propertyId)).andExpect(status().isOk());
    }

    private static String card(String number) {
        return """
                {"method": "CARD", "card": {"cardNumber": "%s", "holderName": "Asha Rao",
                 "expiryMonth": 12, "expiryYear": 2099, "cvv": "123"}}""".formatted(number);
    }

    private static String wallet(String walletId) {
        return """
                {"method": "WALLET", "wallet": {"provider": "PAYTM", "walletId": "%s"}}""".formatted(walletId);
    }

    private static String declined(String method) {
        return switch (method) {
            case "CARD" -> card("4000000000000002");
            case "UPI" -> """
                    {"method": "UPI", "upi": {"vpa": "fail@okbank"}}""";
            case "WALLET" -> wallet("9000000002");
            default -> throw new IllegalArgumentException(method);
        };
    }

    // ---------------------------------------------------------------- search and inventory helpers

    private MockHttpServletRequestBuilder search(int guests) {
        return get("/api/v1/properties/search")
                .param("city", catalogue.city())
                .param("checkIn", CHECK_IN.toString())
                .param("checkOut", CHECK_OUT.toString())
                .param("guests", String.valueOf(guests));
    }

    /**
     * Available rooms per offered room type, as reported by the search API. Room types that are not offered
     * (filtered out or without enough free rooms) are absent from the map.
     */
    private Map<Long, Integer> availability(MockHttpServletRequestBuilder search) throws Exception {
        String json = body(mockMvc.perform(search).andExpect(status().isOk()));
        List<Map<String, Object>> offers = JsonPath.read(json, "$.results[*].availableRoomTypes[*]");
        Map<Long, Integer> availableRooms = new HashMap<>();
        for (Map<String, Object> offer : offers) {
            availableRooms.put(((Number) offer.get("roomTypeId")).longValue(), ((Number) offer.get("availableRooms")).intValue());
        }
        return availableRooms;
    }

    private static String roomTypePath(long roomTypeId, String field) {
        return "$.results[*].availableRoomTypes[?(@.roomTypeId == " + roomTypeId + ")]." + field;
    }

    private List<Integer> bookedRooms(long roomTypeId) {
        return bookedRooms(roomTypeId, CHECK_IN, CHECK_OUT);
    }

    private List<Integer> bookedRooms(long roomTypeId, LocalDate from, LocalDate to) {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, from, to).stream()
                .map(RoomInventory::getBookedRooms)
                .toList();
    }

    // ---------------------------------------------------------------- generic helpers

    private static MockHttpServletRequestBuilder postJson(String path, String json, Object... uriVariables) {
        return post(path, uriVariables).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static long id(ResultActions result) throws Exception {
        return JsonPath.<Number>read(body(result), "$.id").longValue();
    }

    /** Reads a JSON number exactly, whatever its scale; a filter path must match exactly one value. */
    private static BigDecimal decimal(String json, String path) {
        Object value = JsonPath.read(json, path);
        if (value instanceof List<?> values) {
            assertThat(values).hasSize(1);
            value = values.getFirst();
        }
        return new BigDecimal(value.toString());
    }

    private static String uniqueEmail() {
        return "owner-" + UUID.randomUUID() + "@example.com";
    }

    private static <T> List<T> concurrently(int threads, Callable<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
