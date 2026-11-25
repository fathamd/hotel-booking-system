package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the entity mappings produce a valid schema and round-trip correctly through H2.
 */
@DataJpaTest
class DomainMappingTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsAndReloadsFullBookingGraph() {
        OwnerAccount owner = entityManager.persist(DomainFixtures.owner());
        Property property = entityManager.persist(DomainFixtures.property(owner));
        RoomType roomType = entityManager.persist(DomainFixtures.roomType(property));
        entityManager.persist(new RoomInventory(roomType, DomainFixtures.CHECK_IN));
        Booking booking = entityManager.persist(DomainFixtures.pendingBooking(roomType));
        Payment payment = Payment.initiate(booking, PaymentMethod.CARD, "idem-1", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);
        payment.markSucceeded("TXN-100", DomainFixtures.NOW);
        booking.confirm(DomainFixtures.NOW);
        entityManager.persist(payment);
        Refund refund = Refund.initiate(payment, new BigDecimal("1000"), DomainFixtures.NOW);
        entityManager.persist(refund);
        entityManager.flush();
        entityManager.clear();

        Booking reloaded = entityManager.find(Booking.class, booking.getId());
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reloaded.getStayPeriod()).isEqualTo(DomainFixtures.stay());
        assertThat(reloaded.getGuest()).isEqualTo(DomainFixtures.guest());
        assertThat(reloaded.getTotalPrice()).isEqualByComparingTo("7500.00");
        assertThat(reloaded.getProperty().getId()).isEqualTo(property.getId());
        assertThat(reloaded.getRoomType().getId()).isEqualTo(roomType.getId());
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();

        Property reloadedProperty = entityManager.find(Property.class, property.getId());
        assertThat(reloadedProperty.getLocation().city()).isEqualTo("Bengaluru");
        assertThat(reloadedProperty.getAmenities()).containsExactlyInAnyOrder(Amenity.WIFI, Amenity.SWIMMING_POOL);
        assertThat(reloadedProperty.getOwner().getId()).isEqualTo(owner.getId());

        Payment reloadedPayment = entityManager.find(Payment.class, payment.getId());
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloadedPayment.getBooking()).isEqualTo(reloaded);
        assertThat(entityManager.find(Refund.class, refund.getId()).getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void ownerCanManageMultiplePropertiesWithTheSameModel() {
        OwnerAccount chain = entityManager.persist(new OwnerAccount("Chain Co", "ops@chain.example", null));
        OwnerAccount standalone = entityManager.persist(new OwnerAccount("Lake Inn", "hello@lakeinn.example", null));
        entityManager.persist(DomainFixtures.property(chain));
        entityManager.persist(new Property(chain, "Chain Goa", new Location("1 Beach Rd", "Calangute", "Goa"),
                3, Set.of(), CancellationPolicyType.MODERATE));
        entityManager.persist(new Property(standalone, "Lake Inn", new Location("2 Lake Rd", "Old Town", "Udaipur"),
                5, Set.of(Amenity.SPA), CancellationPolicyType.NON_REFUNDABLE));
        entityManager.flush();

        List<Property> chainProperties = entityManager.getEntityManager()
                .createQuery("select p from Property p where p.owner = :owner", Property.class)
                .setParameter("owner", chain)
                .getResultList();
        assertThat(chainProperties).hasSize(2);
    }

    @Test
    void inventoryIsUniquePerRoomTypeAndDate() {
        OwnerAccount owner = entityManager.persist(DomainFixtures.owner());
        Property property = entityManager.persist(DomainFixtures.property(owner));
        RoomType roomType = entityManager.persist(DomainFixtures.roomType(property));
        entityManager.persist(new RoomInventory(roomType, DomainFixtures.CHECK_IN));

        assertThatThrownBy(() -> entityManager.persistAndFlush(new RoomInventory(roomType, DomainFixtures.CHECK_IN)))
                .isInstanceOf(ConstraintViolationException.class);
    }
}
