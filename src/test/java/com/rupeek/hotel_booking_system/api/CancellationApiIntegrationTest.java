package com.rupeek.hotel_booking_system.api;

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
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingAlreadyCancelledException;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.booking.BookingService;
import com.rupeek.hotel_booking_system.service.cancellation.BookingCancellationService;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import com.rupeek.hotel_booking_system.service.payment.PaymentService;
import com.rupeek.hotel_booking_system.service.payment.gateway.MockPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hotel.inventory.horizon-days=30")
class CancellationApiIntegrationTest {

    private static final int TOTAL_ROOMS = 3;
    private static final int THREADS = 8;
    private static final PaymentRequest CARD_OK = PaymentRequest.card(
            new CardDetailsRequest("4111111111111111", "Asha Rao", 12, 2099, "123"));
    private static final PaymentRequest CARD_DECLINED = PaymentRequest.card(
            new CardDetailsRequest("4000000000000002", "Asha Rao", 12, 2099, "123"));

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BookingCancellationService cancellationService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private MockPaymentGateway gateway;

    private MockMvc mockMvc;
    private final LocalDate checkIn = LocalDate.now().plusDays(3);
    private final LocalDate checkOut = checkIn.plusDays(2);
    private long propertyId;
    private long roomTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void cancellingAConfirmedFlexibleBookingRefundsInFullAndFreesItsRooms() throws Exception {
        givenPropertyWith(CancellationPolicyType.FLEXIBLE);
        long bookingId = paidBooking(TOTAL_ROOMS);
        assertThatThrownBy(() -> newBooking(1)).isInstanceOf(InsufficientInventoryException.class);

        cancel(bookingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.cancellationPolicy").value("FLEXIBLE"))
                .andExpect(jsonPath("$.paidAmount").value(15000.00))
                .andExpect(jsonPath("$.refundPercentage").value(100))
                .andExpect(jsonPath("$.refundAmount").value(15000.00))
                .andExpect(jsonPath("$.refund.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refund.refundReference").value(startsWith("MOCK-REFUND-")));

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/bookings/{id}/payments", bookingId))
                .andExpect(jsonPath("$[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$[0].refundedAmount").value(15000.00));
        assertThat(bookedRooms()).containsOnly(0);

        long rebooked = newBooking(TOTAL_ROOMS);
        assertThat(bookingRepository.findById(rebooked).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(bookedRooms()).containsOnly(TOTAL_ROOMS);
    }

    @Test
    void moderatePolicyRefundsHalfWhenCancelledBetweenTwoAndSevenDaysBeforeCheckIn() throws Exception {
        givenPropertyWith(CancellationPolicyType.MODERATE);
        long bookingId = paidBooking(1);

        cancel(bookingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPercentage").value(50))
                .andExpect(jsonPath("$.refundAmount").value(2500.00))
                .andExpect(jsonPath("$.refund.amount").value(2500.00));

        Payment payment = paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("2500.00");
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void nonRefundablePolicyCancelsWithoutRefund() throws Exception {
        givenPropertyWith(CancellationPolicyType.NON_REFUNDABLE);
        long bookingId = paidBooking(2);

        cancel(bookingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(0))
                .andExpect(jsonPath("$.refund").value(nullValue()));

        assertThat(refundRepository.findByBookingId(bookingId)).isEmpty();
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).getFirst().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void unpaidBookingIsCancelledAndItsHoldReleased() throws Exception {
        givenPropertyWith(CancellationPolicyType.FLEXIBLE);
        long bookingId = newBooking(2);
        assertThat(bookedRooms()).containsOnly(2);

        cancel(bookingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(0))
                .andExpect(jsonPath("$.refund").value(nullValue()));

        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void alreadyCancelledBookingIsRejectedAndNotRefundedTwice() throws Exception {
        givenPropertyWith(CancellationPolicyType.FLEXIBLE);
        long bookingId = paidBooking(1);
        cancel(bookingId).andExpect(status().isOk());
        int refunds = gateway.refundCount();

        cancel(bookingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Booking " + bookingId + " is already cancelled"));

        assertThat(gateway.refundCount()).isEqualTo(refunds);
        assertThat(refundRepository.findByBookingId(bookingId)).hasSize(1);
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void paymentFailedBookingCannotBeCancelled() throws Exception {
        givenPropertyWith(CancellationPolicyType.FLEXIBLE);
        long bookingId = newBooking(1);
        paymentService.pay(bookingId, "key-" + UUID.randomUUID(), CARD_DECLINED);

        cancel(bookingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Booking cannot transition from PAYMENT_FAILED to CANCELLED"));
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void unknownOrInvalidBookingIds() throws Exception {
        cancel(999_999L).andExpect(status().isNotFound());
        cancel(0L).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentCancellationsCancelAndRefundExactlyOnce() throws Exception {
        givenPropertyWith(CancellationPolicyType.FLEXIBLE);
        long bookingId = paidBooking(2);
        int refundsBefore = gateway.refundCount();

        List<Boolean> outcomes = runConcurrently(() -> {
            try {
                cancellationService.cancel(bookingId);
                return true;
            } catch (BookingAlreadyCancelledException ex) {
                return false;
            }
        });

        assertThat(outcomes).containsOnlyOnce(true);
        assertThat(gateway.refundCount()).isEqualTo(refundsBefore + 1);
        List<Refund> refunds = refundRepository.findByBookingId(bookingId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst().getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(bookedRooms()).containsOnly(0);
    }

    private void givenPropertyWith(CancellationPolicyType policy) {
        long ownerId = onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
        propertyId = onboardingService.addProperty(ownerId, new CreatePropertyRequest("Hotel " + UUID.randomUUID(),
                new LocationRequest("1 Main Rd", "Central", "Bengaluru"), 4, Set.of(Amenity.WIFI), policy)).id();
        roomTypeId = onboardingService.addRoomType(propertyId, new CreateRoomTypeRequest("Room " + UUID.randomUUID(),
                null, 2, TOTAL_ROOMS, new BigDecimal("2500"))).id();
    }

    private long newBooking(int rooms) {
        return bookingService.createBooking(new CreateBookingRequest(propertyId, roomTypeId, checkIn, checkOut,
                rooms, rooms, new GuestRequest("Asha Rao", "asha@example.com", null))).id();
    }

    private long paidBooking(int rooms) {
        long bookingId = newBooking(rooms);
        paymentService.pay(bookingId, "key-" + UUID.randomUUID(), CARD_OK);
        return bookingId;
    }

    private ResultActions cancel(long bookingId) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/{id}/cancellation", bookingId));
    }

    private List<Integer> bookedRooms() {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, checkIn, checkOut).stream()
                .map(RoomInventory::getBookedRooms).toList();
    }

    private static <T> List<T> runConcurrently(Callable<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
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
