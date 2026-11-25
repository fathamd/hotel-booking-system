package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.booking.BookingPageResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.BookingResponse;
import com.rupeek.hotel_booking_system.api.dto.booking.CreateBookingRequest;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.service.booking.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class BookingController {

    static final String BASE_PATH = "/api/v1/bookings";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping(BASE_PATH)
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        BookingResponse booking = bookingService.createBooking(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + booking.id())).body(booking);
    }

    @GetMapping(BASE_PATH + "/{bookingId}")
    public BookingResponse getBooking(@PathVariable @Positive Long bookingId) {
        return bookingService.getBooking(bookingId);
    }

    @GetMapping(PropertyController.BASE_PATH + "/{propertyId}/bookings")
    public BookingPageResponse getPropertyBookings(@PathVariable @Positive Long propertyId,
                                                   @RequestParam(required = false) BookingStatus status,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bookingService.getPropertyBookings(propertyId, status, page, size);
    }
}
