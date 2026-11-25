package com.rupeek.hotel_booking_system.service.onboarding;

import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertiesRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateStandalonePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertyDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertySummaryResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.RoomTypeResponse;
import com.rupeek.hotel_booking_system.config.PropertyDefaults;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.model.BaseEntity;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.OwnerAccountRepository;
import com.rupeek.hotel_booking_system.repository.PropertyRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.exception.DuplicateResourceException;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyOnboardingServiceTest {

    private static final long OWNER_ID = 1L;
    private static final long PROPERTY_ID = 10L;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private OwnerAccountRepository ownerAccountRepository;
    @Mock
    private PropertyRepository propertyRepository;
    @Mock
    private RoomTypeRepository roomTypeRepository;
    @Mock
    private RoomInventoryProvisioner roomInventoryProvisioner;

    private PropertyOnboardingService service;
    private final AtomicLong idSequence = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new PropertyOnboardingService(ownerAccountRepository, propertyRepository,
                roomTypeRepository, roomInventoryProvisioner, new PropertyDefaults(DEFAULT_ZONE));
    }

    @Nested
    class CreateOwner {

        @Test
        void createsOwnerWithNormalisedEmail() {
            when(ownerAccountRepository.save(any(OwnerAccount.class))).thenAnswer(inv -> withId(inv.getArgument(0), OWNER_ID));

            OwnerResponse response = service.createOwner(
                    new CreateOwnerRequest("  Sunrise Hotels ", " Owner@Sunrise.Example ", "+91 9000000000"));

            assertThat(response.id()).isEqualTo(OWNER_ID);
            assertThat(response.name()).isEqualTo("Sunrise Hotels");
            assertThat(response.email()).isEqualTo("owner@sunrise.example");
            verify(ownerAccountRepository).existsByEmailIgnoreCase("owner@sunrise.example");
        }

        @Test
        void rejectsDuplicateEmail() {
            when(ownerAccountRepository.existsByEmailIgnoreCase("owner@sunrise.example")).thenReturn(true);

            assertThatThrownBy(() -> service.createOwner(ownerRequest("owner@sunrise.example")))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("owner@sunrise.example");
            verify(ownerAccountRepository, never()).save(any());
        }

        @Test
        void rejectsMissingMandatoryFieldsBeforeTouchingTheRepository() {
            assertThatThrownBy(() -> service.createOwner(new CreateOwnerRequest(" ", "owner@sunrise.example", null)))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> service.createOwner(new CreateOwnerRequest("Owner", null, null)))
                    .isInstanceOf(DomainValidationException.class);
            verifyNoInteractions(ownerAccountRepository);
        }
    }

    @Nested
    class AddProperty {

        @Test
        void addsPropertyToExistingOwner() {
            OwnerAccount owner = existingOwner();
            when(propertyRepository.save(any(Property.class))).thenAnswer(inv -> withId(inv.getArgument(0), PROPERTY_ID));

            PropertySummaryResponse response = service.addProperty(OWNER_ID, propertyRequest("Sunrise MG Road", 4));

            assertThat(response.id()).isEqualTo(PROPERTY_ID);
            assertThat(response.ownerId()).isEqualTo(owner.getId());
            assertThat(response.starRating()).isEqualTo(4);
            assertThat(response.location().city()).isEqualTo("Bengaluru");
            assertThat(response.amenities()).containsExactly(Amenity.WIFI, Amenity.SWIMMING_POOL);
            assertThat(response.cancellationPolicy()).isEqualTo(CancellationPolicyType.MODERATE);
        }

        @Test
        void rejectsUnknownOwner() {
            when(ownerAccountRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addProperty(OWNER_ID, propertyRequest("Sunrise", 4)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Owner account with id 1");
            verify(propertyRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 6, -1})
        void rejectsStarRatingOutsideOneToFive(int starRating) {
            existingOwner();

            assertThatThrownBy(() -> service.addProperty(OWNER_ID, propertyRequest("Sunrise", starRating)))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("starRating");
            verify(propertyRepository, never()).save(any());
        }

        @Test
        void rejectsMissingMandatoryFields() {
            existingOwner();
            LocationRequest location = new LocationRequest("12 MG Road", "Indiranagar", "Bengaluru");

            assertThatThrownBy(() -> service.addProperty(OWNER_ID,
                    new CreatePropertyRequest("Sunrise", location, null, Set.of(), CancellationPolicyType.FLEXIBLE)))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> service.addProperty(OWNER_ID,
                    new CreatePropertyRequest("Sunrise", null, 3, Set.of(), CancellationPolicyType.FLEXIBLE)))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> service.addProperty(OWNER_ID,
                    new CreatePropertyRequest("Sunrise", location, 3, Set.of(), null)))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> service.addProperty(OWNER_ID,
                    new CreatePropertyRequest("Sunrise", new LocationRequest("12 MG Road", " ", "Bengaluru"),
                            3, Set.of(), CancellationPolicyType.FLEXIBLE)))
                    .isInstanceOf(DomainValidationException.class);
            verify(propertyRepository, never()).save(any());
        }

        @Test
        void acceptsPropertyWithoutAmenities() {
            existingOwner();
            when(propertyRepository.save(any(Property.class))).thenAnswer(inv -> withId(inv.getArgument(0), PROPERTY_ID));

            PropertySummaryResponse response = service.addProperty(OWNER_ID, new CreatePropertyRequest("Budget Inn",
                    new LocationRequest("1 Main St", "Koramangala", "Bengaluru"), 2, null, CancellationPolicyType.FLEXIBLE));

            assertThat(response.amenities()).isEmpty();
        }

        @Test
        void appliesTheConfiguredDefaultTimeZoneWhenNoneIsGiven() {
            existingOwner();
            when(propertyRepository.save(any(Property.class))).thenAnswer(inv -> withId(inv.getArgument(0), PROPERTY_ID));

            PropertySummaryResponse response = service.addProperty(OWNER_ID, propertyRequest("Budget Inn", 3));

            assertThat(response.timeZone()).isEqualTo(DEFAULT_ZONE.getId());
        }

        @Test
        void usesTheRequestedTimeZone() {
            existingOwner();
            when(propertyRepository.save(any(Property.class))).thenAnswer(inv -> withId(inv.getArgument(0), PROPERTY_ID));

            PropertySummaryResponse response = service.addProperty(OWNER_ID, new CreatePropertyRequest("Goa Inn",
                    new LocationRequest("1 Beach Rd", "Calangute", "Goa"), 3, Set.of(),
                    CancellationPolicyType.FLEXIBLE, "Europe/London"));

            assertThat(response.timeZone()).isEqualTo("Europe/London");
        }

        @Test
        void rejectsAnUnknownTimeZone() {
            existingOwner();

            assertThatThrownBy(() -> service.addProperty(OWNER_ID, new CreatePropertyRequest("Goa Inn",
                    new LocationRequest("1 Beach Rd", "Calangute", "Goa"), 3, Set.of(),
                    CancellationPolicyType.FLEXIBLE, "Mars/Olympus")))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("timeZone");
            verify(propertyRepository, never()).save(any());
        }
    }

    @Nested
    class AddProperties {

        @Test
        void addsAllPropertiesUnderTheSameOwner() {
            OwnerAccount owner = existingOwner();
            when(propertyRepository.saveAll(anyList())).thenAnswer(inv -> {
                List<Property> saved = new ArrayList<>();
                for (Property property : inv.<List<Property>>getArgument(0)) {
                    saved.add(withId(property, idSequence.incrementAndGet()));
                }
                return saved;
            });

            List<PropertySummaryResponse> responses = service.addProperties(OWNER_ID, new CreatePropertiesRequest(List.of(
                    propertyRequest("Sunrise MG Road", 4), propertyRequest("Sunrise Whitefield", 3))));

            assertThat(responses).extracting(PropertySummaryResponse::name)
                    .containsExactly("Sunrise MG Road", "Sunrise Whitefield");
            assertThat(responses).extracting(PropertySummaryResponse::ownerId).containsOnly(owner.getId());
            assertThat(responses).extracting(PropertySummaryResponse::id).doesNotContainNull().doesNotHaveDuplicates();
        }

        @Test
        void savesNothingWhenAnyPropertyInTheBatchIsInvalid() {
            existingOwner();

            assertThatThrownBy(() -> service.addProperties(OWNER_ID, new CreatePropertiesRequest(List.of(
                    propertyRequest("Valid", 4), propertyRequest("Invalid", 9)))))
                    .isInstanceOf(DomainValidationException.class);
            verify(propertyRepository, never()).saveAll(anyList());
        }

        @Test
        void rejectsUnknownOwner() {
            when(ownerAccountRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addProperties(OWNER_ID,
                    new CreatePropertiesRequest(List.of(propertyRequest("Sunrise", 4)))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class StandaloneProperty {

        @Test
        void storesAStandaloneHotelAsAnOwnerWithOneProperty() {
            when(ownerAccountRepository.save(any(OwnerAccount.class))).thenAnswer(inv -> withId(inv.getArgument(0), OWNER_ID));
            when(propertyRepository.save(any(Property.class))).thenAnswer(inv -> withId(inv.getArgument(0), PROPERTY_ID));

            OwnerDetailsResponse response = service.onboardStandaloneProperty(new CreateStandalonePropertyRequest(
                    ownerRequest("hello@lakeview.example"), propertyRequest("Lakeview Homestay", 3)));

            ArgumentCaptor<Property> property = ArgumentCaptor.forClass(Property.class);
            verify(propertyRepository).save(property.capture());
            assertThat(property.getValue().getOwner().getEmail()).isEqualTo("hello@lakeview.example");
            assertThat(response.id()).isEqualTo(OWNER_ID);
            assertThat(response.propertyCount()).isEqualTo(1);
            assertThat(response.properties()).singleElement()
                    .satisfies(summary -> assertThat(summary.ownerId()).isEqualTo(OWNER_ID));
        }

        @Test
        void doesNotCreatePropertyWhenOwnerEmailIsTaken() {
            when(ownerAccountRepository.existsByEmailIgnoreCase("hello@lakeview.example")).thenReturn(true);

            assertThatThrownBy(() -> service.onboardStandaloneProperty(new CreateStandalonePropertyRequest(
                    ownerRequest("hello@lakeview.example"), propertyRequest("Lakeview Homestay", 3))))
                    .isInstanceOf(DuplicateResourceException.class);
            verifyNoInteractions(propertyRepository);
        }
    }

    @Nested
    class AddRoomType {

        @Test
        void addsRoomTypeAndProvisionsItsInventory() {
            existingProperty();
            when(roomTypeRepository.save(any(RoomType.class))).thenAnswer(inv -> withId(inv.getArgument(0), 50L));

            RoomTypeResponse response = service.addRoomType(PROPERTY_ID,
                    roomTypeRequest(" Deluxe King ", 2, 5, new BigDecimal("2500")));

            assertThat(response.id()).isEqualTo(50L);
            assertThat(response.propertyId()).isEqualTo(PROPERTY_ID);
            assertThat(response.name()).isEqualTo("Deluxe King");
            assertThat(response.maxGuests()).isEqualTo(2);
            assertThat(response.totalInventory()).isEqualTo(5);
            assertThat(response.basePrice()).isEqualByComparingTo("2500.00").hasScaleOf(2);
            ArgumentCaptor<RoomType> provisioned = ArgumentCaptor.forClass(RoomType.class);
            verify(roomInventoryProvisioner).provision(provisioned.capture());
            assertThat(provisioned.getValue().getId()).isEqualTo(50L);
        }

        @Test
        void rejectsDuplicateRoomTypeNameWithinProperty() {
            existingProperty();
            when(roomTypeRepository.existsByPropertyIdAndNameIgnoreCase(PROPERTY_ID, "deluxe king")).thenReturn(true);

            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID,
                    roomTypeRequest(" deluxe king ", 2, 5, new BigDecimal("2500"))))
                    .isInstanceOf(DuplicateResourceException.class);
            verify(roomTypeRepository, never()).save(any());
            verifyNoInteractions(roomInventoryProvisioner);
        }

        @Test
        void rejectsUnknownProperty() {
            when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID,
                    roomTypeRequest("Deluxe", 2, 5, new BigDecimal("2500"))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Property with id 10");
            verifyNoInteractions(roomTypeRepository, roomInventoryProvisioner);
        }

        @ParameterizedTest(name = "maxGuests={0}, totalInventory={1}")
        @CsvSource({"0, 5", "11, 5", "-1, 5", "2, 0", "2, -3", "2, 1001"})
        void rejectsInvalidCapacityOrInventory(int maxGuests, int totalInventory) {
            existingProperty();

            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID,
                    roomTypeRequest("Deluxe", maxGuests, totalInventory, new BigDecimal("2500"))))
                    .isInstanceOf(DomainValidationException.class);
            verifyNoInteractions(roomTypeRepository, roomInventoryProvisioner);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "0.00", "-100"})
        void rejectsNonPositiveOrMissingPrice(BigDecimal basePrice) {
            existingProperty();

            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID, roomTypeRequest("Deluxe", 2, 5, basePrice)))
                    .isInstanceOf(DomainValidationException.class);
            verifyNoInteractions(roomTypeRepository, roomInventoryProvisioner);
        }

        @Test
        void rejectsMissingCapacityValues() {
            existingProperty();

            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID,
                    new CreateRoomTypeRequest("Deluxe", null, null, 5, new BigDecimal("2500"))))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> service.addRoomType(PROPERTY_ID,
                    new CreateRoomTypeRequest("Deluxe", null, 2, null, new BigDecimal("2500"))))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Retrieval {

        @Test
        void returnsOwnerWithAllProperties() {
            OwnerAccount owner = existingOwner();
            Property first = withId(new Property(owner, "Sunrise A", DomainFixtures.property(owner).getLocation(), 4,
                    Set.of(), CancellationPolicyType.FLEXIBLE), 11L);
            Property second = withId(new Property(owner, "Sunrise B", DomainFixtures.property(owner).getLocation(), 5,
                    Set.of(Amenity.SPA), CancellationPolicyType.MODERATE), 12L);
            when(propertyRepository.findByOwnerIdOrderByNameAsc(OWNER_ID)).thenReturn(List.of(first, second));

            OwnerDetailsResponse response = service.getOwner(OWNER_ID);

            assertThat(response.email()).isEqualTo(owner.getEmail());
            assertThat(response.propertyCount()).isEqualTo(2);
            assertThat(response.properties()).extracting(PropertySummaryResponse::id).containsExactly(11L, 12L);
        }

        @Test
        void returnsOwnerWithoutPropertiesAsEmptyPortfolio() {
            existingOwner();
            when(propertyRepository.findByOwnerIdOrderByNameAsc(OWNER_ID)).thenReturn(List.of());

            assertThat(service.getOwner(OWNER_ID).properties()).isEmpty();
        }

        @Test
        void returnsPropertyWithRoomTypes() {
            Property property = existingProperty();
            RoomType deluxe = withId(DomainFixtures.roomType(property), 50L);
            when(roomTypeRepository.findByPropertyIdOrderByBasePriceAsc(PROPERTY_ID)).thenReturn(List.of(deluxe));

            PropertyDetailsResponse response = service.getProperty(PROPERTY_ID);

            assertThat(response.id()).isEqualTo(PROPERTY_ID);
            assertThat(response.ownerId()).isEqualTo(OWNER_ID);
            assertThat(response.roomTypes()).singleElement().satisfies(roomType -> {
                assertThat(roomType.id()).isEqualTo(50L);
                assertThat(roomType.name()).isEqualTo("Deluxe King");
            });
        }

        @Test
        void rejectsUnknownOwnerOrProperty() {
            when(ownerAccountRepository.findById(OWNER_ID)).thenReturn(Optional.empty());
            when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getOwner(OWNER_ID)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.getProperty(PROPERTY_ID)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private OwnerAccount existingOwner() {
        OwnerAccount owner = withId(DomainFixtures.owner(), OWNER_ID);
        when(ownerAccountRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        return owner;
    }

    private Property existingProperty() {
        Property property = withId(DomainFixtures.property(withId(DomainFixtures.owner(), OWNER_ID)), PROPERTY_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        return property;
    }

    private static <T extends BaseEntity> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static CreateOwnerRequest ownerRequest(String email) {
        return new CreateOwnerRequest("Sunrise Hotels", email, null);
    }

    private static CreatePropertyRequest propertyRequest(String name, int starRating) {
        return new CreatePropertyRequest(name, new LocationRequest("12 MG Road", "Indiranagar", "Bengaluru"),
                starRating, Set.of(Amenity.SWIMMING_POOL, Amenity.WIFI), CancellationPolicyType.MODERATE);
    }

    private static CreateRoomTypeRequest roomTypeRequest(String name, int maxGuests, int inventory, BigDecimal price) {
        return new CreateRoomTypeRequest(name, "King bed", maxGuests, inventory, price);
    }
}
