package com.rupeek.hotel_booking_system.api;

import com.jayway.jsonpath.JsonPath;
import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.CardDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.PaymentInProgressException;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.booking.BookingService;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import com.rupeek.hotel_booking_system.service.payment.PaymentOutcome;
import com.rupeek.hotel_booking_system.service.payment.PaymentService;
import com.rupeek.hotel_booking_system.service.payment.gateway.MockPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hotel.inventory.horizon-days=30")
class PaymentApiIntegrationTest {

    private static final String CARD_OK = """
            {"method": "CARD", "card": {"cardNumber": "4111111111111111", "holderName": "Asha Rao",
             "expiryMonth": 12, "expiryYear": 2099, "cvv": "123"}}
            """;
    private static final String CARD_DECLINED = CARD_OK.replace("4111111111111111", "4000000000000002");
    private static final int THREADS = 8;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private MockPaymentGateway gateway;

    private MockMvc mockMvc;
    private final LocalDate checkIn = LocalDate.now().plusDays(3);
    private final LocalDate checkOut = checkIn.plusDays(2);
    private long propertyId;
    private long roomTypeId;
    private long bookingId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ownerId = onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
        propertyId = onboardingService.addProperty(ownerId, new CreatePropertyRequest("Hotel " + UUID.randomUUID(),
                new LocationRequest("1 Main Rd", "Central", "Bengaluru"), 4, Set.of(Amenity.WIFI),
                CancellationPolicyType.FLEXIBLE)).id();
        roomTypeId = onboardingService.addRoomType(propertyId, new CreateRoomTypeRequest("Room " + UUID.randomUUID(),
                null, 2, 3, new BigDecimal("2500"))).id();
        bookingId = newBooking();
    }

    @Test
    void successfulCardPaymentConfirmsTheBookingAndKeepsItsRooms() throws Exception {
        String body = pay(bookingId, "key-" + UUID.randomUUID(), CARD_OK)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/payments/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.method").value("CARD"))
                .andExpect(jsonPath("$.transactionReference").value(startsWith("MOCK-CARD-")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("4111111111111111");

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        assertThat(bookedRooms()).containsOnly(1);

        long paymentId = ((Number) JsonPath.read(body, "$.id")).longValue();
        String reference = JsonPath.read(body, "$.transactionReference");
        mockMvc.perform(get("/api/v1/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId));
        mockMvc.perform(get("/api/v1/payments").param("transactionReference", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId));
        mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/v1/payments").param("transactionReference", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Payment with transaction reference nope was not found"));
    }

    @Test
    void declinedPaymentFailsTheBookingAndReleasesItsRooms() throws Exception {
        assertThat(bookedRooms()).containsOnly(1);

        pay(bookingId, "key-" + UUID.randomUUID(), """
                {"method": "UPI", "upi": {"vpa": "fail@okbank"}}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.bookingStatus").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.failureReason").value("UPI payment declined by issuer"))
                .andExpect(jsonPath("$.transactionReference").value(startsWith("MOCK-UPI-")));

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(bookedRooms()).containsOnly(0);

        pay(bookingId, "key-" + UUID.randomUUID(), CARD_OK)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("is PAYMENT_FAILED")));
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void walletPaymentIsSupported() throws Exception {
        pay(bookingId, "key-" + UUID.randomUUID(), """
                {"method": "WALLET", "wallet": {"provider": "PHONEPE", "walletId": "9876543210"}}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.transactionReference").value(startsWith("MOCK-WALLET-")));
    }

    @Test
    void repeatedKeyReplaysThePaymentWithoutChargingAgain() throws Exception {
        String key = "key-" + UUID.randomUUID();
        int chargesBefore = gateway.chargeCount();

        String first = pay(bookingId, key, CARD_DECLINED)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = pay(bookingId, key, CARD_DECLINED)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();

        assertThat((Object) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(gateway.chargeCount() - chargesBefore).isEqualTo(1);
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)).hasSize(1);
        assertThat(bookedRooms()).containsOnly(0);

        long otherBooking = newBooking();
        pay(otherBooking, key, CARD_OK)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already used for a different payment request")));
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(otherBooking)).isEmpty();
    }

    @Test
    void concurrentRequestsWithTheSameKeyChargeOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();
        int chargesBefore = gateway.chargeCount();

        List<PaymentOutcome> outcomes = runConcurrently(i -> paymentService.pay(bookingId, key, cardRequest()));

        assertThat(outcomes).extracting(outcome -> outcome.payment().id()).containsOnly(outcomes.getFirst().payment().id());
        assertThat(outcomes).filteredOn(outcome -> !outcome.replayed()).hasSize(1);
        assertThat(gateway.chargeCount() - chargesBefore).isEqualTo(1);
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)).hasSize(1);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void concurrentRequestsWithDifferentKeysConfirmTheBookingOnce() throws Exception {
        int chargesBefore = gateway.chargeCount();
        List<Object> results = runConcurrently(i -> {
            try {
                return paymentService.pay(bookingId, "key-" + UUID.randomUUID(), cardRequest());
            } catch (InvalidBookingStateException | PaymentInProgressException ex) {
                return ex;
            }
        });

        // Losers either see the booking already confirmed or the winner's charge still in flight.
        assertThat(results).filteredOn(PaymentOutcome.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof InvalidBookingStateException
                || r instanceof PaymentInProgressException).hasSize(THREADS - 1);
        assertThat(gateway.chargeCount() - chargesBefore).isEqualTo(1);
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)).hasSize(1);
        assertThat(bookedRooms()).containsOnly(1);
    }

    @Test
    void rejectsInvalidPaymentRequestsWithoutRecordingAPayment() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", bookingId)
                        .contentType(MediaType.APPLICATION_JSON).content(CARD_OK))
                .andExpect(status().isBadRequest());
        pay(bookingId, "bad key!", CARD_OK).andExpect(status().isBadRequest());
        pay(bookingId, "k1", CARD_OK.replace("4111111111111111", "4111111111111112"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Card number is invalid"));
        pay(bookingId, "k2", """
                {"method": "CARD", "upi": {"vpa": "asha@okbank"}}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("CARD payment requires 'card' details"));
        pay(bookingId, "k3", """
                {"method": "UPI", "upi": {"vpa": "asha@okbank"}, "wallet": {"provider": "PAYTM", "walletId": "98765"}}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("singleInstrument"));
        pay(bookingId, "k4", """
                {"method": "CASH", "upi": {"vpa": "asha@okbank"}}
                """)
                .andExpect(status().isBadRequest());
        pay(bookingId, "k5", CARD_OK.replace("2099", "2020")).andExpect(status().isBadRequest());
        pay(999_999L, "k6", CARD_OK).andExpect(status().isNotFound());

        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)).isEmpty();
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(bookedRooms()).containsOnly(1);
    }

    private ResultActions pay(long booking, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static PaymentRequest cardRequest() {
        return PaymentRequest.card(new CardDetailsRequest("4111111111111111", "Asha Rao", 12, 2099, "123"));
    }

    private long newBooking() {
        return bookingService.createBooking(new CreateBookingRequest(propertyId, roomTypeId, checkIn, checkOut, 2, 1,
                new GuestRequest("Asha Rao", "asha@example.com", null))).id();
    }

    private List<Integer> bookedRooms() {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, checkIn, checkOut).stream()
                .map(RoomInventory::getBookedRooms).toList();
    }

    private interface Attempt<T> {
        T run(int index);
    }

    /**
     * Releases all attempts at once so they contend for the booking row lock.
     */
    private static <T> List<T> runConcurrently(Attempt<T> attempt) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return attempt.run(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException | java.util.concurrent.TimeoutException ex) {
                    fail("Concurrent payment attempt failed unexpectedly", ex);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
