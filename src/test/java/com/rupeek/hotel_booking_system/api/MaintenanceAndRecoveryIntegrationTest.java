package com.rupeek.hotel_booking_system.api;

import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestRequest;
import com.rupeek.hotel_booking_system.api.dto.cancellation.CancellationResponse;
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
import com.rupeek.hotel_booking_system.domain.exception.BookingHoldExpiredException;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.booking.BookingService;
import com.rupeek.hotel_booking_system.service.cancellation.BookingCancellationService;
import com.rupeek.hotel_booking_system.service.maintenance.BookingHoldExpiryService;
import com.rupeek.hotel_booking_system.service.maintenance.InventoryHorizonService;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import com.rupeek.hotel_booking_system.service.payment.PaymentService;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.MockPaymentGateway;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGateway;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException;
import com.rupeek.hotel_booking_system.service.refund.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recovery paths that need time to pass or a misbehaving gateway: hold expiry, inventory horizon top-up and
 * refund retries. The context has its own database, a clock the tests move forward and a refund gateway whose
 * behaviour each test chooses.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance_flows;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "hotel.inventory.horizon-days=10"
})
class MaintenanceAndRecoveryIntegrationTest {

    private static final Instant START = Instant.parse("2027-06-01T04:00:00Z");
    private static final PaymentRequest CARD_OK = PaymentRequest.card(
            new CardDetailsRequest("4111111111111111", "Asha Rao", 12, 2099, "123"));

    @TestConfiguration
    static class Overrides {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(START);
        }

        @Bean
        @Primary
        SwitchableRefundGateway switchableRefundGateway(MockPaymentGateway delegate) {
            return new SwitchableRefundGateway(delegate);
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private MovableClock clock;
    @Autowired
    private SwitchableRefundGateway refundGateway;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BookingCancellationService cancellationService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private BookingHoldExpiryService holdExpiryService;
    @Autowired
    private InventoryHorizonService horizonService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;

    private MockMvc mockMvc;
    private long propertyId;
    private long roomTypeId;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        clock.set(START);
        refundGateway.reset();
        long ownerId = onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
        propertyId = onboardingService.addProperty(ownerId, new CreatePropertyRequest("Hotel " + UUID.randomUUID(),
                new LocationRequest("1 Main Rd", "Central", "Bengaluru"), 4, Set.of(Amenity.WIFI),
                CancellationPolicyType.FLEXIBLE)).id();
        roomTypeId = onboardingService.addRoomType(propertyId, new CreateRoomTypeRequest("Room " + UUID.randomUUID(),
                null, 2, 2, new BigDecimal("2500"))).id();
        checkIn = LocalDate.ofInstant(START, ZoneId.of("Asia/Kolkata")).plusDays(5);
        checkOut = checkIn.plusDays(2);
    }

    @Test
    void expiredHoldIsReleasedByTheExpiryJobSoTheRoomsCanBeBookedAgain() {
        long abandoned = newBooking(2);
        assertThat(bookedRooms()).containsOnly(2);

        clock.advance(Duration.ofMinutes(16));
        assertThat(holdExpiryService.expireOverdueHolds(100)).isGreaterThanOrEqualTo(1);

        assertThat(bookingRepository.findById(abandoned).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookedRooms()).containsOnly(0);
        newBooking(2);
        assertThat(bookedRooms()).containsOnly(2);
    }

    @Test
    void payingAfterTheHoldExpiredReleasesTheRoomsWithoutCharging() {
        long late = newBooking(1);
        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> paymentService.pay(late, "key-" + UUID.randomUUID(), CARD_OK))
                .isInstanceOf(BookingHoldExpiredException.class);

