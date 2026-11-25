package com.rupeek.hotel_booking_system.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * @param defaultTimeZone time zone given to a property onboarded without one
 */
@Validated
@ConfigurationProperties(prefix = "hotel.property")
public record PropertyDefaults(@NotNull @DefaultValue("Asia/Kolkata") ZoneId defaultTimeZone) {
}
