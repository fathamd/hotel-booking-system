package com.rupeek.hotel_booking_system.service.payment.processor;

import com.rupeek.hotel_booking_system.api.dto.payment.CardDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.UpiDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.WalletDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.WalletProvider;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.service.payment.PaymentCharge;
import com.rupeek.hotel_booking_system.service.payment.PaymentResult;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayChargeRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayChargeResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentProcessorsTest {

    private static final PaymentCharge CHARGE = new PaymentCharge("key-1", 30L, new BigDecimal("7500.00"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC);

    private final PaymentGateway gateway = mock(PaymentGateway.class);

    private GatewayChargeRequest sentToGateway() {
        ArgumentCaptor<GatewayChargeRequest> request = ArgumentCaptor.forClass(GatewayChargeRequest.class);
        verify(gateway).charge(request.capture());
        return request.getValue();
    }

    private static CardDetailsRequest card(String number, int month, int year) {
        return new CardDetailsRequest(number, "Asha Rao", month, year, "123");
    }

    @Nested
    class Card {

        private final CardPaymentProcessor processor = new CardPaymentProcessor(gateway, CLOCK);

        @Test
        void approvedChargeIsASuccessfulResult() {
            when(gateway.charge(any())).thenReturn(GatewayChargeResponse.approved("TXN-1"));

            PaymentResult result = processor.process(CHARGE, PaymentRequest.card(card("4111111111111111", 12, 2030)));

            assertThat(result).isEqualTo(PaymentResult.success("TXN-1"));
            GatewayChargeRequest sent = sentToGateway();
            assertThat(sent.idempotencyKey()).isEqualTo("key-1");
            assertThat(sent.amount()).isEqualByComparingTo("7500.00");
            assertThat(sent.currency()).isEqualTo("INR");
            assertThat(sent.method()).isEqualTo(PaymentMethod.CARD);
            assertThat(sent.instrument()).isEqualTo("4111111111111111");
            assertThat(sent.toString()).doesNotContain("4111111111111111");
        }

        @Test
        void declinedChargeIsAFailedResult() {
            when(gateway.charge(any())).thenReturn(GatewayChargeResponse.declined("TXN-2", "Insufficient funds"));

            PaymentResult result = processor.process(CHARGE, PaymentRequest.card(card("4000000000000002", 12, 2030)));

            assertThat(result).isEqualTo(PaymentResult.failure("TXN-2", "Insufficient funds"));
        }

        @Test
        void cardExpiringThisMonthIsAccepted() {
            when(gateway.charge(any())).thenReturn(GatewayChargeResponse.approved("TXN-1"));

            assertThat(processor.process(CHARGE, PaymentRequest.card(card("4111111111111111", 12, 2026))).successful())
                    .isTrue();
        }

        @Test
        void expiredCardIsRejectedWithoutCharging() {
            assertThatThrownBy(() -> processor.process(CHARGE, PaymentRequest.card(card("4111111111111111", 11, 2026))))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("Card has expired");
            verifyNoInteractions(gateway);
        }

        @ParameterizedTest
        @ValueSource(strings = {"4111111111111112", "1234", "41111111111111x1"})
        void invalidCardNumberIsRejectedWithoutCharging(String number) {
            assertThatThrownBy(() -> processor.process(CHARGE, PaymentRequest.card(card(number, 12, 2030))))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("Card number is invalid");
            verifyNoInteractions(gateway);
        }

        @Test
        void requestWithoutCardDetailsIsRejected() {
            PaymentRequest upiOnly = new PaymentRequest(PaymentMethod.CARD, null, new UpiDetailsRequest("a@ok"), null);

            assertThatThrownBy(() -> processor.process(CHARGE, upiOnly))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("CARD payment requires 'card' details");
            verifyNoInteractions(gateway);
        }

        @Test
        void luhnCheck() {
            assertThat(CardPaymentProcessor.passesLuhnCheck("4111111111111111")).isTrue();
            assertThat(CardPaymentProcessor.passesLuhnCheck("5500005555555559")).isTrue();
            assertThat(CardPaymentProcessor.passesLuhnCheck("4111111111111121")).isFalse();
        }
    }

    @Nested
    class Upi {

        private final UpiPaymentProcessor processor = new UpiPaymentProcessor(gateway);

        @Test
        void chargesTheNormalisedVpa() {
            when(gateway.charge(any())).thenReturn(GatewayChargeResponse.approved("TXN-3"));

            PaymentResult result = processor.process(CHARGE, PaymentRequest.upi(new UpiDetailsRequest(" Asha.Rao@OkBank ")));

            assertThat(result.successful()).isTrue();
            GatewayChargeRequest sent = sentToGateway();
            assertThat(sent.method()).isEqualTo(PaymentMethod.UPI);
            assertThat(sent.instrument()).isEqualTo("asha.rao@okbank");
        }

        @ParameterizedTest
        @ValueSource(strings = {"asha", "asha@", "@okbank", "as ha@okbank", "asha@ok-bank"})
        void invalidVpaIsRejectedWithoutCharging(String vpa) {
            assertThatThrownBy(() -> processor.process(CHARGE, PaymentRequest.upi(new UpiDetailsRequest(vpa))))
                    .isInstanceOf(InvalidPaymentDetailsException.class);
            verifyNoInteractions(gateway);
        }

        @Test
        void requestWithoutUpiDetailsIsRejected() {
            assertThatThrownBy(() -> processor.process(CHARGE,
                    new PaymentRequest(PaymentMethod.UPI, card("4111111111111111", 12, 2030), null, null)))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("UPI payment requires 'upi' details");
        }
    }

    @Nested
    class Wallet {

        private final WalletPaymentProcessor processor = new WalletPaymentProcessor(gateway);

        @Test
        void chargesTheProviderAccount() {
            when(gateway.charge(any())).thenReturn(GatewayChargeResponse.declined("TXN-4", "Wallet balance too low"));

            PaymentResult result = processor.process(CHARGE,
                    PaymentRequest.wallet(new WalletDetailsRequest(WalletProvider.PAYTM, "9876543210")));

            assertThat(result).isEqualTo(PaymentResult.failure("TXN-4", "Wallet balance too low"));
            GatewayChargeRequest sent = sentToGateway();
            assertThat(sent.method()).isEqualTo(PaymentMethod.WALLET);
            assertThat(sent.instrument()).isEqualTo("PAYTM:9876543210");
        }

        @Test
        void invalidWalletIdIsRejectedWithoutCharging() {
            assertThatThrownBy(() -> processor.process(CHARGE,
                    PaymentRequest.wallet(new WalletDetailsRequest(WalletProvider.PHONEPE, "x y"))))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("Wallet id is invalid");
            verifyNoInteractions(gateway);
        }

        @Test
        void missingProviderIsRejected() {
            assertThatThrownBy(() -> processor.process(CHARGE,
                    PaymentRequest.wallet(new WalletDetailsRequest(null, "9876543210"))))
                    .isInstanceOf(InvalidPaymentDetailsException.class)
                    .hasMessage("Wallet provider is required");
        }
    }
}
