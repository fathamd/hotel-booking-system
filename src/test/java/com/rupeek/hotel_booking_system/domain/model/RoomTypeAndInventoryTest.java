package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomTypeAndInventoryTest {

    private RoomType roomType;
    private RoomInventory inventory;

    @BeforeEach
    void setUp() {
        roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));
        inventory = new RoomInventory(roomType, DomainFixtures.CHECK_IN);
    }

    @Test
    void roomTypeRejectsInvalidValues() {
        Property property = roomType.getProperty();
        assertThatThrownBy(() -> new RoomType(property, "X", null, 0, 1, BigDecimal.TEN))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RoomType(property, "X", null, 1, 0, BigDecimal.TEN))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RoomType(property, "X", null, 1, 1, BigDecimal.ZERO))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void basePriceIsNormalisedToTwoDecimals() {
        RoomType type = new RoomType(roomType.getProperty(), "Suite", null, 2, 1, new BigDecimal("1999.999"));
        assertThat(type.getBasePrice()).isEqualByComparingTo("2000.00").hasScaleOf(2);
    }

    @Test
    void canAccommodateChecksGuestsPerRoomAndInventory() {
        assertThat(roomType.canAccommodate(2, 1)).isTrue();
        assertThat(roomType.canAccommodate(3, 1)).isFalse();
        assertThat(roomType.canAccommodate(10, 5)).isTrue();
        assertThat(roomType.canAccommodate(6, 6)).isFalse();
        assertThat(roomType.canAccommodate(1, 2)).isFalse();
    }

    @Test
    void newInventoryStartsWithAllRoomsAvailable() {
        assertThat(inventory.getTotalRooms()).isEqualTo(5);
        assertThat(inventory.availableRooms()).isEqualTo(5);
    }

    @Test
    void reserveAndReleaseAdjustAvailability() {
        inventory.reserve(3);
        assertThat(inventory.availableRooms()).isEqualTo(2);

        inventory.release(2);
        assertThat(inventory.getBookedRooms()).isEqualTo(1);
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        inventory.reserve(4);

        assertThatThrownBy(() -> inventory.reserve(2)).isInstanceOf(InsufficientInventoryException.class);
        assertThat(inventory.getBookedRooms()).isEqualTo(4);
    }

    @Test
    void cannotReleaseMoreThanBooked() {
        inventory.reserve(1);

        assertThatThrownBy(() -> inventory.release(2)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void totalRoomsCannotDropBelowBookedRooms() {
        inventory.reserve(3);

        assertThatThrownBy(() -> inventory.changeTotalRooms(2)).isInstanceOf(DomainValidationException.class);
        inventory.changeTotalRooms(3);
        assertThat(inventory.availableRooms()).isZero();
    }
}
