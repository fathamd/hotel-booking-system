package com.rupeek.hotel_booking_system.service.booking;

import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

/**
 * Fires simultaneous booking requests through the real transactional service against H2 and checks that the
 * pessimistic inventory locks never let more rooms be sold than exist.
 */
@SpringBootTest(properties = "hotel.inventory.horizon-days=30")
class BookingConcurrencyTest {

    private static final int THREADS = 16;

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private BookingRepository bookingRepository;

    private final LocalDate checkIn = LocalDate.now().plusDays(5);
    private final LocalDate checkOut = checkIn.plusDays(3);
    private long propertyId;

    @BeforeEach
    void setUp() {
        long ownerId = onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
        propertyId = onboardingService.addProperty(ownerId, new CreatePropertyRequest("Hotel " + UUID.randomUUID(),
                new LocationRequest("1 Main Rd", "Central", "Bengaluru"), 4, Set.of(),
                CancellationPolicyType.FLEXIBLE)).id();
    }

    @Test
    void simultaneousRequestsForTheSameStaySellExactlyTheAvailableRooms() throws Exception {
        long roomTypeId = roomType(5);
        List<Callable<Long>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> book(roomTypeId, checkIn, checkOut, 1));
        }

        Outcome outcome = runSimultaneously(attempts);

        assertThat(outcome.successes()).isEqualTo(5);
        assertThat(outcome.soldOut()).isEqualTo(THREADS - 5);
        assertThat(bookedRooms(roomTypeId, checkIn, checkOut)).containsOnly(5);
        assertThat(bookingRepository.findInventoryConsuming(roomTypeId, checkIn, checkOut)).hasSize(5);
    }

    @Test
    void simultaneousMultiRoomRequestsNeverOversell() throws Exception {
        long roomTypeId = roomType(5);
        List<Callable<Long>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> book(roomTypeId, checkIn, checkOut, 2));
        }

        Outcome outcome = runSimultaneously(attempts);

        assertThat(outcome.successes()).isEqualTo(2);
        assertThat(outcome.soldOut()).isEqualTo(THREADS - 2);
        assertThat(bookedRooms(roomTypeId, checkIn, checkOut)).containsOnly(4);
    }

    @Test
    void simultaneousOverlappingStaysCompeteOnlyForTheSharedNights() throws Exception {
        long roomTypeId = roomType(1);
        LocalDate shared = checkIn.plusDays(2);
        List<Callable<Long>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            LocalDate start = shared.minusDays(i % 3);
            LocalDate end = shared.plusDays(1 + (i % 2));
            attempts.add(() -> book(roomTypeId, start, end, 1));
        }
        LocalDate disjointStart = shared.plusDays(5);
        attempts.add(() -> book(roomTypeId, disjointStart, disjointStart.plusDays(2), 1));

        Outcome outcome = runSimultaneously(attempts);

        assertThat(outcome.successes()).isEqualTo(2);
        assertThat(outcome.soldOut()).isEqualTo(THREADS - 1);
        assertThat(bookedRooms(roomTypeId, shared, shared.plusDays(1))).containsExactly(1);
        assertThat(bookedRooms(roomTypeId, shared.minusDays(2), disjointStart.plusDays(2))).allMatch(rooms -> rooms <= 1);
        assertThat(bookedRooms(roomTypeId, disjointStart, disjointStart.plusDays(2))).containsOnly(1);
    }

    private Long book(long roomTypeId, LocalDate in, LocalDate out, int rooms) {
        return bookingService.createBooking(new CreateBookingRequest(propertyId, roomTypeId, in, out, rooms * 2, rooms,
                new GuestRequest("Guest", "guest@example.com", null))).id();
    }

    private record Outcome(int successes, int soldOut) {
    }

    /**
     * Releases all attempts at the same instant and classifies their results. Any failure other than
     * sold-out inventory (for example a lock timeout or deadlock) fails the test.
     */
    private static Outcome runSimultaneously(List<Callable<Long>> attempts) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(attempts.size());
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (Callable<Long> attempt : attempts) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return attempt.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int soldOut = 0;
            for (Future<Long> future : futures) {
                try {
                    assertThat(future.get(30, TimeUnit.SECONDS)).isPositive();
                    successes++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(InsufficientInventoryException.class);
                    soldOut++;
                } catch (java.util.concurrent.TimeoutException ex) {
                    throw new AssertionError("Booking attempt did not finish", ex);
                }
            }
            return new Outcome(successes, soldOut);
        } finally {
            executor.shutdownNow();
        }
    }

    private long roomType(int inventory) {
        return onboardingService.addRoomType(propertyId, new CreateRoomTypeRequest("Room " + UUID.randomUUID(), null,
                2, inventory, new BigDecimal("2000"))).id();
    }

    private List<Integer> bookedRooms(long roomTypeId, LocalDate from, LocalDate to) {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, from, to).stream()
                .map(RoomInventory::getBookedRooms).toList();
    }
}
