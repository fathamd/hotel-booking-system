package com.rupeek.hotel_booking_system.api.dto.onboarding;

import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOwnerRequest(
        @NotBlank @Size(max = OwnerAccount.NAME_MAX)
        String name,

        @NotBlank @Email @Size(max = OwnerAccount.EMAIL_MAX)
        String email,

        @Size(max = OwnerAccount.PHONE_MAX)
        @Pattern(regexp = "^\\+?[0-9][0-9 -]{5,18}$", message = "must be a valid phone number")
        String phone) {
}
