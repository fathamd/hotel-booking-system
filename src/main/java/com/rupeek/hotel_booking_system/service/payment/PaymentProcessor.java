package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;

/**
 * Strategy for one payment method. Implementations are Spring beans discovered by
 * {@link PaymentProcessorRegistry}; supporting a new method means adding an enum constant and one bean,
 * with no change to {@link PaymentService}.
 * <p>
 * Validation ({@link #prepare}) and charging ({@link #charge}) are separate so the service can validate the
 * request and record the attempt before money moves, and call the gateway outside any database transaction.
 */
public interface PaymentProcessor {

    PaymentMethod supportedMethod();

    /**
     * Validates the method-specific instrument in {@code request}. Nothing is charged.
     *
     * @throws com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException if the
     *         instrument is missing or invalid
     */
    PaymentInstrument prepare(PaymentRequest request);

    /**
     * Charges {@code charge.amount()} to a previously {@link #prepare prepared} instrument.
     *
     * @return the outcome; a declined charge is returned, not thrown
     * @throws com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGatewayException if the gateway
     *         gave no decision; retrying with the same idempotency key is safe
     */
    PaymentResult charge(PaymentCharge charge, PaymentInstrument instrument);

    /**
     * Validates and charges in one step.
     */
    default PaymentResult process(PaymentCharge charge, PaymentRequest request) {
        return charge(charge, prepare(request));
    }
}
