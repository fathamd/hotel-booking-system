package com.rupeek.hotel_booking_system.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pessimistic locks are only meaningful inside the caller's transaction, so locking reads must refuse to run without one.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LockingQueryTransactionTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 20);

    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void inventoryLockRequiresTransaction() {
        assertThatThrownBy(() -> roomInventoryRepository.findForUpdate(1L, DATE, DATE.plusDays(1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void bookingLockRequiresTransaction() {
        assertThatThrownBy(() -> bookingRepository.findByIdForUpdate(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
