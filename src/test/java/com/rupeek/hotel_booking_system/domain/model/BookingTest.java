package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.BookingHoldExpiredException;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.GuestCapacityExceededException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private final RoomType roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));

    @Test
    void newBookingIsPendingAndHoldsInventory() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.holdsInventory()).isTrue();
        assertThat(booking.getProperty()).isSameAs(roomType.getProperty());
        assertThat(booking.getTotalPrice()).isEqualByComparingTo("7500.00");
    }

    @Test
    void rejectsGuestsBeyondRoomCapacity() {
        assertThatThrownBy(() -> Booking.createPending(roomType, DomainFixtures.guest(), DomainFixtures.stay(),
                3, 1, new BigDecimal("100"), NOW.plusMinutes(15)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNonPositiveCountsAndPrice() {
        assertThatThrownBy(() -> Booking.createPending(roomType, DomainFixtures.guest(), DomainFixtures.stay(),
                0, 1, new BigDecimal("100"), NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Booking.createPending(roomType, DomainFixtures.guest(), DomainFixtures.stay(),
                1, 1, BigDecimal.ZERO, NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void confirmWithinHoldMovesToConfirmed() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        booking.confirm(NOW.plusMinutes(5));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getConfirmedAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(booking.holdsInventory()).isTrue();
    }

    @Test
    void confirmAfterHoldExpiryIsRejected() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        assertThatThrownBy(() -> booking.confirm(NOW.plusMinutes(15)))
                .isInstanceOf(BookingHoldExpiredException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void paymentFailureReleasesInventoryAndIsTerminal() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        booking.markPaymentFailed();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(booking.holdsInventory()).isFalse();
        assertThatThrownBy(() -> booking.confirm(NOW)).isInstanceOf(InvalidBookingStateException.class);
        assertThatThrownBy(() -> booking.cancel(NOW.atZone(ZoneOffset.UTC))).isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void expireRequiresElapsedHold() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        assertThatThrownBy(() -> booking.expire(NOW.plusMinutes(14))).isInstanceOf(InvalidBookingStateException.class);

        booking.expire(NOW.plusMinutes(15));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.holdsInventory()).isFalse();
    }

    @Test
    void confirmedBookingCannotExpireOrFailPayment() {
        Booking booking = DomainFixtures.pendingBooking(roomType);
        booking.confirm(NOW);

        assertThatThrownBy(() -> booking.expire(NOW.plusDays(1))).isInstanceOf(InvalidBookingStateException.class);
        assertThatThrownBy(booking::markPaymentFailed).isInstanceOf(InvalidBookingStateException.class);
        assertThatThrownBy(() -> booking.confirm(NOW)).isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void confirmedBookingCanBeCancelledBeforeCheckIn() {
        Booking booking = DomainFixtures.pendingBooking(roomType);
        booking.confirm(NOW);
        LocalDateTime justBeforeCheckIn = booking.getStayPeriod().checkInDateTime().minusSeconds(1);

        booking.cancel(justBeforeCheckIn.atZone(ZoneOffset.UTC));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelledAt()).isEqualTo(justBeforeCheckIn);
        assertThat(booking.holdsInventory()).isFalse();
        assertThatThrownBy(() -> booking.cancel(justBeforeCheckIn.atZone(ZoneOffset.UTC))).isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void confirmedBookingCannotBeCancelledAtOrAfterCheckIn() {
        Booking booking = DomainFixtures.pendingBooking(roomType);
        booking.confirm(NOW);

        assertThatThrownBy(() -> booking.cancel(booking.getStayPeriod().checkInDateTime().atZone(ZoneOffset.UTC)))
                .isInstanceOf(InvalidBookingStateException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void checkInDeadlineIsTheCheckInTimeInThePropertysTimeZone() {
        Property kolkata = new Property(DomainFixtures.owner(), "Sunrise Kolkata",
                new Location("1 Park St", "Park Street", "Kolkata"), 4, Set.of(), CancellationPolicyType.FLEXIBLE,
                ZoneId.of("Asia/Kolkata"));
        Booking booking = DomainFixtures.pendingBooking(DomainFixtures.roomType(kolkata));
        booking.confirm(NOW);
        Instant checkIn = booking.getStayPeriod().checkInDateTime().atZone(ZoneId.of("Asia/Kolkata")).toInstant();

        assertThat(booking.checkInInstant()).isEqualTo(checkIn);
        // Check-in has passed in Kolkata even though the same wall-clock time has not yet been reached in UTC.
        assertThatThrownBy(() -> booking.cancel(checkIn.atZone(ZoneOffset.UTC)))
                .isInstanceOf(InvalidBookingStateException.class);
        booking.cancel(checkIn.minusSeconds(1).atZone(ZoneOffset.UTC));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void pendingBookingCanBeCancelled() {
        Booking booking = DomainFixtures.pendingBooking(roomType);

        booking.cancel(NOW.atZone(ZoneOffset.UTC));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void terminalBookingsRejectEveryFurtherTransition() {
        Booking failed = DomainFixtures.pendingBooking(roomType);
        failed.markPaymentFailed();
        Booking cancelled = DomainFixtures.pendingBooking(roomType);
        cancelled.cancel(NOW.atZone(ZoneOffset.UTC));
        Booking expired = DomainFixtures.pendingBooking(roomType);
        expired.expire(NOW.plusMinutes(15));

        for (Booking booking : new Booking[]{failed, cancelled, expired}) {
            assertThat(booking.holdsInventory()).isFalse();
            assertThatThrownBy(() -> booking.confirm(NOW)).isInstanceOf(InvalidBookingStateException.class);
            assertThatThrownBy(booking::markPaymentFailed).isInstanceOf(InvalidBookingStateException.class);
            assertThatThrownBy(() -> booking.cancel(NOW.atZone(ZoneOffset.UTC))).isInstanceOf(InvalidBookingStateException.class);
            assertThatThrownBy(() -> booking.expire(NOW.plusDays(1))).isInstanceOf(InvalidBookingStateException.class);
        }
    }

    @Test
    void confirmedBookingCannotBeConfirmedAgain() {
        Booking booking = DomainFixtures.pendingBooking(roomType);
        booking.confirm(NOW);

        assertThatThrownBy(() -> booking.confirm(NOW))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessage("Booking cannot transition from CONFIRMED to CONFIRMED");
        assertThat(booking.holdsInventory()).isTrue();
    }

    @Test
    void capacityViolationIsAGuestCapacityError() {
        assertThatThrownBy(() -> Booking.createPending(roomType, DomainFixtures.guest(), DomainFixtures.stay(),
                1, 2, new BigDecimal("100"), NOW.plusMinutes(15)))
                .isInstanceOf(GuestCapacityExceededException.class);
    }
}
