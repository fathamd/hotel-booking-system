package com.rupeek.hotel_booking_system.api.dto.booking;

import com.rupeek.hotel_booking_system.domain.model.GuestDetails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestRequest(
        @NotBlank @Size(max = GuestDetails.NAME_MAX) String fullName,
        @NotBlank @Email @Size(max = GuestDetails.EMAIL_MAX) String email,
        @Size(max = GuestDetails.PHONE_MAX) String phone) {
}
