package com.rupeek.hotel_booking_system.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param holdDuration how long a booking awaiting payment keeps its rooms reserved
 */
@Validated
@ConfigurationProperties(prefix = "hotel.booking")
public record BookingProperties(@NotNull @DurationMin(minutes = 1) @DurationMax(hours = 24) Duration holdDuration) {
}
