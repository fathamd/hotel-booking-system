package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PropertyAndRoomTypeRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private RoomTypeRepository roomTypeRepository;
    @Autowired
    private OwnerAccountRepository ownerAccountRepository;

    private OwnerAccount chain;
    private Property indiranagar;
    private Property koramangala;
    private Property mumbai;

    @BeforeEach
    void setUp() {
        PersistenceTestData data = new PersistenceTestData(entityManager);
        chain = data.owner("ops@chain.example");
        OwnerAccount standalone = data.owner("hello@inn.example");
        indiranagar = data.property(chain, "Chain Indiranagar", "Bengaluru", "Indiranagar", 3);
        koramangala = data.property(chain, "Chain Koramangala", "Bengaluru", "Koramangala", 5);
        mumbai = data.property(standalone, "Sea Inn", "Mumbai", "Bandra", 4);
        data.roomType(indiranagar, "Suite", "5000");
        data.roomType(indiranagar, "Standard", "2000");
        data.roomType(mumbai, "Standard", "3000");
        data.flushAndClear();
    }

    @Test
    void findsPropertiesByCityIgnoringCaseAndWhitespace() {
        List<Property> result = propertyRepository.findByCity("  bengaluru ");

        assertThat(result).extracting(Property::getName)
                .containsExactly("Chain Koramangala", "Chain Indiranagar");
    }

    @Test
    void findsPropertiesByCityAndLocality() {
        assertThat(propertyRepository.findByCityAndLocality("BENGALURU", "indiranagar"))
                .extracting(Property::getId).containsExactly(indiranagar.getId());
        assertThat(propertyRepository.findByCityAndLocality("Bengaluru", "Bandra")).isEmpty();
    }

    @Test
    void findsPropertiesOwnedByAnAccount() {
        assertThat(propertyRepository.findByOwnerIdOrderByNameAsc(chain.getId()))
                .extracting(Property::getId).containsExactly(indiranagar.getId(), koramangala.getId());
        assertThat(propertyRepository.findByIdAndOwnerId(mumbai.getId(), chain.getId())).isEmpty();
    }

    @Test
    void findsOwnerByEmailIgnoringCase() {
        assertThat(ownerAccountRepository.findByEmailIgnoreCase("OPS@chain.example")).isPresent();
        assertThat(ownerAccountRepository.existsByEmailIgnoreCase("nobody@example.com")).isFalse();
    }

    @Test
    void findsRoomTypesByPropertyOrderedByPrice() {
        assertThat(roomTypeRepository.findByPropertyIdOrderByBasePriceAsc(indiranagar.getId()))
                .extracting(RoomType::getName).containsExactly("Standard", "Suite");
        assertThat(roomTypeRepository.findByPropertyIdOrderByBasePriceAsc(koramangala.getId())).isEmpty();
    }

    @Test
    void roomTypeLookupIsScopedToProperty() {
        RoomType suite = roomTypeRepository.findByPropertyIdOrderByBasePriceAsc(indiranagar.getId()).getLast();

        assertThat(roomTypeRepository.findByIdAndPropertyId(suite.getId(), indiranagar.getId())).isPresent();
        assertThat(roomTypeRepository.findByIdAndPropertyId(suite.getId(), mumbai.getId())).isEmpty();
        assertThat(roomTypeRepository.existsByPropertyIdAndNameIgnoreCase(indiranagar.getId(), "suite")).isTrue();
    }

    @Test
    void batchLoadsRoomTypesWithTheirProperty() {
        List<RoomType> roomTypes = roomTypeRepository.findWithPropertyByPropertyIdIn(
                List.of(indiranagar.getId(), mumbai.getId()));

        assertThat(roomTypes).hasSize(3);
        assertThat(roomTypes).allSatisfy(rt ->
                assertThat(org.hibernate.Hibernate.isInitialized(rt.getProperty())).isTrue());
    }
}
