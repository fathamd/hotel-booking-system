package com.rupeek.hotel_booking_system.domain.common;

import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;

/**
 * Invariant checks used by domain objects. Violations raise {@link DomainValidationException}
 * so that domain rules hold regardless of whether bean validation ran at the API boundary.
 */
public final class DomainPreconditions {

    private DomainPreconditions() {
    }

    public static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new DomainValidationException(fieldName + " must not be null");
        }
        return value;
    }

    public static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(fieldName + " must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new DomainValidationException(fieldName + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, fieldName, maxLength);
    }

    public static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new DomainValidationException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    public static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new DomainValidationException(fieldName + " must be greater than zero");
        }
        return value;
    }
}
