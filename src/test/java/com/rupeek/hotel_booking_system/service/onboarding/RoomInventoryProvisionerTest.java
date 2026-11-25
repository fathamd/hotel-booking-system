package com.rupeek.hotel_booking_system.service.onboarding;

import com.rupeek.hotel_booking_system.config.InventoryProperties;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomInventoryProvisionerTest {

    @Mock
    private RoomInventoryRepository roomInventoryRepository;

    @Test
    @SuppressWarnings("unchecked")
    void createsOneFullyAvailableRowPerNightFromTodayThroughTheHorizon() {
        Clock clock = Clock.fixed(DomainFixtures.NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        RoomInventoryProvisioner provisioner =
                new RoomInventoryProvisioner(roomInventoryRepository, new InventoryProperties(3), clock);
        RoomType roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));

        provisioner.provision(roomType);

        ArgumentCaptor<List<RoomInventory>> rows = ArgumentCaptor.forClass(List.class);
        verify(roomInventoryRepository).saveAll(rows.capture());
        LocalDate today = DomainFixtures.NOW.toLocalDate();
        assertThat(rows.getValue()).extracting(RoomInventory::getStayDate)
                .containsExactly(today, today.plusDays(1), today.plusDays(2));
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getRoomType()).isSameAs(roomType);
            assertThat(row.availableRooms()).isEqualTo(roomType.getTotalInventory());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void extendHorizonAddsOnlyTheNightsAfterTheLastProvisionedOne() {
        RoomInventoryProvisioner provisioner = provisionerWithHorizon(5);
        RoomType roomType = roomTypeWithId();
        LocalDate today = DomainFixtures.NOW.toLocalDate();
        when(roomInventoryRepository.findLastStayDate(20L)).thenReturn(Optional.of(today.plusDays(2)));

        assertThat(provisioner.extendHorizon(roomType)).isEqualTo(2);

        ArgumentCaptor<List<RoomInventory>> rows = ArgumentCaptor.forClass(List.class);
        verify(roomInventoryRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(RoomInventory::getStayDate)
                .containsExactly(today.plusDays(3), today.plusDays(4));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extendHorizonStartsFromTodayWhenTheLastNightIsInThePast() {
        RoomInventoryProvisioner provisioner = provisionerWithHorizon(2);
        LocalDate today = DomainFixtures.NOW.toLocalDate();
        when(roomInventoryRepository.findLastStayDate(20L)).thenReturn(Optional.of(today.minusDays(10)));

        assertThat(provisioner.extendHorizon(roomTypeWithId())).isEqualTo(2);

        ArgumentCaptor<List<RoomInventory>> rows = ArgumentCaptor.forClass(List.class);
        verify(roomInventoryRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(RoomInventory::getStayDate).containsExactly(today, today.plusDays(1));
    }

    @Test
    void extendHorizonDoesNothingWhenTheHorizonIsAlreadyCovered() {
        RoomInventoryProvisioner provisioner = provisionerWithHorizon(3);
        when(roomInventoryRepository.findLastStayDate(20L))
                .thenReturn(Optional.of(DomainFixtures.NOW.toLocalDate().plusDays(2)));

        assertThat(provisioner.extendHorizon(roomTypeWithId())).isZero();
        verify(roomInventoryRepository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void todayIsTakenInThePropertysTimeZone() {
        // 2026-12-01T20:00Z is already 2 December in Kolkata.
        Clock lateUtc = Clock.fixed(Instant.parse("2026-12-01T20:00:00Z"), ZoneOffset.UTC);
        RoomInventoryProvisioner provisioner =
                new RoomInventoryProvisioner(roomInventoryRepository, new InventoryProperties(1), lateUtc);
        Property kolkata = new Property(DomainFixtures.owner(), "Sunrise Kolkata",
                new Location("1 Park St", "Park Street", "Kolkata"), 4, Set.of(), CancellationPolicyType.FLEXIBLE,
                ZoneId.of("Asia/Kolkata"));

        provisioner.provision(DomainFixtures.roomType(kolkata));

        ArgumentCaptor<List<RoomInventory>> rows = ArgumentCaptor.forClass(List.class);
        verify(roomInventoryRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(RoomInventory::getStayDate).containsExactly(LocalDate.of(2026, 12, 2));
    }

    private RoomInventoryProvisioner provisionerWithHorizon(int days) {
        Clock clock = Clock.fixed(DomainFixtures.NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        return new RoomInventoryProvisioner(roomInventoryRepository, new InventoryProperties(days), clock);
    }

    private static RoomType roomTypeWithId() {
        RoomType roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));
        ReflectionTestUtils.setField(roomType, "id", 20L);
        return roomType;
    }
}
