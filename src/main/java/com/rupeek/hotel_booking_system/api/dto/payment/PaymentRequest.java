package com.rupeek.hotel_booking_system.api.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Pays for a booking. The amount is never supplied by the client; it is always the booking total.
 * Exactly one instrument must be provided and it must match {@code method}; the matching
 * {@code PaymentProcessor} checks the latter.
 */
public record PaymentRequest(@NotNull PaymentMethod method,
                             @Valid CardDetailsRequest card,
                             @Valid UpiDetailsRequest upi,
                             @Valid WalletDetailsRequest wallet) {

    public static PaymentRequest card(CardDetailsRequest card) {
        return new PaymentRequest(PaymentMethod.CARD, card, null, null);
    }

    public static PaymentRequest upi(UpiDetailsRequest upi) {
        return new PaymentRequest(PaymentMethod.UPI, null, upi, null);
    }

    public static PaymentRequest wallet(WalletDetailsRequest wallet) {
        return new PaymentRequest(PaymentMethod.WALLET, null, null, wallet);
    }

    @JsonIgnore
    @AssertTrue(message = "exactly one of card, upi or wallet details must be provided")
    public boolean isSingleInstrument() {
        return Stream.of(card, upi, wallet).filter(Objects::nonNull).count() == 1;
    }
}
