package com.rupeek.hotel_booking_system.service.maintenance;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Expires {@code PENDING_PAYMENT} bookings whose hold ran out and returns their rooms to inventory.
 * <p>
 * Each booking is expired in its own short transaction that locks the booking row and re-checks its state, so a
 * payment or cancellation running at the same moment either wins the lock and changes the state first, or waits
 * and then finds the booking expired. A booking with a payment still awaiting the gateway is skipped; payment
 * reconciliation settles it, and a late success must still be able to confirm it.
 */
@Service
public class BookingHoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(BookingHoldExpiryService.class);

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryReservationService inventoryReservationService;
    private final TransactionOperations transactions;
    private final Clock clock;

    public BookingHoldExpiryService(BookingRepository bookingRepository,
                                    PaymentRepository paymentRepository,
                                    InventoryReservationService inventoryReservationService,
                                    TransactionOperations transactions,
                                    Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @return the number of bookings expired
     */
    public int expireOverdueHolds(int batchSize) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> overdue = bookingRepository.findIdsByStatusWithHoldExpiredAt(BookingStatus.PENDING_PAYMENT, now,
                PageRequest.of(0, batchSize));
        int expired = 0;
        for (Long bookingId : overdue) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> expire(bookingId)))) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not expire booking {}; will retry on the next run", bookingId, ex);
            }
        }
        return expired;
    }

    private boolean expire(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (booking == null || !booking.isHoldExpired(now)
                || paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)) {
            return false;
        }
        booking.expire(now);
        inventoryReservationService.release(booking);
        return true;
    }
}
