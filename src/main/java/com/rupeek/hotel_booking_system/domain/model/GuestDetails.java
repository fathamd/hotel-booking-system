package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contact details of the guest who made the booking.
 */
@Embeddable
public record GuestDetails(
        @NotBlank @Size(max = GuestDetails.NAME_MAX) @Column(name = "guest_name", nullable = false, length = NAME_MAX)
        String fullName,
        @NotBlank @Email @Size(max = GuestDetails.EMAIL_MAX) @Column(name = "guest_email", nullable = false, length = EMAIL_MAX)
        String email,
        @Size(max = GuestDetails.PHONE_MAX) @Column(name = "guest_phone", length = PHONE_MAX)
        String phone) {

    public static final int NAME_MAX = 150;
    public static final int EMAIL_MAX = 254;
    public static final int PHONE_MAX = 20;

    public GuestDetails {
        fullName = DomainPreconditions.requireText(fullName, "guest fullName", NAME_MAX);
        email = DomainPreconditions.requireText(email, "guest email", EMAIL_MAX);
        if (!email.contains("@")) {
            throw new DomainValidationException("guest email must be a valid email address");
        }
        phone = DomainPreconditions.optionalText(phone, "guest phone", PHONE_MAX);
    }
}
