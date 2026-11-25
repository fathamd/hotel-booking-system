package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.cancellation.CancellationResponse;
import com.rupeek.hotel_booking_system.service.cancellation.BookingCancellationService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingCancellationController {

    private final BookingCancellationService cancellationService;

    public BookingCancellationController(BookingCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @PostMapping(BookingController.BASE_PATH + "/{bookingId}/cancellation")
    public CancellationResponse cancelBooking(@PathVariable @Positive Long bookingId) {
        return cancellationService.cancel(bookingId);
    }
}
