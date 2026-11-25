package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.exception.GuestCapacityExceededException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A category of room within a property (e.g. "Deluxe King"). {@code totalInventory} is the number of
 * physical rooms of this type; per-night availability is tracked by {@link RoomInventory}.
 */
@Entity
@Table(name = "room_type",
        uniqueConstraints = @UniqueConstraint(name = "uk_room_type_property_name", columnNames = {"property_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomType extends BaseEntity {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int MAX_GUESTS_PER_ROOM = 10;
    public static final int MAX_TOTAL_INVENTORY = 1000;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false, foreignKey = @ForeignKey(name = "fk_room_type_property"))
    private Property property;

    @NotBlank
    @Size(max = NAME_MAX)
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Size(max = DESCRIPTION_MAX)
    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Min(1)
    @Max(MAX_GUESTS_PER_ROOM)
    @Column(name = "max_guests", nullable = false)
    private int maxGuests;

    @Min(1)
    @Max(MAX_TOTAL_INVENTORY)
    @Column(name = "total_inventory", nullable = false)
    private int totalInventory;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 10, fraction = MonetaryAmounts.SCALE)
    @Column(name = "base_price", nullable = false, precision = 12, scale = MonetaryAmounts.SCALE)
    private BigDecimal basePrice;

    public RoomType(Property property,
                    String name,
                    String description,
                    int maxGuests,
                    int totalInventory,
                    BigDecimal basePrice) {
        this.property = DomainPreconditions.requireNonNull(property, "property");
        this.name = DomainPreconditions.requireText(name, "room type name", NAME_MAX);
        this.description = DomainPreconditions.optionalText(description, "description", DESCRIPTION_MAX);
        this.maxGuests = DomainPreconditions.requireInRange(maxGuests, 1, MAX_GUESTS_PER_ROOM, "maxGuests");
        this.totalInventory = DomainPreconditions.requireInRange(totalInventory, 1, MAX_TOTAL_INVENTORY, "totalInventory");
        this.basePrice = MonetaryAmounts.requirePositive(basePrice, "basePrice");
    }

    /**
     * Whether {@code guestCount} guests fit into {@code roomCount} rooms of this type,
     * assuming every booked room hosts at least one guest.
     */
    public boolean canAccommodate(int guestCount, int roomCount) {
        return roomCount > 0
                && roomCount <= totalInventory
                && guestCount >= roomCount
                && guestCount <= (long) maxGuests * roomCount;
    }

    public void requireCanAccommodate(int guestCount, int roomCount) {
        if (!canAccommodate(guestCount, roomCount)) {
            throw new GuestCapacityExceededException(guestCount + " guest(s) in " + roomCount + " room(s) cannot be "
                    + "accommodated by room type '" + name + "' (max " + maxGuests + " guest(s) per room, "
                    + totalInventory + " room(s) in total, at least one guest per room)");
        }
    }

    public void changeBasePrice(BigDecimal newBasePrice) {
        this.basePrice = MonetaryAmounts.requirePositive(newBasePrice, "basePrice");
    }
}