        assertThat(bookingRepository.findById(late).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(late)).isEmpty();
        assertThat(bookedRooms()).containsOnly(0);
    }

    @Test
    void horizonJobAddsTheNightsThatCameIntoRange() {
        LocalDate today = LocalDate.ofInstant(START, ZoneId.of("Asia/Kolkata"));
        assertThat(roomInventoryRepository.findLastStayDate(roomTypeId)).contains(today.plusDays(9));

        clock.advance(Duration.ofDays(3));
        assertThat(horizonService.extendAll()).isGreaterThanOrEqualTo(3);

        assertThat(roomInventoryRepository.findLastStayDate(roomTypeId)).contains(today.plusDays(12));
        assertThat(roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, today.plusDays(10),
                today.plusDays(13))).hasSize(3).allSatisfy(night -> assertThat(night.availableRooms()).isEqualTo(2));
        assertThat(horizonService.extendAll()).as("a second run adds nothing").isZero();
    }

    @Test
    void refundLeftPendingByAnUnreachableGatewayIsPaidOutByTheRetryJob() {
        long bookingId = paidBooking();
        refundGateway.behave(SwitchableRefundGateway.Mode.UNREACHABLE);

        CancellationResponse cancelled = cancellationService.cancel(bookingId);

        assertThat(cancelled.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.refund().status()).isEqualTo(RefundStatus.PENDING);
        assertThat(bookedRooms()).containsOnly(0);

        refundGateway.behave(SwitchableRefundGateway.Mode.DELEGATE);
        clock.advance(Duration.ofMinutes(3));
        refundService.retryPendingRefunds(Duration.ofMinutes(2), 100);

        assertThat(refundService.getRefund(cancelled.refund().id()).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).getFirst().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void rejectedRefundCanBeRetriedThroughTheApi() throws Exception {
        long bookingId = paidBooking();
        refundGateway.behave(SwitchableRefundGateway.Mode.REJECT);
        long refundId = cancellationService.cancel(bookingId).refund().id();
        mockMvc.perform(get("/api/v1/refunds/{id}", refundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Account closed"));

        refundGateway.behave(SwitchableRefundGateway.Mode.DELEGATE);
        mockMvc.perform(post("/api/v1/refunds/{id}/retry", refundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(refundGateway.lastKey()).isEqualTo(refundGateway.firstKey() + "-attempt-2");

        mockMvc.perform(post("/api/v1/refunds/{id}/retry", refundId))
                .andExpect(status().isConflict());
    }

    private long newBooking(int rooms) {
        return bookingService.createBooking(new CreateBookingRequest(propertyId, roomTypeId, checkIn, checkOut,
                rooms, rooms, new GuestRequest("Asha Rao", "asha@example.com", null))).id();
    }

    private long paidBooking() {
        long bookingId = newBooking(1);
        paymentService.pay(bookingId, "key-" + UUID.randomUUID(), CARD_OK);
        return bookingId;
    }

    private List<Integer> bookedRooms() {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, checkIn, checkOut).stream()
                .map(RoomInventory::getBookedRooms).toList();
    }

    static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static final class SwitchableRefundGateway implements RefundGateway {

        enum Mode { DELEGATE, REJECT, UNREACHABLE }

        private final RefundGateway delegate;
        private volatile Mode mode = Mode.DELEGATE;
        private volatile String firstKey;
        private volatile String lastKey;

        SwitchableRefundGateway(RefundGateway delegate) {
            this.delegate = delegate;
        }

        void behave(Mode newMode) {
            mode = newMode;
        }

        void reset() {
            mode = Mode.DELEGATE;
            firstKey = null;
            lastKey = null;
        }

        String firstKey() {
            return firstKey;
        }

        String lastKey() {
            return lastKey;
        }

        @Override
        public GatewayRefundResponse refund(GatewayRefundRequest request) {
            if (firstKey == null) {
                firstKey = request.idempotencyKey();
            }
            lastKey = request.idempotencyKey();
            return switch (mode) {
                case DELEGATE -> delegate.refund(request);
                case REJECT -> GatewayRefundResponse.rejected("Account closed");
                case UNREACHABLE -> throw new RefundGatewayException("timeout");
            };
        }
    }
}
