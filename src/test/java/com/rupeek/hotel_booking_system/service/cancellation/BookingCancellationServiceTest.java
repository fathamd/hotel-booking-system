package com.rupeek.hotel_booking_system.service.cancellation;

import com.rupeek.hotel_booking_system.api.dto.cancellation.CancellationResponse;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.cancellation.CancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.NonRefundableCancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.RefundDecision;
import com.rupeek.hotel_booking_system.domain.cancellation.TieredCancellationPolicy;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingAlreadyCancelledException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.PaymentInProgressException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.refund.RefundService;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGateway;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingCancellationServiceTest {

    private static final long BOOKING_ID = 30L;
    private static final long PAYMENT_ID = 40L;
    private static final long REFUND_ID = 50L;
    private static final LocalDateTime FOUR_DAYS_BEFORE_CHECK_IN = LocalDateTime.of(2026, 12, 16, 10, 0);
    private static final LocalDateTime FOUR_HOURS_BEFORE_CHECK_IN = LocalDateTime.of(2026, 12, 20, 10, 0);
    private static final LocalDateTime AFTER_CHECK_IN = LocalDateTime.of(2026, 12, 20, 15, 0);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private InventoryReservationService inventoryReservationService;
    @Mock
    private RefundGateway refundGateway;

    private final CancellationPolicyResolver defaultResolver = new PropertyCancellationPolicyResolver(List.of(
            TieredCancellationPolicy.flexible(), TieredCancellationPolicy.moderate(),
            new NonRefundableCancellationPolicy()));
    private Booking booking;
    private Payment payment;
    private Refund recordedRefund;

    @BeforeEach
    void setUp() {
        givenBookingAtPropertyWith(CancellationPolicyType.FLEXIBLE);
    }

    private void givenBookingAtPropertyWith(CancellationPolicyType policy) {
        RoomType roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner(), policy));
        ReflectionTestUtils.setField(roomType, "id", 20L);
        booking = DomainFixtures.pendingBooking(roomType);
        ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
    }

    private void givenConfirmedAndPaid() {
        payment = Payment.initiate(booking, PaymentMethod.CARD, "pay-key", DomainFixtures.FINGERPRINT, NOW);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.markSucceeded("TXN-1", NOW);
        booking.confirm(NOW);
        when(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.of(payment));
    }

    private void givenLockedBooking() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
    }

    private void givenRefundGatewayAnswers(GatewayRefundResponse response) {
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund refund = inv.getArgument(0);
            ReflectionTestUtils.setField(refund, "id", REFUND_ID);
            recordedRefund = refund;
            return refund;
        });
        when(refundRepository.findByIdForUpdate(REFUND_ID)).thenAnswer(inv -> Optional.ofNullable(recordedRefund));
        when(refundGateway.refund(any())).thenReturn(response);
    }

    private BookingCancellationService serviceAt(LocalDateTime now) {
        return serviceAt(now, defaultResolver);
    }

    private BookingCancellationService serviceAt(LocalDateTime now, CancellationPolicyResolver resolver) {
        Clock clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        TransactionOperations transactions = TransactionOperations.withoutTransaction();
        RefundService refundService = new RefundService(refundRepository, refundGateway, transactions, clock);
        return new BookingCancellationService(bookingRepository, paymentRepository, refundRepository,
                inventoryReservationService, resolver, refundService, transactions, clock);
    }

    private void verifyInventoryReleased() {
        verify(inventoryReservationService).release(booking);
    }

    @Nested
    class SuccessfulCancellation {

        @Test
        void fullRefundWhenFlexiblePolicyNoticeIsMet() {
            givenLockedBooking();
            givenConfirmedAndPaid();
            givenRefundGatewayAnswers(GatewayRefundResponse.approved("RFND-1"));

            CancellationResponse response = serviceAt(NOW).cancel(BOOKING_ID);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.cancelledAt()).isEqualTo(NOW);
            assertThat(response.cancellationPolicy()).isEqualTo(CancellationPolicyType.FLEXIBLE);
            assertThat(response.paidAmount()).isEqualByComparingTo("7500.00");
            assertThat(response.refundPercentage()).isEqualByComparingTo("100");
            assertThat(response.refundAmount()).isEqualByComparingTo("7500.00");
            assertThat(response.refund().id()).isEqualTo(REFUND_ID);
            assertThat(response.refund().paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(response.refund().status()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(response.refund().refundReference()).isEqualTo("RFND-1");
            assertThat(response.refund().processedAt()).isEqualTo(NOW);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.getRefundedAmount()).isEqualByComparingTo("7500.00");
            verifyInventoryReleased();
        }

        @Test
        void refundsAgainstTheOriginalChargeWithAPaymentDerivedIdempotencyKey() {
            givenLockedBooking();
            givenConfirmedAndPaid();
            givenRefundGatewayAnswers(GatewayRefundResponse.approved("RFND-1"));

            serviceAt(NOW).cancel(BOOKING_ID);

            ArgumentCaptor<GatewayRefundRequest> request = ArgumentCaptor.forClass(GatewayRefundRequest.class);
            verify(refundGateway).refund(request.capture());
            assertThat(request.getValue().idempotencyKey()).isEqualTo("refund-payment-" + PAYMENT_ID);
            assertThat(request.getValue().originalTransactionReference()).isEqualTo("TXN-1");
            assertThat(request.getValue().amount()).isEqualByComparingTo("7500.00");
            assertThat(request.getValue().currency()).isEqualTo("INR");
        }

        @Test
        void partialRefundWhenOnlyTheModeratePolicyLowerTierIsMet() {
            givenBookingAtPropertyWith(CancellationPolicyType.MODERATE);
            givenLockedBooking();
            givenConfirmedAndPaid();
            givenRefundGatewayAnswers(GatewayRefundResponse.approved("RFND-2"));

            CancellationResponse response = serviceAt(FOUR_DAYS_BEFORE_CHECK_IN).cancel(BOOKING_ID);

            assertThat(response.cancellationPolicy()).isEqualTo(CancellationPolicyType.MODERATE);
            assertThat(response.refundPercentage()).isEqualByComparingTo("50");
            assertThat(response.refundAmount()).isEqualByComparingTo("3750.00");
            assertThat(response.refund().amount()).isEqualByComparingTo("3750.00");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.getRefundedAmount()).isEqualByComparingTo("3750.00");
            verifyInventoryReleased();
        }

        @Test
        void noRefundForNonRefundablePolicyButInventoryIsStillReleased() {
            givenBookingAtPropertyWith(CancellationPolicyType.NON_REFUNDABLE);
            givenLockedBooking();
            givenConfirmedAndPaid();

            CancellationResponse response = serviceAt(NOW).cancel(BOOKING_ID);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.refundPercentage()).isEqualByComparingTo("0");
            assertThat(response.refundAmount()).isEqualByComparingTo("0");
            assertThat(response.refund()).isNull();
            assertThat(response.refundRule()).startsWith("NON_REFUNDABLE");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getRefundedAmount()).isEqualByComparingTo("0");
            verifyNoInteractions(refundRepository, refundGateway);
            verifyInventoryReleased();
        }

        @Test
        void noRefundWhenCancelledTooCloseToCheckIn() {
            givenLockedBooking();
            givenConfirmedAndPaid();

            CancellationResponse response = serviceAt(FOUR_HOURS_BEFORE_CHECK_IN).cancel(BOOKING_ID);

            assertThat(response.refundAmount()).isEqualByComparingTo("0");
            assertThat(response.refund()).isNull();
            assertThat(response.refundRule()).contains("too close to check-in");
            verifyNoInteractions(refundGateway);
            verifyInventoryReleased();
        }

        @Test
        void unpaidPendingBookingIsCancelledWithoutRefundAndReleasesItsHold() {
            givenLockedBooking();
            when(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.SUCCEEDED))
                    .thenReturn(Optional.empty());

            CancellationResponse response = serviceAt(NOW).cancel(BOOKING_ID);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.paidAmount()).isEqualByComparingTo("0");
            assertThat(response.refundAmount()).isEqualByComparingTo("0");
            assertThat(response.refundRule()).isEqualTo(BookingCancellationService.NO_PAYMENT_RULE);
            assertThat(response.refund()).isNull();
            verifyNoInteractions(refundRepository, refundGateway);
            verifyInventoryReleased();
        }

        @Test
        void rejectedRefundIsRecordedAsFailedAndTheCancellationStands() {
            givenLockedBooking();
            givenConfirmedAndPaid();
            givenRefundGatewayAnswers(GatewayRefundResponse.rejected("Card account closed"));

            CancellationResponse response = serviceAt(NOW).cancel(BOOKING_ID);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.refund().status()).isEqualTo(RefundStatus.FAILED);
            assertThat(response.refund().failureReason()).isEqualTo("Card account closed");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            verifyInventoryReleased();
        }

        @Test
        void policyCanBeReplacedThroughTheResolver() {
            CancellationPolicy quarterRefund = new CancellationPolicy() {
                @Override
                public CancellationPolicyType type() {
                    return CancellationPolicyType.FLEXIBLE;
                }

                @Override
                public RefundDecision evaluate(com.rupeek.hotel_booking_system.domain.cancellation.CancellationContext
                                                       context) {
                    return new RefundDecision(BigDecimal.valueOf(25),
                            MonetaryAmounts.percentageOf(context.paidAmount(), BigDecimal.valueOf(25)), "PROMO: 25%");
                }
            };
            givenLockedBooking();
            givenConfirmedAndPaid();
            givenRefundGatewayAnswers(GatewayRefundResponse.approved("RFND-3"));

            CancellationResponse response = serviceAt(NOW, ignored -> quarterRefund).cancel(BOOKING_ID);

            assertThat(response.refundAmount()).isEqualByComparingTo("1875.00");
            assertThat(response.refundRule()).isEqualTo("PROMO: 25%");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }
    }

    @Nested
    class RejectedCancellation {

        @Test
        void alreadyCancelledBookingCannotBeCancelledAgain() {
            booking.cancel(NOW.atZone(ZoneOffset.UTC));
            givenLockedBooking();

            assertThatThrownBy(() -> serviceAt(NOW).cancel(BOOKING_ID))
                    .isInstanceOf(BookingAlreadyCancelledException.class)
                    .hasMessage("Booking 30 is already cancelled");
            verifyNoInteractions(paymentRepository, refundRepository, refundGateway, inventoryReservationService);
        }

        @Test
        void paymentFailedBookingCannotBeCancelled() {
            booking.markPaymentFailed();
            givenLockedBooking();

            assertThatThrownBy(() -> serviceAt(NOW).cancel(BOOKING_ID))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessage("Booking cannot transition from PAYMENT_FAILED to CANCELLED");
            verifyNoInteractions(refundGateway, inventoryReservationService);
        }

        @Test
        void expiredBookingCannotBeCancelled() {
            booking.expire(NOW.plusHours(1));
            givenLockedBooking();

            assertThatThrownBy(() -> serviceAt(NOW.plusHours(1)).cancel(BOOKING_ID))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessage("Booking cannot transition from EXPIRED to CANCELLED");
            verifyNoInteractions(refundGateway, inventoryReservationService);
        }

        @Test
        void confirmedBookingCannotBeCancelledAfterCheckIn() {
            payment = Payment.initiate(booking, PaymentMethod.CARD, "pay-key", DomainFixtures.FINGERPRINT, NOW);
            payment.markSucceeded("TXN-1", NOW);
            booking.confirm(NOW);
            givenLockedBooking();

            assertThatThrownBy(() -> serviceAt(AFTER_CHECK_IN).cancel(BOOKING_ID))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessageContaining("cannot be cancelled at or after check-in");
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verifyNoInteractions(refundGateway, inventoryReservationService);
        }

        @Test
        void unknownBookingIsNotFound() {
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> serviceAt(NOW).cancel(BOOKING_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void paymentAwaitingTheGatewayBlocksCancellation() {
            givenLockedBooking();
            when(paymentRepository.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.PENDING)).thenReturn(true);

            assertThatThrownBy(() -> serviceAt(NOW).cancel(BOOKING_ID))
                    .isInstanceOf(PaymentInProgressException.class);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            verifyNoInteractions(refundGateway, inventoryReservationService);
        }
    }

    @Nested
    class RefundGatewayUnavailable {

        @Test
        void cancellationStandsAndTheRefundStaysPendingForRetry() {
            givenLockedBooking();
            givenConfirmedAndPaid();
            when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
                recordedRefund = inv.getArgument(0);
                ReflectionTestUtils.setField(recordedRefund, "id", REFUND_ID);
                return recordedRefund;
            });
            when(refundRepository.findByIdForUpdate(REFUND_ID)).thenAnswer(inv -> Optional.of(recordedRefund));
            when(refundGateway.refund(any())).thenThrow(new RefundGatewayException("timeout"));

            CancellationResponse response = serviceAt(NOW).cancel(BOOKING_ID);

            assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(response.refund().status()).isEqualTo(RefundStatus.PENDING);
            assertThat(response.refundAmount()).isEqualByComparingTo("7500.00");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            verifyInventoryReleased();
        }
    }
}
