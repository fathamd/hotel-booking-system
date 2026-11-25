package com.rupeek.hotel_booking_system.service.maintenance;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingHoldExpiryServiceTest {

    private static final long BOOKING_ID = 30L;
    private static final LocalDateTime AFTER_HOLD = DomainFixtures.NOW.plusMinutes(16);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private InventoryReservationService inventoryReservationService;

    private Booking booking;

    @BeforeEach
    void setUp() {
        booking = DomainFixtures.pendingBooking();
        ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
    }

    private BookingHoldExpiryService serviceAt(LocalDateTime now) {
        return new BookingHoldExpiryService(bookingRepository, paymentRepository, inventoryReservationService,
                TransactionOperations.withoutTransaction(), Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private void givenOverdue(LocalDateTime now) {
        when(bookingRepository.findIdsByStatusWithHoldExpiredAt(eq(BookingStatus.PENDING_PAYMENT), eq(now), any()))
                .thenReturn(List.of(BOOKING_ID));
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
    }

    @Test
    void expiresOverdueHoldsAndReleasesTheirRooms() {
        givenOverdue(AFTER_HOLD);

        assertThat(serviceAt(AFTER_HOLD).expireOverdueHolds(10)).isEqualTo(1);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(inventoryReservationService).release(booking);
    }

    @Test
    void skipsABookingWhosePaymentIsStillAwaitingTheGateway() {
        givenOverdue(AFTER_HOLD);
        when(paymentRepository.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.PENDING)).thenReturn(true);

        assertThat(serviceAt(AFTER_HOLD).expireOverdueHolds(10)).isZero();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(inventoryReservationService, never()).release(any());
    }

    @Test
    void skipsABookingPaidBetweenTheQueryAndTheLock() {
        givenOverdue(AFTER_HOLD);
        booking.confirm(DomainFixtures.NOW);

        assertThat(serviceAt(AFTER_HOLD).expireOverdueHolds(10)).isZero();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(inventoryReservationService, never()).release(any());
    }

    @Test
    void oneFailureDoesNotStopTheBatch() {
        Booking second = DomainFixtures.pendingBooking();
        ReflectionTestUtils.setField(second, "id", 31L);
        when(bookingRepository.findIdsByStatusWithHoldExpiredAt(eq(BookingStatus.PENDING_PAYMENT), eq(AFTER_HOLD),
                any())).thenReturn(List.of(BOOKING_ID, 31L));
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenThrow(new IllegalStateException("lock timeout"));
        when(bookingRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(second));

        assertThat(serviceAt(AFTER_HOLD).expireOverdueHolds(10)).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }
}
