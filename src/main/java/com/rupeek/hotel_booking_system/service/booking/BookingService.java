package com.rupeek.hotel_booking_system.service.booking;

import com.rupeek.hotel_booking_system.api.dto.booking.BookingPageResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.BookingResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestRequest;
import com.rupeek.hotel_booking_system.config.BookingProperties;
import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.exception.RoomTypeNotInPropertyException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.GuestDetails;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PropertyRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.pricing.PriceCalculator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Creates and reads bookings.
 * <p>
 * <b>Transaction boundary:</b> {@link #createBooking} is a single read-write transaction. Inside it the
 * per-night inventory rows are locked and decremented, and the booking is inserted. Either all of it commits
 * or none of it does, so a booking never exists without its reserved rooms and rooms are never reserved
 * without a booking. All validation that needs no lock (dates, ownership, capacity) runs before the rows are
 * locked, which keeps the locks short.
 * <p>
 * A new booking is {@link BookingStatus#PENDING_PAYMENT}: it holds its rooms until {@code holdExpiresAt}
 * and only becomes {@link BookingStatus#CONFIRMED} after a successful payment, which is outside this service.
 */
@Service
public class BookingService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Sort PROPERTY_BOOKINGS_ORDER = Sort.by("stayPeriod.checkIn", "id");

    private final BookingRepository bookingRepository;
    private final PropertyRepository propertyRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final InventoryReservationService inventoryReservationService;
    private final PriceCalculator priceCalculator;
    private final Duration holdDuration;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository,
                          PropertyRepository propertyRepository,
                          RoomTypeRepository roomTypeRepository,
                          InventoryReservationService inventoryReservationService,
                          PriceCalculator priceCalculator,
                          BookingProperties bookingProperties,
                          Clock clock) {
        this.bookingRepository = bookingRepository;
        this.propertyRepository = propertyRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.priceCalculator = priceCalculator;
        this.holdDuration = bookingProperties.holdDuration();
        this.clock = clock;
    }

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        int guests = DomainPreconditions.requireNonNull(request.guests(), "guests");
        int rooms = DomainPreconditions.requireNonNull(request.rooms(), "rooms");
        GuestDetails guest = toGuestDetails(request.guest());
        LocalDateTime now = LocalDateTime.now(clock);
        StayPeriod stay = new StayPeriod(request.checkIn(), request.checkOut());

        RoomType roomType = findRoomTypeOfProperty(request.propertyId(), request.roomTypeId());
        stay.requireBookableFrom(roomType.getProperty().today(clock));
        roomType.requireCanAccommodate(guests, rooms);

        inventoryReservationService.reserve(roomType, stay, rooms);
        BigDecimal totalPrice = priceCalculator.totalPrice(roomType, stay, rooms);
        Booking booking = Booking.createPending(roomType, guest, stay, guests, rooms, totalPrice,
                now.plus(holdDuration));
        return BookingResponseMapper.toResponse(bookingRepository.save(booking));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        return bookingRepository.findDetailedById(bookingId)
                .map(BookingResponseMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }

    /**
     * Bookings of a property ordered by check-in date, optionally only those in {@code status}.
     */
    @Transactional(readOnly = true)
    public BookingPageResponse getPropertyBookings(Long propertyId, BookingStatus status, int page, int size) {
        DomainPreconditions.requireInRange(page, 0, Integer.MAX_VALUE, "page");
        DomainPreconditions.requireInRange(size, 1, MAX_PAGE_SIZE, "size");
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property", propertyId);
        }
        return BookingResponseMapper.toPage(bookingRepository.findByProperty(propertyId, status,
                PageRequest.of(page, size, PROPERTY_BOOKINGS_ORDER)));
    }

    private RoomType findRoomTypeOfProperty(Long propertyId, Long roomTypeId) {
        if (!propertyRepository.existsById(propertyId)) {
            throw new ResourceNotFoundException("Property", propertyId);
        }
        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type", roomTypeId));
        if (!roomType.getProperty().getId().equals(propertyId)) {
            throw new RoomTypeNotInPropertyException(roomTypeId, propertyId);
        }
        return roomType;
    }

    private static GuestDetails toGuestDetails(GuestRequest guest) {
        DomainPreconditions.requireNonNull(guest, "guest");
        return new GuestDetails(guest.fullName(), guest.email(), guest.phone());
    }
}
