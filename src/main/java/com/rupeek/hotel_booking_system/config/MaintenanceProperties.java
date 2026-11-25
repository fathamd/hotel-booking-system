package com.rupeek.hotel_booking_system.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the background jobs that keep bookings, payments, refunds and inventory consistent.
 *
 * @param batchSize                  maximum number of records a job processes per run
 * @param paymentReconciliationGrace how long a payment may await the gateway's answer before it is reconciled;
 *                                   must exceed the gateway timeout so in-flight requests are not touched
 * @param refundRetryGrace           how long a refund may stay pending before it is re-sent to the gateway
 */
@Validated
@ConfigurationProperties(prefix = "hotel.maintenance")
public record MaintenanceProperties(
        @Min(1) @Max(10_000) @DefaultValue("100") int batchSize,
        @NotNull @DurationMin(seconds = 1) @DefaultValue("2m") Duration paymentReconciliationGrace,
        @NotNull @DurationMin(seconds = 1) @DefaultValue("2m") Duration refundRetryGrace) {
}
