package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.UnsupportedPaymentMethodException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Selects the {@link PaymentProcessor} for a payment method by lookup instead of conditionals.
 * Two processors claiming the same method is a configuration error and fails application startup.
 */
@Component
public class PaymentProcessorRegistry {

    private final Map<PaymentMethod, PaymentProcessor> processors;

    public PaymentProcessorRegistry(List<PaymentProcessor> processors) {
        Map<PaymentMethod, PaymentProcessor> byMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentProcessor processor : processors) {
            PaymentProcessor previous = byMethod.putIfAbsent(processor.supportedMethod(), processor);
            if (previous != null) {
                throw new IllegalStateException("Payment method " + processor.supportedMethod()
                        + " is handled by both " + previous.getClass().getName()
                        + " and " + processor.getClass().getName());
            }
        }
        this.processors = Collections.unmodifiableMap(byMethod);
    }

    /**
     * @throws UnsupportedPaymentMethodException if no processor is registered for {@code method}
     */
    public PaymentProcessor processorFor(PaymentMethod method) {
        DomainPreconditions.requireNonNull(method, "method");
        PaymentProcessor processor = processors.get(method);
        if (processor == null) {
            throw new UnsupportedPaymentMethodException(method);
        }
        return processor;
    }
}
