package com.rupeek.hotel_booking_system.service.payment.processor;

import com.rupeek.hotel_booking_system.api.dto.payment.CardDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.YearMonth;

@Component
public class CardPaymentProcessor extends GatewayPaymentProcessor<CardDetailsRequest> {

    private final Clock clock;

    public CardPaymentProcessor(PaymentGateway gateway, Clock clock) {
        super(gateway);
        this.clock = clock;
    }

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.CARD;
    }

    @Override
    protected CardDetailsRequest instrumentDetails(PaymentRequest request) {
        return request.card();
    }

    @Override
    protected String detailsField() {
        return "card";
    }

    @Override
    protected String validateAndDescribe(CardDetailsRequest card) {
        String number = card.cardNumber() == null ? "" : card.cardNumber().replace(" ", "");
        if (!number.matches("\\d{12,19}") || !passesLuhnCheck(number)) {
            throw new InvalidPaymentDetailsException("Card number is invalid");
        }
        if (card.cvv() == null || !card.cvv().matches("\\d{3,4}")) {
            throw new InvalidPaymentDetailsException("Card CVV is invalid");
        }
        if (card.holderName() == null || card.holderName().isBlank()) {
            throw new InvalidPaymentDetailsException("Card holder name is required");
        }
        if (expiry(card).isBefore(YearMonth.now(clock))) {
            throw new InvalidPaymentDetailsException("Card has expired");
        }
        return number;
    }

    private static YearMonth expiry(CardDetailsRequest card) {
        if (card.expiryMonth() == null || card.expiryYear() == null) {
            throw new InvalidPaymentDetailsException("Card expiry month and year are required");
        }
        try {
            return YearMonth.of(card.expiryYear(), card.expiryMonth());
        } catch (DateTimeException ex) {
            throw new InvalidPaymentDetailsException("Card expiry is invalid");
        }
    }

    static boolean passesLuhnCheck(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
