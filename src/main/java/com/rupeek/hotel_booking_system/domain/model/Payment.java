package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment attempt for a booking. The amount always comes from the booking total, never from the client.
 * Lifecycle: PENDING -> SUCCEEDED | FAILED; SUCCEEDED -> REFUNDED | PARTIALLY_REFUNDED.
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_payment_transaction_reference", columnNames = "transaction_reference")
        },
        indexes = {
                @Index(name = "idx_payment_booking", columnList = "booking_id"),
                @Index(name = "idx_payment_status_initiated", columnList = "status, initiated_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    public static final int IDEMPOTENCY_KEY_MAX = 100;
    public static final int FINGERPRINT_LENGTH = 64;
    private static final int TRANSACTION_REFERENCE_MAX = 100;
    private static final int FAILURE_REASON_MAX = 500;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, foreignKey = @ForeignKey(name = "fk_payment_booking"))
    private Booking booking;

    @NotNull
    @DecimalMin(value = "0.01")
    @Column(nullable = false, precision = 14, scale = MonetaryAmounts.SCALE)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @NotBlank
    @Size(max = IDEMPOTENCY_KEY_MAX)
    @Column(name = "idempotency_key", nullable = false, length = IDEMPOTENCY_KEY_MAX)
    private String idempotencyKey;

    /**
     * Hash of what was requested (booking, method, instrument). A replay with the same idempotency key must match it,
     * so a key cannot silently stand in for a different request. The raw instrument is never stored.
     */
    @NotBlank
    @Size(min = FINGERPRINT_LENGTH, max = FINGERPRINT_LENGTH)
    @Column(name = "request_fingerprint", nullable = false, length = FINGERPRINT_LENGTH)
    private String requestFingerprint;

    @NotNull
    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Size(max = TRANSACTION_REFERENCE_MAX)
    @Column(name = "transaction_reference", length = TRANSACTION_REFERENCE_MAX)
    private String transactionReference;

    @Size(max = FAILURE_REASON_MAX)
    @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
    private String failureReason;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @NotNull
    @Column(name = "refunded_amount", nullable = false, precision = 14, scale = MonetaryAmounts.SCALE)
    private BigDecimal refundedAmount;

    private Payment(Booking booking, PaymentMethod method, String idempotencyKey, String requestFingerprint,
                    LocalDateTime initiatedAt) {
        this.booking = booking;
        this.amount = booking.getTotalPrice();
        this.method = method;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.initiatedAt = initiatedAt;
        this.status = PaymentStatus.PENDING;
        this.refundedAmount = MonetaryAmounts.ZERO;
    }

    public static Payment initiate(Booking booking, PaymentMethod method, String idempotencyKey,
                                   String requestFingerprint, LocalDateTime now) {
        DomainPreconditions.requireNonNull(booking, "booking");
        DomainPreconditions.requireNonNull(method, "paymentMethod");
        String key = DomainPreconditions.requireText(idempotencyKey, "idempotencyKey", IDEMPOTENCY_KEY_MAX);
        String fingerprint = DomainPreconditions.requireText(requestFingerprint, "requestFingerprint",
                FINGERPRINT_LENGTH);
        DomainPreconditions.requireNonNull(now, "now");
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new InvalidBookingStateException("Payment can only be made for a booking in PENDING_PAYMENT; "
                    + "booking " + booking.getId() + " is " + booking.getStatus());
        }
        return new Payment(booking, method, key, fingerprint, now);
    }

    /**
     * Whether a request with this idempotency key is a genuine retry of the request that created this payment.
     */
    public boolean isSameRequest(Long bookingId, String fingerprint) {
        return booking.getId().equals(bookingId) && requestFingerprint.equals(fingerprint);
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public void markSucceeded(String transactionReference, LocalDateTime now) {
        requirePending();
        this.transactionReference = DomainPreconditions.requireText(
                transactionReference, "transactionReference", TRANSACTION_REFERENCE_MAX);
        this.status = PaymentStatus.SUCCEEDED;
        this.completedAt = DomainPreconditions.requireNonNull(now, "completedAt");
    }

    public void markFailed(String transactionReference, String failureReason, LocalDateTime now) {
        requirePending();
        this.transactionReference = DomainPreconditions.optionalText(
                transactionReference, "transactionReference", TRANSACTION_REFERENCE_MAX);
        this.failureReason = DomainPreconditions.optionalText(failureReason, "failureReason", FAILURE_REASON_MAX);
        this.status = PaymentStatus.FAILED;
        this.completedAt = DomainPreconditions.requireNonNull(now, "completedAt");
    }

    /**
     * Records money returned to the customer. A payment is refunded at most once, fully or partially.
     */
    public void recordRefund(BigDecimal refundAmount) {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new InvalidPaymentStateException("Only a successful payment can be refunded; payment "
                    + getId() + " is " + status);
        }
        BigDecimal refund = MonetaryAmounts.requirePositive(refundAmount, "refund amount");
        if (refund.compareTo(amount) > 0) {
            throw new DomainValidationException("Refund amount " + refund + " exceeds paid amount " + amount);
        }
        this.refundedAmount = refund;
        this.status = refund.compareTo(amount) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.SUCCEEDED;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException("Payment " + getId() + " is already " + status);
        }
    }
}
