package com.rupeek.hotel_booking_system.service.inventory;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_IN;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    private static final StayPeriod STAY = new StayPeriod(CHECK_IN, CHECK_OUT);

    @Mock
    private RoomInventoryRepository roomInventoryRepository;
    @InjectMocks
    private InventoryReservationService reservationService;

    private RoomType roomType;
    private List<RoomInventory> nights;

    @BeforeEach
    void setUp() {
        roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));
        ReflectionTestUtils.setField(roomType, "id", 7L);
        nights = STAY.nights().stream().map(night -> new RoomInventory(roomType, night)).toList();
    }

    @Test
    void reservesTheRoomsOnEveryNightOfTheStay() {
        when(roomInventoryRepository.findForUpdate(7L, CHECK_IN, CHECK_OUT)).thenReturn(nights);

        reservationService.reserve(roomType, STAY, 2);

        assertThat(nights).extracting(RoomInventory::getBookedRooms).containsExactly(2, 2, 2);
    }

    @Test
    void rejectsStayWithNightsNotOnSale() {
        when(roomInventoryRepository.findForUpdate(7L, CHECK_IN, CHECK_OUT)).thenReturn(nights.subList(0, 2));

        assertThatThrownBy(() -> reservationService.reserve(roomType, STAY, 1))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("not available for sale on [" + CHECK_OUT.minusDays(1) + "]");
        assertThat(nights).extracting(RoomInventory::getBookedRooms).containsOnly(0);
    }

    @Test
    void rejectsWholeStayWhenOneNightIsShortWithoutChangingAnyNight() {
        nights.get(1).reserve(4);
        when(roomInventoryRepository.findForUpdate(7L, CHECK_IN, CHECK_OUT)).thenReturn(nights);

        assertThatThrownBy(() -> reservationService.reserve(roomType, STAY, 2))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("1 room(s) available on " + CHECK_IN.plusDays(1) + " but 2 requested");
        assertThat(nights).extracting(RoomInventory::getBookedRooms).containsExactly(0, 4, 0);
    }

    @Test
    void releasesTheRoomsOnEveryNight() {
        nights.forEach(night -> night.reserve(3));
        when(roomInventoryRepository.findForUpdate(7L, CHECK_IN, CHECK_OUT)).thenReturn(nights);

        reservationService.release(roomType, STAY, 2);

        assertThat(nights).extracting(RoomInventory::getBookedRooms).containsOnly(1);
    }
}
