package com.rupeek.hotel_booking_system.service.inventory;

import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_IN;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomAvailabilityCalculatorTest {

    private static final StayPeriod THREE_NIGHTS = new StayPeriod(CHECK_IN, CHECK_OUT);

    @Mock
    private RoomInventoryRepository roomInventoryRepository;
    @InjectMocks
    private RoomAvailabilityCalculator calculator;

    @Test
    void usesTheMinimumFreeRoomsWhenEveryNightIsCovered() {
        when(roomInventoryRepository.summarizeAvailability(List.of(1L), CHECK_IN, CHECK_OUT))
                .thenReturn(List.of(new RoomTypeAvailability(1L, 3L, 2)));

        assertThat(calculator.availableRooms(List.of(1L), THREE_NIGHTS)).containsExactly(Map.entry(1L, 2));
    }

    @Test
    void treatsPartiallyCoveredStayAsUnavailable() {
        when(roomInventoryRepository.summarizeAvailability(List.of(1L), CHECK_IN, CHECK_OUT))
                .thenReturn(List.of(new RoomTypeAvailability(1L, 2L, 5)));

        assertThat(calculator.availableRooms(List.of(1L), THREE_NIGHTS)).containsExactly(Map.entry(1L, 0));
    }

    @Test
    void reportsZeroForRoomTypesWithoutInventoryRows() {
        when(roomInventoryRepository.summarizeAvailability(List.of(1L, 2L), CHECK_IN, CHECK_OUT))
                .thenReturn(List.of(new RoomTypeAvailability(1L, 3L, 4)));

        assertThat(calculator.availableRooms(List.of(1L, 2L), THREE_NIGHTS))
                .containsOnly(Map.entry(1L, 4), Map.entry(2L, 0));
    }

    @Test
    void neverReportsNegativeAvailability() {
        when(roomInventoryRepository.summarizeAvailability(List.of(1L), CHECK_IN, CHECK_OUT))
                .thenReturn(List.of(new RoomTypeAvailability(1L, 3L, -1)));

        assertThat(calculator.availableRooms(List.of(1L), THREE_NIGHTS)).containsExactly(Map.entry(1L, 0));
    }

    @Test
    void skipsTheQueryWhenThereAreNoCandidates() {
        assertThat(calculator.availableRooms(List.of(), THREE_NIGHTS)).isEmpty();
        verifyNoInteractions(roomInventoryRepository);
    }
}
