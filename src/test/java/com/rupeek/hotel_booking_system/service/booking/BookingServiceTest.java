package com.rupeek.hotel_booking_system.service.booking;

import com.rupeek.hotel_booking_system.api.dto.booking.BookingPageResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.BookingResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestRequest;
import com.rupeek.hotel_booking_system.config.BookingProperties;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.GuestCapacityExceededException;
import com.rupeek.hotel_booking_system.domain.exception.InsufficientInventoryException;
import com.rupeek.hotel_booking_system.domain.exception.RoomTypeNotInPropertyException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PropertyRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.pricing.BaseRatePriceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_IN;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.CHECK_OUT;
import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final long PROPERTY_ID = 10L;
    private static final long ROOM_TYPE_ID = 20L;
    private static final long BOOKING_ID = 30L;
    private static final Duration HOLD = Duration.ofMinutes(15);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PropertyRepository propertyRepository;
    @Mock
    private RoomTypeRepository roomTypeRepository;
    @Mock
    private InventoryReservationService inventoryReservationService;

    private BookingService bookingService;
    private Property property;
    private RoomType roomType;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(bookingRepository, propertyRepository, roomTypeRepository,
                inventoryReservationService, new BaseRatePriceCalculator(), new BookingProperties(HOLD),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        property = DomainFixtures.property(DomainFixtures.owner());
        ReflectionTestUtils.setField(property, "id", PROPERTY_ID);
        roomType = DomainFixtures.roomType(property);
        ReflectionTestUtils.setField(roomType, "id", ROOM_TYPE_ID);
    }

    @Nested
    class CreateBooking {

        @BeforeEach
        void knownRoomType() {
            lenient().when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);
            lenient().when(roomTypeRepository.findById(ROOM_TYPE_ID)).thenReturn(Optional.of(roomType));
            lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
                Booking booking = invocation.getArgument(0);
                ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
                return booking;
            });
        }

        @Test
        void createsPendingBookingAfterReservingInventory() {
            BookingResponse response = bookingService.createBooking(request(2, 1));

            assertThat(response.id()).isEqualTo(BOOKING_ID);
            assertThat(response.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            assertThat(response.propertyId()).isEqualTo(PROPERTY_ID);
            assertThat(response.roomTypeId()).isEqualTo(ROOM_TYPE_ID);
            assertThat(response.nights()).isEqualTo(3);
            assertThat(response.totalPrice()).isEqualByComparingTo("7500.00");
            assertThat(response.holdExpiresAt()).isEqualTo(NOW.plus(HOLD));
            assertThat(response.confirmedAt()).isNull();
            assertThat(response.guest().email()).isEqualTo("asha@example.com");

            InOrder order = inOrder(inventoryReservationService, bookingRepository);
            order.verify(inventoryReservationService).reserve(roomType, new StayPeriod(CHECK_IN, CHECK_OUT), 1);
            order.verify(bookingRepository).save(any(Booking.class));
        }

        @Test
        void bookingIsNeverConfirmedOnCreation() {
            bookingService.createBooking(request(2, 1));

            ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            assertThat(saved.getValue().holdsInventory()).isTrue();
            assertThat(saved.getValue().getConfirmedAt()).isNull();
        }

        @Test
        void reservesAndPricesMultipleRoomsInOneBooking() {
            BookingResponse response = bookingService.createBooking(request(4, 3));

            assertThat(response.rooms()).isEqualTo(3);
            assertThat(response.guests()).isEqualTo(4);
            assertThat(response.totalPrice()).isEqualByComparingTo("22500.00");
            verify(inventoryReservationService).reserve(roomType, new StayPeriod(CHECK_IN, CHECK_OUT), 3);
        }

        @Test
        void rejectsInvalidDatesBeforeTouchingInventory() {
            assertInvalid(withDates(CHECK_IN, CHECK_IN), DomainValidationException.class, "checkOut must be after checkIn");
            assertInvalid(withDates(CHECK_OUT, CHECK_IN), DomainValidationException.class, "checkOut must be after checkIn");
            assertInvalid(withDates(NOW.toLocalDate().minusDays(1), CHECK_IN), DomainValidationException.class, "past");
            assertInvalid(withDates(CHECK_IN, CHECK_IN.plusDays(StayPeriod.MAX_BOOKABLE_NIGHTS + 1)),
                    DomainValidationException.class, "cannot exceed");
            assertInvalid(withDates(null, CHECK_OUT), DomainValidationException.class, "checkIn");

            verifyNoInteractions(inventoryReservationService, bookingRepository);
        }

        @Test
        void acceptsCheckInTodayAndTheLongestAllowedStay() {
            LocalDate today = NOW.toLocalDate();

            bookingService.createBooking(withDates(today, today.plusDays(StayPeriod.MAX_BOOKABLE_NIGHTS)));

            verify(inventoryReservationService).reserve(roomType,
                    new StayPeriod(today, today.plusDays(StayPeriod.MAX_BOOKABLE_NIGHTS)), 1);
        }

        @Test
        void rejectsGuestCountsTheRoomsCannotHost() {
            assertInvalid(request(3, 1), GuestCapacityExceededException.class, "cannot be accommodated");
            assertInvalid(request(1, 2), GuestCapacityExceededException.class, "at least one guest per room");
            assertInvalid(request(6, 6), GuestCapacityExceededException.class, "5 room(s) in total");
            assertInvalid(request(0, 1), GuestCapacityExceededException.class, "0 guest(s)");

            verifyNoInteractions(inventoryReservationService, bookingRepository);
        }

        @Test
        void rejectsUnknownPropertyOrRoomType() {
            when(propertyRepository.existsById(99L)).thenReturn(false);
            assertThatThrownBy(() -> bookingService.createBooking(request(99L, ROOM_TYPE_ID, 2, 1)))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Property with id 99 was not found");

            when(roomTypeRepository.findById(98L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bookingService.createBooking(request(PROPERTY_ID, 98L, 2, 1)))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Room type with id 98 was not found");

            verifyNoInteractions(inventoryReservationService, bookingRepository);
        }

        @Test
        void rejectsRoomTypeOfAnotherProperty() {
            when(propertyRepository.existsById(11L)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.createBooking(request(11L, ROOM_TYPE_ID, 2, 1)))
                    .isInstanceOf(RoomTypeNotInPropertyException.class)
                    .hasMessage("Room type 20 does not belong to property 11");
            verifyNoInteractions(inventoryReservationService, bookingRepository);
        }

        @Test
        void doesNotCreateBookingWhenInventoryIsInsufficient() {
            doThrow(new InsufficientInventoryException(ROOM_TYPE_ID, CHECK_IN, 2, 1))
                    .when(inventoryReservationService).reserve(eq(roomType), any(StayPeriod.class), anyInt());

            assertThatThrownBy(() -> bookingService.createBooking(request(2, 2)))
                    .isInstanceOf(InsufficientInventoryException.class)
                    .hasMessageContaining("1 room(s) available on " + CHECK_IN);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        void requiresGuestDetails() {
            CreateBookingRequest noGuest = new CreateBookingRequest(PROPERTY_ID, ROOM_TYPE_ID, CHECK_IN, CHECK_OUT,
                    2, 1, null);
            assertInvalid(noGuest, DomainValidationException.class, "guest");

            CreateBookingRequest badEmail = new CreateBookingRequest(PROPERTY_ID, ROOM_TYPE_ID, CHECK_IN, CHECK_OUT,
                    2, 1, new GuestRequest("Asha", "not-an-email", null));
            assertInvalid(badEmail, DomainValidationException.class, "email");
        }
    }

    @Nested
    class ReadBookings {

        @Test
        void returnsBookingDetails() {
            Booking booking = DomainFixtures.pendingBooking(roomType);
            ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
            when(bookingRepository.findDetailedById(BOOKING_ID)).thenReturn(Optional.of(booking));

            BookingResponse response = bookingService.getBooking(BOOKING_ID);

            assertThat(response.id()).isEqualTo(BOOKING_ID);
            assertThat(response.propertyName()).isEqualTo(property.getName());
            assertThat(response.roomTypeName()).isEqualTo(roomType.getName());
            assertThat(response.checkIn()).isEqualTo(CHECK_IN);
        }

        @Test
        void unknownBookingIsNotFound() {
            when(bookingRepository.findDetailedById(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.getBooking(BOOKING_ID))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Booking with id 30 was not found");
        }

        @Test
        void returnsPropertyBookingsPage() {
            Booking booking = DomainFixtures.pendingBooking(roomType);
            when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(true);
            when(bookingRepository.findByProperty(eq(PROPERTY_ID), eq(BookingStatus.PENDING_PAYMENT), any(Pageable.class)))
                    .thenAnswer(invocation -> new PageImpl<>(List.of(booking), invocation.getArgument(2), 21));

            BookingPageResponse page = bookingService.getPropertyBookings(PROPERTY_ID, BookingStatus.PENDING_PAYMENT, 1, 20);

            assertThat(page.content()).hasSize(1);
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalElements()).isEqualTo(21);
            assertThat(page.totalPages()).isEqualTo(2);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(bookingRepository).findByProperty(eq(PROPERTY_ID), eq(BookingStatus.PENDING_PAYMENT), pageable.capture());
            assertThat(pageable.getValue().getSort().getOrderFor("stayPeriod.checkIn")).isNotNull();
            assertThat(pageable.getValue()).extracting(Pageable::getPageNumber, Pageable::getPageSize).containsExactly(1, 20);
        }

        @Test
        void propertyBookingsRequireExistingPropertyAndValidPage() {
            when(propertyRepository.existsById(PROPERTY_ID)).thenReturn(false);

            assertThatThrownBy(() -> bookingService.getPropertyBookings(PROPERTY_ID, null, 0, 20))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> bookingService.getPropertyBookings(PROPERTY_ID, null, -1, 20))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> bookingService.getPropertyBookings(PROPERTY_ID, null, 0, BookingService.MAX_PAGE_SIZE + 1))
                    .isInstanceOf(DomainValidationException.class);
            verify(bookingRepository, never()).findByProperty(any(), any(), any(PageRequest.class));
        }
    }

    private void assertInvalid(CreateBookingRequest request, Class<? extends Exception> type, String message) {
        assertThatThrownBy(() -> bookingService.createBooking(request)).isInstanceOf(type).hasMessageContaining(message);
    }

    private static CreateBookingRequest request(int guests, int rooms) {
        return request(PROPERTY_ID, ROOM_TYPE_ID, guests, rooms);
    }

    private static CreateBookingRequest request(long propertyId, long roomTypeId, int guests, int rooms) {
        return new CreateBookingRequest(propertyId, roomTypeId, CHECK_IN, CHECK_OUT, guests, rooms, guest());
    }

    private static CreateBookingRequest withDates(LocalDate checkIn, LocalDate checkOut) {
        return new CreateBookingRequest(PROPERTY_ID, ROOM_TYPE_ID, checkIn, checkOut, 2, 1, guest());
    }

    private static GuestRequest guest() {
        return new GuestRequest("Asha Rao", "asha@example.com", null);
    }
}
