package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Rooms of one room type for a single night. Bookings reserve one row per night of their stay,
 * which makes the availability check exact per night and gives a natural row to lock on.
 */
@Entity
@Table(name = "room_inventory",
        uniqueConstraints = @UniqueConstraint(name = "uk_room_inventory_room_type_date",
                columnNames = {"room_type_id", "stay_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomInventory extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false, foreignKey = @ForeignKey(name = "fk_room_inventory_room_type"))
    private RoomType roomType;

    @NotNull
    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Min(0)
    @Column(name = "total_rooms", nullable = false)
    private int totalRooms;

    @Min(0)
    @Column(name = "booked_rooms", nullable = false)
    private int bookedRooms;

    public RoomInventory(RoomType roomType, LocalDate stayDate) {
        this.roomType = DomainPreconditions.requireNonNull(roomType, "roomType");
        this.stayDate = DomainPreconditions.requireNonNull(stayDate, "stayDate");
        this.totalRooms = roomType.getTotalInventory();
        this.bookedRooms = 0;
    }

    public int availableRooms() {
        return totalRooms - bookedRooms;
    }

    public boolean canReserve(int rooms) {
        return rooms > 0 && rooms <= availableRooms();
    }

    public void reserve(int rooms) {
        DomainPreconditions.requirePositive(rooms, "rooms");
        if (!canReserve(rooms)) {
            throw new InsufficientInventoryException(roomType.getId(), stayDate, rooms, availableRooms());
        }
        bookedRooms += rooms;
    }

    public void release(int rooms) {
        DomainPreconditions.requirePositive(rooms, "rooms");
        if (rooms > bookedRooms) {
            throw new DomainValidationException("Cannot release " + rooms + " room(s) on " + stayDate
                    + "; only " + bookedRooms + " booked");
        }
        bookedRooms -= rooms;
    }

    public void changeTotalRooms(int newTotalRooms) {
        if (newTotalRooms < bookedRooms) {
            throw new DomainValidationException("Total rooms on " + stayDate + " cannot be below the "
                    + bookedRooms + " room(s) already booked");
        }
        this.totalRooms = newTotalRooms;
    }
}
