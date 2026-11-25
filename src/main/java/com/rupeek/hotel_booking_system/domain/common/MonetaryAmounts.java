package com.rupeek.hotel_booking_system.domain.common;

import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Central place for monetary scale and rounding rules so every amount is stored consistently.
 */
public final class MonetaryAmounts {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private MonetaryAmounts() {
    }

    public static BigDecimal normalize(BigDecimal amount) {
        return DomainPreconditions.requireNonNull(amount, "amount").setScale(SCALE, ROUNDING);
    }

    public static BigDecimal requirePositive(BigDecimal amount, String fieldName) {
        DomainPreconditions.requireNonNull(amount, fieldName);
        if (amount.signum() <= 0) {
            throw new DomainValidationException(fieldName + " must be greater than zero");
        }
        return normalize(amount);
    }

    public static BigDecimal requireNonNegative(BigDecimal amount, String fieldName) {
        DomainPreconditions.requireNonNull(amount, fieldName);
        if (amount.signum() < 0) {
            throw new DomainValidationException(fieldName + " must not be negative");
        }
        return normalize(amount);
    }

    public static BigDecimal percentageOf(BigDecimal amount, BigDecimal percentage) {
        return normalize(amount.multiply(percentage).divide(ONE_HUNDRED, SCALE, ROUNDING));
    }
}
