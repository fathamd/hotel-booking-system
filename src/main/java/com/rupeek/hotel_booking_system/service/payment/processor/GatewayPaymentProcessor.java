package com.rupeek.hotel_booking_system.service.payment.processor;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.service.payment.PaymentCharge;
import com.rupeek.hotel_booking_system.service.payment.PaymentInstrument;
import com.rupeek.hotel_booking_system.service.payment.PaymentProcessor;
import com.rupeek.hotel_booking_system.service.payment.PaymentResult;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayChargeRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;

/**
 * Template for processors that charge through a {@link PaymentGateway}. Subclasses only extract and validate
 * their own instrument; building the gateway request and mapping its response is shared.
 *
 * @param <D> the method-specific instrument details
 */
abstract class GatewayPaymentProcessor<D> implements PaymentProcessor {

    private final PaymentGateway gateway;

    protected GatewayPaymentProcessor(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public final PaymentInstrument prepare(PaymentRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        D details = instrumentDetails(request);
        if (details == null) {
            throw new InvalidPaymentDetailsException(supportedMethod() + " payment requires '"
                    + detailsField() + "' details");
        }
        return new PaymentInstrument(supportedMethod(), validateAndDescribe(details));
    }

    @Override
    public final PaymentResult charge(PaymentCharge charge, PaymentInstrument instrument) {
        DomainPreconditions.requireNonNull(charge, "charge");
        DomainPreconditions.requireNonNull(instrument, "instrument");
        if (instrument.method() != supportedMethod()) {
            throw new IllegalArgumentException(supportedMethod() + " processor cannot charge a "
                    + instrument.method() + " instrument");
        }
        return PaymentResult.from(gateway.charge(new GatewayChargeRequest(charge.idempotencyKey(),
                charge.amount(), charge.currency(), supportedMethod(), instrument.identifier(),
                "Hotel booking " + charge.bookingId())));
    }

    /**
     * @return this method's details from the request, or {@code null} if absent
     */
    protected abstract D instrumentDetails(PaymentRequest request);

    protected abstract String detailsField();

    /**
     * @return the instrument identifier sent to the gateway
     * @throws InvalidPaymentDetailsException if the details are not acceptable
     */
    protected abstract String validateAndDescribe(D details);
}
