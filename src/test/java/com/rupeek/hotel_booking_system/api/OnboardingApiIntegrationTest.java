package com.rupeek.hotel_booking_system.api;

import com.jayway.jsonpath.JsonPath;
import com.rupeek.hotel_booking_system.repository.RoomInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hotel.inventory.horizon-days=7")
class OnboardingApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private RoomInventoryRepository roomInventoryRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void onboardsAMultiPropertyOwnerEndToEnd() throws Exception {
        long ownerId = createOwner(uniqueEmail());

        String property = postJson("/api/v1/owners/" + ownerId + "/properties", propertyJson("Sunrise MG Road", 4))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/properties/")))
                .andExpect(jsonPath("$.ownerId").value(ownerId))
                .andExpect(jsonPath("$.amenities[0]").value("WIFI"))
                .andReturn().getResponse().getContentAsString();
        long propertyId = idOf(property);

        postJson("/api/v1/owners/" + ownerId + "/properties/batch", """
                {"properties": [%s, %s]}
                """.formatted(propertyJson("Sunrise Whitefield", 3), propertyJson("Sunrise Airport", 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));

        String roomType = postJson("/api/v1/properties/" + propertyId + "/room-types", roomTypeJson("Deluxe King", 2, 5, "2500"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propertyId))
                .andExpect(jsonPath("$.basePrice").value(2500.00))
                .andReturn().getResponse().getContentAsString();
        long roomTypeId = idOf(roomType);

        mockMvc.perform(get("/api/v1/owners/" + ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyCount").value(3))
                .andExpect(jsonPath("$.properties[*].name").value(hasItem("Sunrise Airport")));

        mockMvc.perform(get("/api/v1/properties/" + propertyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypes.length()").value(1))
                .andExpect(jsonPath("$.roomTypes[0].id").value(roomTypeId))
                .andExpect(jsonPath("$.roomTypes[0].totalInventory").value(5));

        LocalDate today = LocalDate.now();
        assertThat(roomInventoryRepository.findByRoomTypeAndDateRange(roomTypeId, today, today.plusDays(30)))
                .hasSize(7)
                .allSatisfy(night -> assertThat(night.availableRooms()).isEqualTo(5));
    }

    @Test
    void onboardsAStandaloneHotelAsAnOwnerWithOneProperty() throws Exception {
        postJson("/api/v1/properties/standalone", """
                {"owner": %s, "property": %s}
                """.formatted(ownerJson(uniqueEmail()), propertyJson("Lakeview Homestay", 3)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.propertyCount").value(1))
                .andExpect(jsonPath("$.properties[0].name").value("Lakeview Homestay"));
    }

    @Test
    void returnsFieldErrorsForInvalidOwner() throws Exception {
        postJson("/api/v1/owners", """
                {"name": " ", "email": "not-an-email"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")));
    }

    @Test
    void returnsConflictForDuplicateOwnerEmail() throws Exception {
        String email = uniqueEmail();
        createOwner(email);

        postJson("/api/v1/owners", ownerJson(email.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rejectsInvalidStarRatingAndUnknownAmenity() throws Exception {
        long ownerId = createOwner(uniqueEmail());

        postJson("/api/v1/owners/" + ownerId + "/properties", propertyJson("Too Many Stars", 6))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("starRating"));

        postJson("/api/v1/owners/" + ownerId + "/properties", """
                {"name": "X", "location": {"addressLine": "a", "locality": "b", "city": "c"},
                 "starRating": 3, "amenities": ["HELIPAD"], "cancellationPolicy": "FLEXIBLE"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void rejectsInvalidBatchWithoutSavingAnything() throws Exception {
        long ownerId = createOwner(uniqueEmail());

        postJson("/api/v1/owners/" + ownerId + "/properties/batch", """
                {"properties": [%s, %s]}
                """.formatted(propertyJson("Valid", 3), propertyJson("Invalid", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("properties[1].starRating"));
        postJson("/api/v1/owners/" + ownerId + "/properties/batch", """
                {"properties": []}
                """)
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/owners/" + ownerId))
                .andExpect(jsonPath("$.propertyCount").value(0));
    }

    @Test
    void rejectsInvalidRoomTypeCapacityInventoryAndPrice() throws Exception {
        long propertyId = createProperty(createOwner(uniqueEmail()));
        String path = "/api/v1/properties/" + propertyId + "/room-types";

        postJson(path, roomTypeJson("Zero Guests", 0, 5, "2500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("maxGuests"));
        postJson(path, roomTypeJson("Zero Rooms", 2, 0, "2500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("totalInventory"));
        postJson(path, roomTypeJson("Free", 2, 5, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("basePrice"));
        postJson(path, roomTypeJson("Fractional", 2, 5, "10.999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("basePrice"));
    }

    @Test
    void returnsConflictForDuplicateRoomTypeName() throws Exception {
        long propertyId = createProperty(createOwner(uniqueEmail()));
        String path = "/api/v1/properties/" + propertyId + "/room-types";

        postJson(path, roomTypeJson("Deluxe", 2, 5, "2500")).andExpect(status().isCreated());
        postJson(path, roomTypeJson("DELUXE", 3, 2, "3000")).andExpect(status().isConflict());
    }

    @Test
    void returnsNotFoundForUnknownResources() throws Exception {
        mockMvc.perform(get("/api/v1/owners/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Owner account with id 999999 was not found"));
        mockMvc.perform(get("/api/v1/properties/999999")).andExpect(status().isNotFound());
        postJson("/api/v1/owners/999999/properties", propertyJson("Ghost", 3)).andExpect(status().isNotFound());
        postJson("/api/v1/properties/999999/room-types", roomTypeJson("Ghost", 2, 1, "100"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedIdentifiers() throws Exception {
        mockMvc.perform(get("/api/v1/owners/abc")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/owners/0")).andExpect(status().isBadRequest());
    }

    private long createOwner(String email) throws Exception {
        String body = postJson("/api/v1/owners", ownerJson(email))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/owners/")))
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createProperty(long ownerId) throws Exception {
        String body = postJson("/api/v1/owners/" + ownerId + "/properties", propertyJson("Sunrise", 4))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long idOf(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String uniqueEmail() {
        return "owner-" + UUID.randomUUID() + "@example.com";
    }

    private static String ownerJson(String email) {
        return """
                {"name": "Sunrise Hotels", "email": "%s", "phone": "+91 9000000000"}
                """.formatted(email);
    }

    private static String propertyJson(String name, int starRating) {
        return """
                {"name": "%s",
                 "location": {"addressLine": "12 MG Road", "locality": "Indiranagar", "city": "Bengaluru"},
                 "starRating": %d, "amenities": ["SWIMMING_POOL", "WIFI"], "cancellationPolicy": "MODERATE"}
                """.formatted(name, starRating);
    }

    private static String roomTypeJson(String name, int maxGuests, int totalInventory, String basePrice) {
        return """
                {"name": "%s", "description": "Comfortable room", "maxGuests": %d,
                 "totalInventory": %d, "basePrice": %s}
                """.formatted(name, maxGuests, totalInventory, basePrice);
    }
}
