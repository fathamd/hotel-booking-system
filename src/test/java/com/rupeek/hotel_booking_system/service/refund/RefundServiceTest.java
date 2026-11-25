package com.rupeek.hotel_booking_system.service.refund;

import com.rupeek.hotel_booking_system.api.dto.cancellation.RefundResponse;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentStateException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGateway;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final long PAYMENT_ID = 40L;
    private static final long REFUND_ID = 50L;
    private static final String FIRST_KEY = "refund-payment-" + PAYMENT_ID;

    @Mock
    private RefundRepository refundRepository;
    @Mock
    private RefundGateway refundGateway;

    private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private RefundService service;
    private Payment payment;
    private Refund refund;

    @BeforeEach
    void setUp() {
        service = new RefundService(refundRepository, refundGateway, TransactionOperations.withoutTransaction(), clock);
        Booking booking = DomainFixtures.pendingBooking();
        payment = Payment.initiate(booking, PaymentMethod.CARD, "pay-key", DomainFixtures.FINGERPRINT, NOW);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.markSucceeded("TXN-1", NOW);
        refund = Refund.initiate(payment, new BigDecimal("3750.00"), NOW);
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
    }

    private void givenStoredRefund() {
        when(refundRepository.findByIdForUpdate(REFUND_ID)).thenReturn(Optional.of(refund));
    }

    @Test
    void approvedRefundIsRecordedAgainstThePayment() {
        givenStoredRefund();
        when(refundGateway.refund(any())).thenReturn(GatewayRefundResponse.approved("RFND-1"));

        RefundResponse response = service.process(REFUND_ID);

        ArgumentCaptor<GatewayRefundRequest> request = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(refundGateway).refund(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(FIRST_KEY);
        assertThat(request.getValue().originalTransactionReference()).isEqualTo("TXN-1");
        assertThat(response.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(response.refundReference()).isEqualTo("RFND-1");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("3750.00");
    }

    @Test
    void unreachableGatewayLeavesTheRefundPending() {
        givenStoredRefund();
        when(refundGateway.refund(any())).thenThrow(new RefundGatewayException("timeout"));

        assertThatThrownBy(() -> service.process(REFUND_ID)).isInstanceOf(RefundGatewayException.class);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void refundAlreadySettledIsNotSentAgain() {
        refund.markSucceeded("RFND-1", NOW);
        givenStoredRefund();
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund));

        assertThat(service.process(REFUND_ID).status()).isEqualTo(RefundStatus.SUCCEEDED);
        verify(refundGateway, never()).refund(any());
    }

    @Test
    void retryOfARejectedRefundUsesANewGatewayKey() {
        refund.markFailed("Account closed", NOW);
        givenStoredRefund();
        when(refundGateway.refund(any())).thenReturn(GatewayRefundResponse.approved("RFND-2"));

        RefundResponse response = service.retry(REFUND_ID);

        ArgumentCaptor<GatewayRefundRequest> request = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(refundGateway).refund(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(FIRST_KEY + "-attempt-2");
        assertThat(response.status()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void onlyAFailedRefundCanBeRetried() {
        givenStoredRefund();

        assertThatThrownBy(() -> service.retry(REFUND_ID)).isInstanceOf(InvalidPaymentStateException.class);
        verify(refundGateway, never()).refund(any());
    }

    @Test
    void retryJobResendsStalePendingRefundsWithTheSameKey() {
        Duration grace = Duration.ofMinutes(2);
        when(refundRepository.findIdsByStatusRequestedBefore(eq(RefundStatus.PENDING), eq(NOW.minus(grace)), any()))
                .thenReturn(List.of(REFUND_ID));
        givenStoredRefund();
        when(refundGateway.refund(any())).thenReturn(GatewayRefundResponse.approved("RFND-1"));

        assertThat(service.retryPendingRefunds(grace, 10)).isEqualTo(1);

        ArgumentCaptor<GatewayRefundRequest> request = ArgumentCaptor.forClass(GatewayRefundRequest.class);
        verify(refundGateway).refund(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(FIRST_KEY);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }
}
