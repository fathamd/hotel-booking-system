package com.rupeek.hotel_booking_system.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} maintenance jobs. Tests switch it off with
 * {@code hotel.maintenance.scheduling-enabled=false} and run the jobs explicitly, which keeps them deterministic.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hotel.maintenance.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
