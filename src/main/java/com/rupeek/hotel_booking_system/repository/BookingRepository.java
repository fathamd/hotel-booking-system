package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Date ranges are half-open {@code [checkIn, checkOut)}: two stays overlap when
 * {@code existing.checkIn < requested.checkOut && existing.checkOut > requested.checkIn}.
 */
@Transactional(readOnly = true)
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Booking with its property and room type loaded, for building responses outside a transaction.
     */
    @EntityGraph(attributePaths = {"property", "roomType"})
    @Query("select b from Booking b where b.id = :bookingId")
    Optional<Booking> findDetailedById(@Param("bookingId") Long bookingId);

    /**
     * Locks a booking row so payment, cancellation and expiry cannot change it concurrently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = RoomInventoryRepository.LOCK_TIMEOUT_MILLIS))
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select b from Booking b where b.id = :bookingId")
    Optional<Booking> findByIdForUpdate(@Param("bookingId") Long bookingId);

    @Query("""
            select b from Booking b
            where b.roomType.id = :roomTypeId
              and b.stayPeriod.checkIn < :checkOut
              and b.stayPeriod.checkOut > :checkIn
            order by b.stayPeriod.checkIn asc, b.id asc
            """)
    List<Booking> findOverlapping(@Param("roomTypeId") Long roomTypeId,
                                  @Param("checkIn") LocalDate checkIn,
                                  @Param("checkOut") LocalDate checkOut);

    @Query("""
            select b from Booking b
            where b.roomType.id = :roomTypeId
              and b.status in :statuses
              and b.stayPeriod.checkIn < :checkOut
              and b.stayPeriod.checkOut > :checkIn
            order by b.stayPeriod.checkIn asc, b.id asc
            """)
    List<Booking> findOverlappingWithStatusIn(@Param("roomTypeId") Long roomTypeId,
                                              @Param("checkIn") LocalDate checkIn,
                                              @Param("checkOut") LocalDate checkOut,
                                              @Param("statuses") Collection<BookingStatus> statuses);

    /**
     * Overlapping bookings that currently occupy rooms. Which statuses hold inventory is decided by
     * {@link BookingStatus#inventoryHoldingStatuses()}; cancelled, payment-failed and expired bookings are excluded.
     */
    default List<Booking> findInventoryConsuming(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        return findOverlappingWithStatusIn(roomTypeId, checkIn, checkOut, BookingStatus.inventoryHoldingStatuses());
    }

    @Query("select p.booking from Payment p where p.transactionReference = :transactionReference")
    Optional<Booking> findByPaymentReference(@Param("transactionReference") String transactionReference);

    @Query("""
            select b from Booking b
            where b.status = :status and b.holdExpiresAt <= :cutoff
            order by b.holdExpiresAt asc
            """)
    List<Booking> findByStatusWithHoldExpiredAt(@Param("status") BookingStatus status,
                                                @Param("cutoff") LocalDateTime cutoff);

    /**
     * Ids of bookings whose hold ran out by {@code cutoff}, oldest first, for the expiry job to process one by one.
     */
    @Query("""
            select b.id from Booking b
            where b.status = :status and b.holdExpiresAt <= :cutoff
            order by b.holdExpiresAt asc
            """)
    List<Long> findIdsByStatusWithHoldExpiredAt(@Param("status") BookingStatus status,
                                                @Param("cutoff") LocalDateTime cutoff,
                                                Pageable pageable);

    @Query("select b from Booking b where b.guest.email = :email order by b.createdAt desc")
    List<Booking> findByGuestEmail(@Param("email") String email);

    /**
     * Bookings of a property, optionally restricted to one status, with property and room type fetched.
     */
    @EntityGraph(attributePaths = {"property", "roomType"})
    @Query(value = """
            select b from Booking b
            where b.property.id = :propertyId and (:status is null or b.status = :status)
            """,
            countQuery = """
            select count(b) from Booking b
            where b.property.id = :propertyId and (:status is null or b.status = :status)
            """)
    Page<Booking> findByProperty(@Param("propertyId") Long propertyId,
                                 @Param("status") BookingStatus status,
                                 Pageable pageable);
}
