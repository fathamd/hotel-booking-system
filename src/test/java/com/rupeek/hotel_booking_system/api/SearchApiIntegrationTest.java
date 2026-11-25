package com.rupeek.hotel_booking_system.api;

import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "hotel.inventory.horizon-days=30",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class SearchApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PropertyOnboardingService onboardingService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private String city;
    private final LocalDate checkIn = LocalDate.now().plusDays(2);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        city = "City-" + UUID.randomUUID();
    }

    @Test
    void searchesAvailablePropertiesWithAllFilters() throws Exception {
        long ownerId = onboardOwner();
        long resort = onboardProperty(ownerId, "Resort", 5, Set.of(Amenity.WIFI, Amenity.SWIMMING_POOL));
        onboardRoomType(resort, "Deluxe", 2, "4000");
        onboardRoomType(resort, "Family Suite", 4, "7000");
        onboardRoomType(onboardProperty(ownerId, "Hostel", 2, Set.of(Amenity.WIFI)), "Dorm", 2, "800");

        mockMvc.perform(search("3").param("minStarRating", "4")
                        .param("amenities", "WIFI,SWIMMING_POOL").param("minPrice", "1000").param("maxPrice", "8000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights").value(2))
                .andExpect(jsonPath("$.resultCount").value(1))
                .andExpect(jsonPath("$.results[0].name").value("Resort"))
                .andExpect(jsonPath("$.results[0].availableRoomTypes.length()").value(1))
                .andExpect(jsonPath("$.results[0].availableRoomTypes[0].name").value("Family Suite"))
                .andExpect(jsonPath("$.results[0].availableRoomTypes[0].availableRooms").value(5))
                .andExpect(jsonPath("$.results[0].availableRoomTypes[0].totalPrice").value(14000.00))
                .andExpect(jsonPath("$.results[0].amenities").value(hasItem("SWIMMING_POOL")));
    }

    @Test
    void runsAConstantNumberOfQueriesRegardlessOfResultSize() throws Exception {
        long ownerId = onboardOwner();
        for (int i = 0; i < 6; i++) {
            long propertyId = onboardProperty(ownerId, "Hotel " + i, 4, Set.of(Amenity.WIFI, Amenity.GYM));
            onboardRoomType(propertyId, "Standard", 2, "2000");
            onboardRoomType(propertyId, "Deluxe", 2, "3000");
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(search().param("amenities", "WIFI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(6));

        // room types with fetched properties + aggregated availability + one batched amenity load
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void rejectsCheckOutNotAfterCheckIn() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search").param("city", city)
                        .param("checkIn", checkIn.toString()).param("checkOut", checkIn.toString()).param("guests", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("checkOut must be after checkIn"));
    }

    @Test
    void rejectsMissingOrInvalidParameters() throws Exception {
        mockMvc.perform(get("/api/v1/properties/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("city")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("checkIn")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("checkOut")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("guests")));

        mockMvc.perform(get("/api/v1/properties/search").param("city", city).param("guests", "2")
                        .param("checkIn", "2026-13-01").param("checkOut", checkIn.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("checkIn"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));

        mockMvc.perform(search("0")).andExpect(status().isBadRequest());
        mockMvc.perform(search().param("amenities", "HELIPAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amenities"));
        mockMvc.perform(search().param("minStarRating", "6")).andExpect(status().isBadRequest());
        mockMvc.perform(search().param("minPrice", "5000").param("maxPrice", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("minPrice cannot be greater than maxPrice"));
    }

    private MockHttpServletRequestBuilder search() {
        return search("2");
    }

    private MockHttpServletRequestBuilder search(String guests) {
        return get("/api/v1/properties/search")
                .param("city", city)
                .param("checkIn", checkIn.toString())
                .param("checkOut", checkIn.plusDays(2).toString())
                .param("guests", guests);
    }

    private long onboardOwner() {
        return onboardingService.createOwner(
                new CreateOwnerRequest("Owner", "owner-" + UUID.randomUUID() + "@example.com", null)).id();
    }

    private long onboardProperty(long ownerId, String name, int stars, Set<Amenity> amenities) {
        return onboardingService.addProperty(ownerId, new CreatePropertyRequest(name,
                new LocationRequest("1 Main Rd", "Central", city), stars, amenities, CancellationPolicyType.FLEXIBLE)).id();
    }

    private void onboardRoomType(long propertyId, String name, int maxGuests, String price) {
        onboardingService.addRoomType(propertyId, new CreateRoomTypeRequest(name, null, maxGuests, 5, new BigDecimal(price)));
    }
}
