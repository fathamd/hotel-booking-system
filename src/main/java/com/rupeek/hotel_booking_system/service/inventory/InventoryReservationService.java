package com.rupeek.hotel_booking_system.service.inventory;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reserves and releases rooms on the per-night inventory rows of a stay.
 * <p>
 * <b>Locking:</b> the rows of every night of the stay are read with {@code SELECT ... FOR UPDATE}
 * (pessimistic write) in ascending date order, then checked and updated. The locks are held until the
 * caller's transaction ends, so the check and the update are atomic with respect to any other booking of the
 * same nights: a concurrent request blocks until the first commits and then sees its updated counters.
 * Locking in a fixed order means two overlapping stays cannot deadlock, and stays that share no night
 * never block each other.
 * <p>
 * Both operations require an existing transaction so that the locks cover the caller's whole unit of work
 * (for example, saving the booking).
 */
@Service
public class InventoryReservationService {

    private final RoomInventoryRepository roomInventoryRepository;

    public InventoryReservationService(RoomInventoryRepository roomInventoryRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
    }

    /**
     * @throws InsufficientInventoryException if any night is not on sale or has fewer than {@code rooms} free;
     *                                        no inventory row is changed in that case
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(RoomType roomType, StayPeriod stay, int rooms) {
        List<RoomInventory> nights = lockNights(roomType, stay, rooms);
        for (RoomInventory night : nights) {
            if (!night.canReserve(rooms)) {
                throw new InsufficientInventoryException(roomType.getId(), night.getStayDate(), rooms,
                        night.availableRooms());
            }
        }
        nights.forEach(night -> night.reserve(rooms));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(RoomType roomType, StayPeriod stay, int rooms) {
        lockNights(roomType, stay, rooms).forEach(night -> night.release(rooms));
    }

    /**
     * Returns the rooms held by {@code booking} to inventory.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Booking booking) {
        DomainPreconditions.requireNonNull(booking, "booking");
        release(booking.getRoomType(), booking.getStayPeriod(), booking.getRoomCount());
    }

    private List<RoomInventory> lockNights(RoomType roomType, StayPeriod stay, int rooms) {
        DomainPreconditions.requireNonNull(roomType, "roomType");
        DomainPreconditions.requireNonNull(stay, "stay");
        DomainPreconditions.requirePositive(rooms, "rooms");
        List<RoomInventory> nights = roomInventoryRepository.findForUpdate(roomType.getId(), stay.checkIn(),
                stay.checkOut());
        if (nights.size() != stay.numberOfNights()) {
            Set<LocalDate> stocked = nights.stream().map(RoomInventory::getStayDate).collect(Collectors.toSet());
            List<LocalDate> missing = stay.nights().stream().filter(night -> !stocked.contains(night)).toList();
            throw new InsufficientInventoryException(roomType.getId(), missing);
        }
        return nights;
    }
}
