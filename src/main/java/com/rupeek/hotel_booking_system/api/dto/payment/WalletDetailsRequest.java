package com.rupeek.hotel_booking_system.api.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param walletId the customer's account at the wallet provider, usually a mobile number
 */
public record WalletDetailsRequest(@NotNull WalletProvider provider, @NotBlank @Size(max = 50) String walletId) {
}
