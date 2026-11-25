package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPaymentId(Long paymentId);

    @Query("select r from Refund r where r.payment.booking.id = :bookingId order by r.createdAt desc")
    List<Refund> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Locks a refund row so that concurrent attempts to process or retry it are serialised.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = RoomInventoryRepository.LOCK_TIMEOUT_MILLIS))
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select r from Refund r join fetch r.payment where r.id = :refundId")
    Optional<Refund> findByIdForUpdate(@Param("refundId") Long refundId);

    @Query("""
            select r.id from Refund r
            where r.status = :status and r.requestedAt <= :cutoff
            order by r.requestedAt asc
            """)
    List<Long> findIdsByStatusRequestedBefore(@Param("status") RefundStatus status,
                                              @Param("cutoff") LocalDateTime cutoff,
                                              Pageable pageable);
}
