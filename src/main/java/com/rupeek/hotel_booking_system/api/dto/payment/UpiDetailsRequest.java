package com.rupeek.hotel_booking_system.api.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param vpa UPI virtual payment address such as {@code asha@okbank}
 */
public record UpiDetailsRequest(@NotBlank @Size(max = 255) String vpa) {
}
