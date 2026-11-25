package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BookingRepositoryTest {

    private static final LocalDate D20 = LocalDate.of(2026, 12, 20);
    private static final LocalDate D23 = LocalDate.of(2026, 12, 23);

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;

    private PersistenceTestData data;
    private RoomType deluxe;
    private RoomType standard;

    @BeforeEach
    void setUp() {
        data = new PersistenceTestData(entityManager);
        var property = data.property(data.owner("o@x.example"), "P", "Goa", "Calangute", 4);
        deluxe = data.roomType(property, "Deluxe", "4000");
        standard = data.roomType(property, "Standard", "2000");
    }

    @Test
    void overlapUsesHalfOpenRanges() {
        Booking existing = data.pendingBooking(deluxe, D20, D23);
        data.pendingBooking(standard, D20, D23);
        data.flushAndClear();

        Long roomTypeId = deluxe.getId();
        assertThat(bookingRepository.findOverlapping(roomTypeId, D20.minusDays(2), D20)).isEmpty();
        assertThat(bookingRepository.findOverlapping(roomTypeId, D23, D23.plusDays(2))).isEmpty();
        assertThat(bookingRepository.findOverlapping(roomTypeId, D23.minusDays(1), D23.plusDays(1)))
                .extracting(Booking::getId).containsExactly(existing.getId());
        assertThat(bookingRepository.findOverlapping(roomTypeId, D20.minusDays(1), D23.plusDays(3)))
                .extracting(Booking::getId).containsExactly(existing.getId());
        assertThat(bookingRepository.findOverlapping(roomTypeId, D20.plusDays(1), D20.plusDays(2)))
                .extracting(Booking::getId).containsExactly(existing.getId());
    }

    @Test
    void onlyPendingAndConfirmedBookingsConsumeInventory() {
        Booking pending = data.pendingBooking(deluxe, D20, D23);
        Booking confirmed = data.pendingBooking(deluxe, D20, D23);
        confirmed.confirm(NOW);
        Booking cancelled = data.pendingBooking(deluxe, D20, D23);
        cancelled.cancel(NOW.atZone(ZoneOffset.UTC));
        Booking paymentFailed = data.pendingBooking(deluxe, D20, D23);
        paymentFailed.markPaymentFailed();
        Booking expired = data.pendingBooking(deluxe, D20, D23);
        expired.expire(NOW.plusHours(1));
        data.flushAndClear();

        assertThat(bookingRepository.findInventoryConsuming(deluxe.getId(), D20, D23))
                .extracting(Booking::getId)
                .containsExactlyInAnyOrder(pending.getId(), confirmed.getId());
        assertThat(bookingRepository.findOverlapping(deluxe.getId(), D20, D23)).hasSize(5);
    }

    @Test
    void findsBookingByPaymentReference() {
        Booking booking = data.pendingBooking(deluxe, D20, D23);
        Payment payment = Payment.initiate(booking, PaymentMethod.UPI, "idem-ref", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);
        payment.markSucceeded("UPI-TXN-42", NOW);
        entityManager.persist(payment);
        data.flushAndClear();

        assertThat(bookingRepository.findByPaymentReference("UPI-TXN-42"))
                .get().extracting(Booking::getId).isEqualTo(booking.getId());
        assertThat(bookingRepository.findByPaymentReference("UNKNOWN")).isEmpty();
    }

    @Test
    void detailedLookupLoadsPropertyAndRoomType() {
        Booking booking = data.pendingBooking(deluxe, D20, D23);
        data.flushAndClear();

        Booking found = bookingRepository.findDetailedById(booking.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getProperty())).isTrue();
        assertThat(Hibernate.isInitialized(found.getRoomType())).isTrue();
        assertThat(bookingRepository.findDetailedById(-1L)).isEmpty();
    }

    @Test
    void lockingLookupFindsBookingInsideTransaction() {
        Booking booking = data.pendingBooking(deluxe, D20, D23);
        data.flushAndClear();

        assertThat(bookingRepository.findByIdForUpdate(booking.getId())).isPresent();
    }

    @Test
    void findsPendingBookingsWhoseHoldHasExpired() {
        Booking pending = data.pendingBooking(deluxe, D20, D23);
        Booking confirmed = data.pendingBooking(deluxe, D20, D23);
        confirmed.confirm(NOW);
        data.flushAndClear();

        assertThat(bookingRepository.findByStatusWithHoldExpiredAt(BookingStatus.PENDING_PAYMENT, NOW)).isEmpty();
        assertThat(bookingRepository.findByStatusWithHoldExpiredAt(BookingStatus.PENDING_PAYMENT, NOW.plusMinutes(15)))
                .extracting(Booking::getId).containsExactly(pending.getId());
    }

    @Test
    void paymentAndRefundLookups() {
        Booking booking = data.pendingBooking(deluxe, D20, D23);
        Payment failed = Payment.initiate(booking, PaymentMethod.CARD, "idem-a", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);
        failed.markFailed(null, "declined", NOW);
        entityManager.persist(failed);
        Payment succeeded = Payment.initiate(booking, PaymentMethod.CARD, "idem-b", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);
        succeeded.markSucceeded("CARD-TXN-1", NOW);
        entityManager.persist(succeeded);
        entityManager.persist(Refund.initiate(succeeded, new BigDecimal("500"), DomainFixtures.NOW));
        data.flushAndClear();

        assertThat(paymentRepository.findByIdempotencyKey("idem-a")).get()
                .extracting(Payment::getStatus).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentRepository.findFirstByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCEEDED)).get()
                .extracting(Payment::getTransactionReference).isEqualTo("CARD-TXN-1");
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId())).hasSize(2);
        assertThat(refundRepository.findByBookingId(booking.getId())).hasSize(1);
        assertThat(refundRepository.findByPaymentId(succeeded.getId())).isPresent();
    }
}
