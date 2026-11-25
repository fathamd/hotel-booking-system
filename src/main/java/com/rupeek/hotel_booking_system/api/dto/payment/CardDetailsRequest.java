package com.rupeek.hotel_booking_system.api.dto.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Card details are passed to the gateway only; they are never persisted or logged.
 */
public record CardDetailsRequest(
        @NotBlank @Pattern(regexp = "\\d{12,19}", message = "must be 12 to 19 digits") String cardNumber,
        @NotBlank @Size(max = 100) String holderName,
        @NotNull @Min(1) @Max(12) Integer expiryMonth,
        @NotNull @Min(2000) @Max(2100) Integer expiryYear,
        @NotBlank @Pattern(regexp = "\\d{3,4}", message = "must be 3 or 4 digits") String cvv) {

    @Override
    public String toString() {
        String last4 = cardNumber == null || cardNumber.length() < 4
                ? "****" : cardNumber.substring(cardNumber.length() - 4);
        return "CardDetailsRequest[cardNumber=****" + last4 + ", expiry=" + expiryMonth + "/" + expiryYear + "]";
    }
}
