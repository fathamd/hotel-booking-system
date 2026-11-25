package com.rupeek.hotel_booking_system.service.booking;

import com.rupeek.hotel_booking_system.api.dto.booking.BookingPageResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.BookingResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.GuestResponse;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.GuestDetails;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import org.springframework.data.domain.Page;

/**
 * Builds booking DTOs. Must run inside a transaction or on bookings whose property and room type are loaded.
 */
final class BookingResponseMapper {

    private BookingResponseMapper() {
    }

    static BookingResponse toResponse(Booking booking) {
        StayPeriod stay = booking.getStayPeriod();
        GuestDetails guest = booking.getGuest();
        return new BookingResponse(booking.getId(), booking.getStatus(),
                booking.getProperty().getId(), booking.getProperty().getName(),
                booking.getRoomType().getId(), booking.getRoomType().getName(),
                stay.checkIn(), stay.checkOut(), stay.numberOfNights(),
                booking.getGuestCount(), booking.getRoomCount(), booking.getTotalPrice(),
                new GuestResponse(guest.fullName(), guest.email(), guest.phone()),
                booking.getHoldExpiresAt(), booking.getConfirmedAt(), booking.getCancelledAt(),
                booking.getCreatedAt());
    }

    static BookingPageResponse toPage(Page<Booking> page) {
        return new BookingPageResponse(page.map(BookingResponseMapper::toResponse).getContent(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
