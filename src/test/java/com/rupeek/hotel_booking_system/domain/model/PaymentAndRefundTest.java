package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.DomainFixtures;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.rupeek.hotel_booking_system.domain.DomainFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAndRefundTest {

    private final Booking booking = DomainFixtures.pendingBooking();

    @Test
    void paymentAmountComesFromBooking() {
        Payment payment = Payment.initiate(booking, PaymentMethod.UPI, "key-1", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);

        assertThat(payment.getAmount()).isEqualByComparingTo(booking.getTotalPrice());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void paymentRequiresPendingBookingAndIdempotencyKey() {
        assertThatThrownBy(() -> Payment.initiate(booking, PaymentMethod.CARD, " ", DomainFixtures.FINGERPRINT, DomainFixtures.NOW))
                .isInstanceOf(DomainValidationException.class);

        booking.markPaymentFailed();
        assertThatThrownBy(() -> Payment.initiate(booking, PaymentMethod.CARD, "key-2", DomainFixtures.FINGERPRINT, DomainFixtures.NOW))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void paymentOutcomeCanBeRecordedOnlyOnce() {
        Payment payment = Payment.initiate(booking, PaymentMethod.CARD, "key-3", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);

        payment.markSucceeded("TXN-1", NOW);

        assertThat(payment.isSuccessful()).isTrue();
        assertThat(payment.getTransactionReference()).isEqualTo("TXN-1");
        assertThatThrownBy(() -> payment.markFailed(null, "late failure", NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void failedPaymentRecordsReason() {
        Payment payment = Payment.initiate(booking, PaymentMethod.WALLET, "key-4", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);

        payment.markFailed(null, "Insufficient wallet balance", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Insufficient wallet balance");
    }

    @Test
    void refundRequiresSuccessfulPaymentAndBoundedAmount() {
        Payment payment = Payment.initiate(booking, PaymentMethod.CARD, "key-5", DomainFixtures.FINGERPRINT, DomainFixtures.NOW);
        assertThatThrownBy(() -> Refund.initiate(payment, BigDecimal.TEN, DomainFixtures.NOW))
                .isInstanceOf(InvalidPaymentStateException.class);

        payment.markSucceeded("TXN-2", NOW);
        assertThatThrownBy(() -> Refund.initiate(payment, new BigDecimal("7500.01"), DomainFixtures.NOW))
                .isInstanceOf(DomainValidationException.class);

        Refund refund = Refund.initiate(payment, new BigDecimal("3750"), DomainFixtures.NOW);
        refund.markSucceeded("RF-1", NOW);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.getAmount()).isEqualByComparingTo("3750.00");
        assertThatThrownBy(() -> refund.markFailed("again", NOW)).isInstanceOf(InvalidPaymentStateException.class);
    }
}
