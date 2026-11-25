package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.api.dto.payment.CardDetailsRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.UpiDetailsRequest;
import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingHoldExpiredException;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.IdempotencyKeyReuseException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.domain.exception.PaymentInProgressException;
import com.rupeek.hotel_booking_system.domain.exception.UnsupportedPaymentMethodException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.PendingPaymentRef;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayChargeResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final long BOOKING_ID = 30L;
    private static final long PAYMENT_ID = 40L;
    private static final String KEY = "pay-key-1";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final PaymentRequest CARD_REQUEST = PaymentRequest.card(
            new CardDetailsRequest(CARD_NUMBER, "Asha Rao", 12, 2030, "123"));
    private static final PaymentInstrument CARD = new PaymentInstrument(PaymentMethod.CARD, CARD_NUMBER);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private InventoryReservationService inventoryReservationService;

    private final PaymentProcessor cardProcessor = mock(PaymentProcessor.class);
    private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private RoomType roomType;
    private Booking booking;
    private PaymentService service;
    private Payment saved;

    @BeforeEach
    void setUp() {
        when(cardProcessor.supportedMethod()).thenReturn(PaymentMethod.CARD);
        service = serviceWith(new PaymentProcessorRegistry(List.of(cardProcessor)), clock);
        roomType = DomainFixtures.roomType(DomainFixtures.property(DomainFixtures.owner()));
        ReflectionTestUtils.setField(roomType, "id", 20L);
        booking = DomainFixtures.pendingBooking(roomType);
        ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
    }

    private PaymentService serviceWith(PaymentProcessorRegistry registry, Clock serviceClock) {
        return new PaymentService(bookingRepository, paymentRepository, refundRepository, registry, paymentGateway,
                inventoryReservationService, TransactionOperations.withoutTransaction(), serviceClock);
    }

    private void givenValidCard() {
        when(cardProcessor.prepare(CARD_REQUEST)).thenReturn(CARD);
    }

    private void givenNewPaymentForLockedBooking() {
        givenValidCard();
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", PAYMENT_ID);
            return saved;
        });
        when(paymentRepository.findById(PAYMENT_ID)).thenAnswer(inv -> Optional.ofNullable(saved));
    }

    private void givenNewPaymentForLockedBookingWithoutCompletion() {
        givenValidCard();
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", PAYMENT_ID);
            return saved;
        });
    }

    private Payment pendingPayment(Booking forBooking, PaymentInstrument instrument) {
        Payment payment = Payment.initiate(forBooking, instrument.method(), KEY,
                PaymentService.fingerprint(forBooking.getId(), instrument), NOW);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    @Nested
    class SuccessfulPayment {

        @Test
        void chargesTheBookingTotalAndConfirmsTheBooking() {
            givenNewPaymentForLockedBooking();
            when(cardProcessor.charge(any(), eq(CARD))).thenReturn(PaymentResult.success("TXN-1"));

            PaymentOutcome outcome = service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            ArgumentCaptor<PaymentCharge> charge = ArgumentCaptor.forClass(PaymentCharge.class);
            verify(cardProcessor).charge(charge.capture(), eq(CARD));
            assertThat(charge.getValue().amount()).isEqualByComparingTo("7500.00");
            assertThat(charge.getValue().idempotencyKey()).isEqualTo(KEY);
            assertThat(charge.getValue().bookingId()).isEqualTo(BOOKING_ID);

            assertThat(outcome.replayed()).isFalse();
            assertThat(outcome.payment().id()).isEqualTo(PAYMENT_ID);
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(outcome.payment().transactionReference()).isEqualTo("TXN-1");
            assertThat(outcome.payment().completedAt()).isEqualTo(NOW);
            assertThat(outcome.payment().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getConfirmedAt()).isEqualTo(NOW);
            verifyNoInteractions(inventoryReservationService);
        }

        @Test
        void recordsAHashedFingerprintInsteadOfTheInstrument() {
            givenNewPaymentForLockedBooking();
            when(cardProcessor.charge(any(), eq(CARD))).thenReturn(PaymentResult.success("TXN-1"));

            service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            assertThat(saved.getRequestFingerprint()).hasSize(Payment.FINGERPRINT_LENGTH)
                    .doesNotContain(CARD_NUMBER)
                    .isEqualTo(PaymentService.fingerprint(BOOKING_ID, CARD));
        }
    }

    @Nested
    class FailedPayment {

        @Test
        void marksBookingPaymentFailedAndReleasesItsInventory() {
            givenNewPaymentForLockedBooking();
            when(cardProcessor.charge(any(), eq(CARD))).thenReturn(PaymentResult.failure("TXN-2", "Insufficient funds"));

            PaymentOutcome outcome = service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(outcome.payment().failureReason()).isEqualTo("Insufficient funds");
            assertThat(outcome.payment().transactionReference()).isEqualTo("TXN-2");
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
            assertThat(booking.getConfirmedAt()).isNull();
            assertThat(booking.holdsInventory()).isFalse();
            verify(inventoryReservationService).release(booking);
        }

        @Test
        void invalidInstrumentIsRejectedBeforeTouchingTheBooking() {
            when(cardProcessor.prepare(CARD_REQUEST)).thenThrow(new InvalidPaymentDetailsException("Card has expired"));

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(InvalidPaymentDetailsException.class);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            verifyNoInteractions(bookingRepository, paymentRepository, inventoryReservationService);
        }

        @Test
        void gatewayWithoutDecisionLeavesThePaymentPendingAndTheRoomsHeld() {
            givenNewPaymentForLockedBookingWithoutCompletion();
            when(cardProcessor.charge(any(), eq(CARD))).thenThrow(new PaymentGatewayException("timeout"));

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(PaymentGatewayException.class);

            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            verifyNoInteractions(inventoryReservationService);
        }
    }

    @Nested
    class InvalidBookingState {

        @ParameterizedTest
        @ValueSource(strings = {"CONFIRMED", "PAYMENT_FAILED", "CANCELLED"})
        void onlyPendingBookingsCanBePaid(String state) {
            switch (BookingStatus.valueOf(state)) {
                case CONFIRMED -> booking.confirm(NOW);
                case PAYMENT_FAILED -> booking.markPaymentFailed();
                default -> booking.cancel(NOW.atZone(ZoneOffset.UTC));
            }
            givenValidCard();
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessage("Booking 30 is " + state + "; only bookings in PENDING_PAYMENT can be paid");
            verify(cardProcessor, never()).charge(any(), any());
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void expiredHoldIsExpiredAndItsRoomsReleasedWithoutCharging() {
            PaymentService later = serviceWith(new PaymentProcessorRegistry(List.of(cardProcessor)),
                    Clock.offset(clock, Duration.ofMinutes(15)));
            givenValidCard();
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> later.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(BookingHoldExpiredException.class);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
            verify(inventoryReservationService).release(booking);
            verify(cardProcessor, never()).charge(any(), any());
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void anotherPaymentInFlightBlocksANewKey() {
            givenValidCard();
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(paymentRepository.existsByBookingIdAndStatus(BOOKING_ID, PaymentStatus.PENDING)).thenReturn(true);

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(PaymentInProgressException.class);
            verify(cardProcessor, never()).charge(any(), any());
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void unknownBookingIsNotFound() {
            givenValidCard();
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Booking with id 30 was not found");
        }
    }

    @Nested
    class UnsupportedMethod {

        @Test
        void methodWithoutRegisteredProcessorIsRejectedBeforeTouchingTheBooking() {
            PaymentRequest upi = PaymentRequest.upi(new UpiDetailsRequest("asha@okbank"));

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, upi))
                    .isInstanceOf(UnsupportedPaymentMethodException.class)
                    .hasMessage("Payment method UPI is not supported");
            verifyNoInteractions(bookingRepository, paymentRepository, inventoryReservationService);
        }

        @Test
        void missingMethodIsAValidationError() {
            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, new PaymentRequest(null, null, null, null)))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessage("method must not be null");
        }
    }

    @Nested
    class DuplicateRequests {

        private Payment completedPayment(Booking forBooking, PaymentInstrument instrument, boolean succeeded) {
            Payment payment = pendingPayment(forBooking, instrument);
            if (succeeded) {
                payment.markSucceeded("TXN-1", NOW);
                forBooking.confirm(NOW);
            } else {
                payment.markFailed("TXN-2", "declined", NOW);
                forBooking.markPaymentFailed();
            }
            return payment;
        }

        private void givenStoredPayment(Payment previous) {
            when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(previous));
        }

        @Test
        void sameKeyReturnsTheStoredPaymentWithoutChargingAgain() {
            givenValidCard();
            givenStoredPayment(completedPayment(booking, CARD, true));

            PaymentOutcome outcome = service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.payment().id()).isEqualTo(PAYMENT_ID);
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(outcome.payment().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(cardProcessor, never()).charge(any(), any());
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void replayOfAFailedPaymentDoesNotReleaseInventoryAgain() {
            givenValidCard();
            givenStoredPayment(completedPayment(booking, CARD, false));

            PaymentOutcome outcome = service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
            verifyNoInteractions(inventoryReservationService);
            verify(cardProcessor, never()).charge(any(), any());
        }

        @Test
        void retryOfAPendingPaymentResumesTheChargeWithTheSameKey() {
            givenValidCard();
            Payment pending = pendingPayment(booking, CARD);
            givenStoredPayment(pending);
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
            when(cardProcessor.charge(any(), eq(CARD))).thenReturn(PaymentResult.success("TXN-1"));

            PaymentOutcome outcome = service.pay(BOOKING_ID, KEY, CARD_REQUEST);

            ArgumentCaptor<PaymentCharge> charge = ArgumentCaptor.forClass(PaymentCharge.class);
            verify(cardProcessor).charge(charge.capture(), eq(CARD));
            assertThat(charge.getValue().idempotencyKey()).isEqualTo(KEY);
            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void sameKeyForAnotherBookingIsRejected() {
            givenValidCard();
            Booking other = DomainFixtures.pendingBooking(roomType);
            ReflectionTestUtils.setField(other, "id", 99L);
            givenStoredPayment(completedPayment(other, CARD, true));

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(IdempotencyKeyReuseException.class);
            verify(cardProcessor, never()).charge(any(), any());
        }

        @Test
        void sameKeyWithAnotherInstrumentIsRejected() {
            givenValidCard();
            givenStoredPayment(completedPayment(booking,
                    new PaymentInstrument(PaymentMethod.CARD, "5555555555554444"), true));

            assertThatThrownBy(() -> service.pay(BOOKING_ID, KEY, CARD_REQUEST))
                    .isInstanceOf(IdempotencyKeyReuseException.class)
                    .hasMessage("Idempotency key 'pay-key-1' was already used for a different payment request");
            verify(cardProcessor, never()).charge(any(), any());
        }

        @Test
        void sameKeyWithAnotherMethodIsRejected() {
            PaymentProcessor upiProcessor = mock(PaymentProcessor.class);
            when(upiProcessor.supportedMethod()).thenReturn(PaymentMethod.UPI);
            PaymentRequest upiRequest = PaymentRequest.upi(new UpiDetailsRequest("asha@okbank"));
            when(upiProcessor.prepare(upiRequest)).thenReturn(new PaymentInstrument(PaymentMethod.UPI, "asha@okbank"));
            PaymentService twoMethods = serviceWith(
                    new PaymentProcessorRegistry(List.of(cardProcessor, upiProcessor)), clock);
            givenStoredPayment(completedPayment(booking, CARD, true));

            assertThatThrownBy(() -> twoMethods.pay(BOOKING_ID, KEY, upiRequest))
                    .isInstanceOf(IdempotencyKeyReuseException.class);
            verify(upiProcessor, never()).charge(any(), any());
        }

        @Test
        void blankKeyIsRejected() {
            assertThatThrownBy(() -> service.pay(BOOKING_ID, " ", CARD_REQUEST))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessage("idempotencyKey must not be blank");
            verifyNoInteractions(bookingRepository, paymentRepository);
        }
    }

    @Nested
    class Reconciliation {

        private static final Duration GRACE = Duration.ofMinutes(2);
        private Payment pending;

        @BeforeEach
        void stalePendingPayment() {
            pending = pendingPayment(booking, CARD);
            when(paymentRepository.findRefsByStatusInitiatedBefore(eq(PaymentStatus.PENDING),
                    eq(NOW.minus(GRACE)), any())).thenReturn(List.of(new PendingPaymentRef(PAYMENT_ID, BOOKING_ID, KEY)));
            lenient().when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking));
            lenient().when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        }

        @Test
        void appliesTheDecisionTheGatewayRecorded() {
            when(paymentGateway.findCharge(KEY)).thenReturn(Optional.of(GatewayChargeResponse.approved("TXN-9")));

            assertThat(service.reconcileStalePayments(GRACE, 10)).isEqualTo(1);

            assertThat(pending.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(pending.getTransactionReference()).isEqualTo("TXN-9");
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        void chargeTheGatewayNeverReceivedFailsAndReleasesTheRooms() {
            when(paymentGateway.findCharge(KEY)).thenReturn(Optional.empty());

            service.reconcileStalePayments(GRACE, 10);

            assertThat(pending.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(pending.getFailureReason()).isEqualTo(PaymentService.NO_GATEWAY_DECISION);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
            verify(inventoryReservationService).release(booking);
        }

        @Test
        void lateSuccessForABookingThatCanNoLongerBeConfirmedIsRefunded() {
            booking.cancel(NOW.atZone(ZoneOffset.UTC));
            when(paymentGateway.findCharge(KEY)).thenReturn(Optional.of(GatewayChargeResponse.approved("TXN-9")));

            service.reconcileStalePayments(GRACE, 10);

            ArgumentCaptor<Refund> refund = ArgumentCaptor.forClass(Refund.class);
            verify(refundRepository).save(refund.capture());
            assertThat(refund.getValue().getAmount()).isEqualByComparingTo(pending.getAmount());
            assertThat(refund.getValue().getStatus()).isEqualTo(RefundStatus.PENDING);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void unreachableGatewayLeavesThePaymentForTheNextRun() {
            when(paymentGateway.findCharge(KEY)).thenThrow(new PaymentGatewayException("down"));

            assertThat(service.reconcileStalePayments(GRACE, 10)).isZero();
            assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }
}
