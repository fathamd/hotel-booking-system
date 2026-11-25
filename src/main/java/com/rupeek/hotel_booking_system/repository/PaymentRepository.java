package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByTransactionReference(String transactionReference);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    Optional<Payment> findFirstByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    /**
     * Payments of {@code status} initiated at or before {@code cutoff}, oldest first.
     */
    @Query("""
            select new com.rupeek.hotel_booking_system.repository.PendingPaymentRef(p.id, p.booking.id, p.idempotencyKey)
            from Payment p
            where p.status = :status and p.initiatedAt <= :cutoff
            order by p.initiatedAt asc
            """)
    List<PendingPaymentRef> findRefsByStatusInitiatedBefore(@Param("status") PaymentStatus status,
                                                            @Param("cutoff") LocalDateTime cutoff,
                                                            Pageable pageable);
}
