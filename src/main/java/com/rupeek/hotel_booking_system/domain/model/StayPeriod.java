package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A stay expressed as the half-open date range [checkIn, checkOut).
 * The guest occupies the nights of checkIn up to, but excluding, checkOut,
 * so a stay ending on a date does not overlap a stay starting on that same date.
 */
@Embeddable
public record StayPeriod(
        @NotNull @Column(name = "check_in_date", nullable = false) LocalDate checkIn,
        @NotNull @Column(name = "check_out_date", nullable = false) LocalDate checkOut) {

    public static final LocalTime STANDARD_CHECK_IN_TIME = LocalTime.of(14, 0);
    public static final int MAX_BOOKABLE_NIGHTS = 30;

    public StayPeriod {
        DomainPreconditions.requireNonNull(checkIn, "checkIn");
        DomainPreconditions.requireNonNull(checkOut, "checkOut");
        if (!checkOut.isAfter(checkIn)) {
            throw new DomainValidationException("checkOut must be after checkIn");
        }
    }

    /**
     * Rules for a stay that can be searched or booked now. Not enforced in the constructor, because
     * historic bookings loaded from the database must remain representable.
     */
    public void requireBookableFrom(LocalDate today) {
        if (checkIn.isBefore(today)) {
            throw new DomainValidationException("checkIn cannot be in the past");
        }
        if (numberOfNights() > MAX_BOOKABLE_NIGHTS) {
            throw new DomainValidationException("stay cannot exceed " + MAX_BOOKABLE_NIGHTS + " nights");
        }
    }

    public long numberOfNights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public List<LocalDate> nights() {
        return checkIn.datesUntil(checkOut).toList();
    }

    public boolean overlaps(StayPeriod other) {
        return checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(checkOut);
    }

    public boolean includesNight(LocalDate date) {
        return !date.isBefore(checkIn) && date.isBefore(checkOut);
    }

    public LocalDateTime checkInDateTime() {
        return checkIn.atTime(STANDARD_CHECK_IN_TIME);
    }

    /**
     * The check-in moment at a property in {@code zone}.
     */
    public Instant checkInInstant(ZoneId zone) {
        return checkInDateTime().atZone(DomainPreconditions.requireNonNull(zone, "zone")).toInstant();
    }
}
