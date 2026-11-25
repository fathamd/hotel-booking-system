package com.rupeek.hotel_booking_system.service.payment.processor;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.UpiDetailsRequest;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class UpiPaymentProcessor extends GatewayPaymentProcessor<UpiDetailsRequest> {

    private static final Pattern VPA = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,255}@[a-z][a-z0-9]{1,63}$");

    public UpiPaymentProcessor(PaymentGateway gateway) {
        super(gateway);
    }

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.UPI;
    }

    @Override
    protected UpiDetailsRequest instrumentDetails(PaymentRequest request) {
        return request.upi();
    }

    @Override
    protected String detailsField() {
        return "upi";
    }

    @Override
    protected String validateAndDescribe(UpiDetailsRequest upi) {
        String vpa = upi.vpa() == null ? "" : upi.vpa().strip().toLowerCase(Locale.ROOT);
        if (!VPA.matcher(vpa).matches()) {
            throw new InvalidPaymentDetailsException("UPI VPA '" + upi.vpa() + "' is invalid");
        }
        return vpa;
    }
}
