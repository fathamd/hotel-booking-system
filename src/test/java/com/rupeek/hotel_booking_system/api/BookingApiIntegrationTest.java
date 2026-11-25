package com.rupeek.hotel_booking_system.api;

import com.jayway.jsonpath.JsonPath;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.RoomInventory;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hotel.inventory.horizon-days=30")
class BookingApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;
    @Autowired
    private BookingRepository bookingRepository;

    private MockMvc mockMvc;
    private final LocalDate checkIn = LocalDate.now().plusDays(3);
    private final LocalDate checkOut = checkIn.plusDays(3);
    private long propertyId;
    private long roomTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ownerId = onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
        propertyId = onboardProperty(ownerId);
        roomTypeId = onboardRoomType(propertyId, 2, 5);
    }

    @Test
    void createsPendingBookingAndReservesEveryNightOfTheStay() throws Exception {
        String body = createBooking(propertyId, roomTypeId, checkIn, checkOut, 2, 1)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/bookings/")))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.nights").value(3))
                .andExpect(jsonPath("$.totalPrice").value(7500.00))
                .andExpect(jsonPath("$.holdExpiresAt").value(notNullValue()))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.guest.email").value("asha@example.com"))
                .andReturn().getResponse().getContentAsString();

        assertThat(bookedRooms(checkIn.minusDays(1), checkOut.plusDays(1))).containsExactly(0, 1, 1, 1, 0);

        long bookingId = ((Number) JsonPath.read(body, "$.id")).longValue();
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId))
                .andExpect(jsonPath("$.propertyId").value(propertyId))
                .andExpect(jsonPath("$.roomTypeId").value(roomTypeId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void reservesAndPricesMultipleRoomsInOneBooking() throws Exception {
        createBooking(propertyId, roomTypeId, checkIn, checkOut, 5, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rooms").value(3))
                .andExpect(jsonPath("$.totalPrice").value(22500.00));

        assertThat(bookedRooms(checkIn, checkOut)).containsOnly(3);

        createBooking(propertyId, roomTypeId, checkIn, checkOut, 3, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2 room(s) available")));
        assertThat(bookedRooms(checkIn, checkOut)).containsOnly(3);
    }

    @Test
    void overlappingBookingsNeverExceedInventory() throws Exception {
        long twoRooms = onboardRoomType(propertyId, 2, 2);
        LocalDate d = checkIn;

        createBooking(propertyId, twoRooms, d, d.plusDays(3), 2, 1).andExpect(status().isCreated());
        createBooking(propertyId, twoRooms, d.plusDays(1), d.plusDays(4), 2, 1).andExpect(status().isCreated());
        createBooking(propertyId, twoRooms, d.plusDays(2), d.plusDays(3), 2, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("0 room(s) available on " + d.plusDays(2))));
        createBooking(propertyId, twoRooms, d.plusDays(3), d.plusDays(5), 2, 1).andExpect(status().isCreated());

        List<RoomInventory> nights = roomInventoryRepository.findByRoomTypeAndDateRange(twoRooms, d, d.plusDays(5));
        assertThat(nights).extracting(RoomInventory::getBookedRooms).containsExactly(1, 2, 2, 2, 1);
        List<Booking> holding = bookingRepository.findInventoryConsuming(twoRooms, d, d.plusDays(5));
        for (RoomInventory night : nights) {
            int roomsHeldByBookings = holding.stream()
                    .filter(booking -> booking.getStayPeriod().includesNight(night.getStayDate()))
                    .mapToInt(Booking::getRoomCount).sum();
            assertThat(roomsHeldByBookings).isEqualTo(night.getBookedRooms()).isLessThanOrEqualTo(night.getTotalRooms());
        }
    }

    @Test
    void rejectsStayBeyondProvisionedInventory() throws Exception {
        LocalDate lastProvisionedNight = LocalDate.now().plusDays(29);

        createBooking(propertyId, roomTypeId, lastProvisionedNight, lastProvisionedNight.plusDays(2), 2, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not available for sale")));
    }

    @Test
    void listsBookingsOfAProperty() throws Exception {
        createBooking(propertyId, roomTypeId, checkIn.plusDays(1), checkOut, 2, 1).andExpect(status().isCreated());
        createBooking(propertyId, roomTypeId, checkIn, checkOut, 2, 1).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/properties/{id}/bookings", propertyId).param("status", "PENDING_PAYMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].checkIn").value(checkIn.toString()))
                .andExpect(jsonPath("$.content[1].checkIn").value(checkIn.plusDays(1).toString()));
        mockMvc.perform(get("/api/v1/properties/{id}/bookings", propertyId).param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/properties/{id}/bookings", propertyId).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/properties/{id}/bookings", propertyId).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/properties/{id}/bookings", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidBookingRequests() throws Exception {
        mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("propertyId")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("checkIn")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("guests")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("guest")));

        createBooking(propertyId, roomTypeId, checkOut, checkIn, 2, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("checkOut must be after checkIn"));
        createBooking(propertyId, roomTypeId, LocalDate.now().minusDays(1), checkOut, 2, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("checkIn cannot be in the past"));
        createBooking(propertyId, roomTypeId, checkIn, checkOut, 3, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cannot be accommodated")));
        createBooking(propertyId, roomTypeId, checkIn, checkOut, 2, 11).andExpect(status().isBadRequest());
        createBooking(propertyId, roomTypeId, checkIn, checkOut, 0, 1).andExpect(status().isBadRequest());

        assertThat(bookedRooms(checkIn, checkOut)).containsOnly(0);
    }

    @Test
    void rejectsUnknownOrMismatchedPropertyAndRoomType() throws Exception {
        long otherProperty = onboardProperty(onboardingService.createOwner(
                new CreateOwnerRequest("Other", "other-" + UUID.randomUUID() + "@example.com", null)).id());

        createBooking(otherProperty, roomTypeId, checkIn, checkOut, 2, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Room type " + roomTypeId + " does not belong to property " + otherProperty));
        createBooking(Long.MAX_VALUE, roomTypeId, checkIn, checkOut, 2, 1).andExpect(status().isNotFound());
        createBooking(propertyId, Long.MAX_VALUE, checkIn, checkOut, 2, 1).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    private ResultActions createBooking(long property, long roomType, LocalDate in, LocalDate out,
                                        int guests, int rooms) throws Exception {
        String json = """
                {"propertyId": %d, "roomTypeId": %d, "checkIn": "%s", "checkOut": "%s",
                 "guests": %d, "rooms": %d,
                 "guest": {"fullName": "Asha Rao", "email": "asha@example.com"}}
                """.formatted(property, roomType, in, out, guests, rooms);
        return mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private List<Integer> bookedRooms(LocalDate from, LocalDate to) {
        return roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, from, to).stream()
                .map(RoomInventory::getBookedRooms).toList();
    }

    private long onboardProperty(long ownerId) {
        return onboardingService.addProperty(ownerId, new CreatePropertyRequest("Hotel " + UUID.randomUUID(),
                new LocationRequest("1 Main Rd", "Central", "Bengaluru"), 4, Set.of(Amenity.WIFI),
                CancellationPolicyType.FLEXIBLE)).id();
    }

    private long onboardRoomType(long property, int maxGuests, int inventory) {
        return onboardingService.addRoomType(property, new CreateRoomTypeRequest("Room " + UUID.randomUUID(), null,
                maxGuests, inventory, new BigDecimal("2500"))).id();
    }
}
