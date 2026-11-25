package com.rupeek.hotel_booking_system.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param horizonDays number of nights, starting today, for which inventory rows are created
 *                    when a room type is onboarded
 */
@Validated
@ConfigurationProperties(prefix = "hotel.inventory")
public record InventoryProperties(@Min(1) @Max(730) int horizonDays) {
}
