package com.rupeek.hotel_booking_system.service.payment.gateway;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    private static GatewayChargeRequest request(String key, String instrument) {
        return new GatewayChargeRequest(key, new BigDecimal("100.00"), "INR", PaymentMethod.CARD, instrument, "test");
    }

    @Test
    void approvesOrdinaryInstrumentsWithAUniqueReference() {
        GatewayChargeResponse first = gateway.charge(request("k1", "4111111111111111"));
        GatewayChargeResponse second = gateway.charge(request("k2", "4111111111111111"));

        assertThat(first.approved()).isTrue();
        assertThat(first.transactionReference()).startsWith("MOCK-CARD-");
        assertThat(second.transactionReference()).isNotEqualTo(first.transactionReference());
        assertThat(gateway.chargeCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4000000000000002", "PAYTM:9000000002", "fail@okbank"})
    void declinesTheDocumentedTestInstruments(String instrument) {
        GatewayChargeResponse response = gateway.charge(request("k-" + instrument, instrument));

        assertThat(response.approved()).isFalse();
        assertThat(response.transactionReference()).isNotBlank();
        assertThat(response.declineReason()).isEqualTo("CARD payment declined by issuer");
    }

    @Test
    void refundsAreApprovedAndIdempotentOnTheKey() {
        GatewayRefundRequest refund = new GatewayRefundRequest("refund-payment-1", "MOCK-CARD-1",
                new BigDecimal("50.00"), "INR");

        GatewayRefundResponse first = gateway.refund(refund);
        GatewayRefundResponse replay = gateway.refund(refund);

        assertThat(first.approved()).isTrue();
        assertThat(first.refundReference()).startsWith("MOCK-REFUND-");
        assertThat(replay).isEqualTo(first);
        assertThat(gateway.refundCount()).isEqualTo(1);
    }

    @Test
    void repeatedKeyReplaysTheFirstResponseAndChargesOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 32)
                    .mapToObj(i -> pool.submit(() -> gateway.charge(request("same-key", "4111111111111111"))))
                    .toList();
            GatewayChargeResponse first = futures.getFirst().get();
            for (Future<GatewayChargeResponse> future : futures) {
                assertThat(future.get()).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(gateway.chargeCount()).isEqualTo(1);
    }
}
