package com.rupeek.hotel_booking_system.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({InventoryProperties.class, BookingProperties.class, MaintenanceProperties.class,
        PropertyDefaults.class})
public class InventoryConfiguration {
}
