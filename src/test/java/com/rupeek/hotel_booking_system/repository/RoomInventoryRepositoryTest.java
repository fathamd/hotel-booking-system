package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RoomInventoryRepositoryTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 20);

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private RoomInventoryRepository repository;

    private RoomType deluxe;
    private RoomType standard;

    @BeforeEach
    void setUp() {
        PersistenceTestData data = new PersistenceTestData(entityManager);
        var property = data.property(data.owner("o@x.example"), "P", "Pune", "Baner", 3);
        deluxe = data.roomType(property, "Deluxe", "4000");
        standard = data.roomType(property, "Standard", "2000");
        data.inventory(deluxe, START, START.plusDays(5));
        data.inventory(standard, START, START.plusDays(2));
        data.flushAndClear();
    }

    @Test
    void dateRangeIsHalfOpenAndOrdered() {
        List<RoomInventory> rows = repository.findByRoomTypeAndDateRange(deluxe.getId(), START.plusDays(1), START.plusDays(3));

        assertThat(rows).extracting(RoomInventory::getStayDate).containsExactly(START.plusDays(1), START.plusDays(2));
    }

    @Test
    void lockingReadReturnsRowsInDateOrderWithinTransaction() {
        List<RoomInventory> rows = repository.findForUpdate(deluxe.getId(), START, START.plusDays(5));

        assertThat(rows).extracting(RoomInventory::getStayDate)
                .containsExactly(START, START.plusDays(1), START.plusDays(2), START.plusDays(3), START.plusDays(4));
    }

    @Test
    void batchReadCoversMultipleRoomTypes() {
        List<RoomInventory> rows = repository.findByRoomTypeIdsAndDateRange(
                List.of(deluxe.getId(), standard.getId()), START, START.plusDays(3));

        assertThat(rows).hasSize(5);
    }

    @Test
    void reportsLastStayDateOfInventoryWindow() {
        assertThat(repository.findLastStayDate(deluxe.getId())).contains(START.plusDays(4));
        assertThat(repository.findLastStayDate(-1L)).isEmpty();
    }
}
