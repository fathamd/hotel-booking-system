package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;

/**
 * A validated payment instrument, ready to be charged.
 *
 * @param identifier the instrument as the gateway expects it (card number, VPA, wallet id)
 */
public record PaymentInstrument(PaymentMethod method, String identifier) {

    @Override
    public String toString() {
        return "PaymentInstrument[method=" + method + ", identifier=****]";
    }
}
