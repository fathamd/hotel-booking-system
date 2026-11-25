package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.UnsupportedPaymentMethodException;
import com.rupeek.hotel_booking_system.service.payment.gateway.MockPaymentGateway;
import com.rupeek.hotel_booking_system.service.payment.processor.CardPaymentProcessor;
import com.rupeek.hotel_booking_system.service.payment.processor.UpiPaymentProcessor;
import com.rupeek.hotel_booking_system.service.payment.processor.WalletPaymentProcessor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentProcessorRegistryTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();
    private final CardPaymentProcessor card = new CardPaymentProcessor(gateway, Clock.systemUTC());
    private final UpiPaymentProcessor upi = new UpiPaymentProcessor(gateway);
    private final WalletPaymentProcessor wallet = new WalletPaymentProcessor(gateway);

    @Test
    void selectsTheProcessorRegisteredForEachMethod() {
        PaymentProcessorRegistry registry = new PaymentProcessorRegistry(List.of(wallet, card, upi));

        assertThat(registry.processorFor(PaymentMethod.CARD)).isSameAs(card);
        assertThat(registry.processorFor(PaymentMethod.UPI)).isSameAs(upi);
        assertThat(registry.processorFor(PaymentMethod.WALLET)).isSameAs(wallet);
        assertThat(Arrays.stream(PaymentMethod.values()).map(registry::processorFor)).doesNotContainNull();
    }

    @Test
    void methodWithoutProcessorIsUnsupported() {
        PaymentProcessorRegistry registry = new PaymentProcessorRegistry(List.of(card));

        assertThatThrownBy(() -> registry.processorFor(PaymentMethod.WALLET))
                .isInstanceOf(UnsupportedPaymentMethodException.class)
                .hasMessage("Payment method WALLET is not supported");
    }

    @Test
    void twoProcessorsForOneMethodFailFast() {
        PaymentProcessor secondCard = mock(PaymentProcessor.class);
        when(secondCard.supportedMethod()).thenReturn(PaymentMethod.CARD);

        assertThatThrownBy(() -> new PaymentProcessorRegistry(List.of(card, secondCard)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment method CARD is handled by both");
    }
}
