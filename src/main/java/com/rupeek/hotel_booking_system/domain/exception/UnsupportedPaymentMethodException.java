package com.rupeek.hotel_booking_system.domain.exception;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;

public class UnsupportedPaymentMethodException extends DomainValidationException {

    public UnsupportedPaymentMethodException(PaymentMethod method) {
        super("Payment method " + method + " is not supported");
    }
}
