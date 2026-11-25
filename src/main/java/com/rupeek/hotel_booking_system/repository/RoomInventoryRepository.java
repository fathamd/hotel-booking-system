package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Per-night inventory rows. All date ranges are half-open: {@code [fromInclusive, toExclusive)}.
 */
@Transactional(readOnly = true)
public interface RoomInventoryRepository extends JpaRepository<RoomInventory, Long> {

    String LOCK_TIMEOUT_MILLIS = "5000";

    /**
     * Locks the inventory rows of a stay for update, always in date order so that concurrent bookings
     * of overlapping ranges acquire locks in the same order and cannot deadlock.
     * Must run inside the caller's transaction, otherwise the lock would be released immediately.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            select i from RoomInventory i
            where i.roomType.id = :roomTypeId
              and i.stayDate >= :fromInclusive and i.stayDate < :toExclusive
            order by i.stayDate asc
            """)
    List<RoomInventory> findForUpdate(@Param("roomTypeId") Long roomTypeId,
                                      @Param("fromInclusive") LocalDate fromInclusive,
                                      @Param("toExclusive") LocalDate toExclusive);

    @Query("""
            select i from RoomInventory i
            where i.roomType.id = :roomTypeId
              and i.stayDate >= :fromInclusive and i.stayDate < :toExclusive
            order by i.stayDate asc
            """)
    List<RoomInventory> findByRoomTypeAndDateRange(@Param("roomTypeId") Long roomTypeId,
                                                   @Param("fromInclusive") LocalDate fromInclusive,
                                                   @Param("toExclusive") LocalDate toExclusive);

    /**
     * Batch availability read for search across many room types.
     */
    @Query("""
            select i from RoomInventory i
            where i.roomType.id in :roomTypeIds
              and i.stayDate >= :fromInclusive and i.stayDate < :toExclusive
            """)
    List<RoomInventory> findByRoomTypeIdsAndDateRange(@Param("roomTypeIds") Collection<Long> roomTypeIds,
                                                      @Param("fromInclusive") LocalDate fromInclusive,
                                                      @Param("toExclusive") LocalDate toExclusive);

    @Query("select max(i.stayDate) from RoomInventory i where i.roomType.id = :roomTypeId")
    Optional<LocalDate> findLastStayDate(@Param("roomTypeId") Long roomTypeId);

    /**
     * Summarises inventory of many room types over {@code [fromInclusive, toExclusive)} in a single grouped
     * query. Room types without any inventory row in the range are absent from the result.
     */
    @Query("""
            select new com.rupeek.hotel_booking_system.repository.RoomTypeAvailability(
                i.roomType.id, count(i), min(i.totalRooms - i.bookedRooms))
            from RoomInventory i
            where i.roomType.id in :roomTypeIds
              and i.stayDate >= :fromInclusive and i.stayDate < :toExclusive
            group by i.roomType.id
            """)
    List<RoomTypeAvailability> summarizeAvailability(@Param("roomTypeIds") Collection<Long> roomTypeIds,
                                                     @Param("fromInclusive") LocalDate fromInclusive,
                                                     @Param("toExclusive") LocalDate toExclusive);
}
