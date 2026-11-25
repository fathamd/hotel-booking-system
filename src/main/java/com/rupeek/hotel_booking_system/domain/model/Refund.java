package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money returned against a successful payment after cancellation.
 * Lifecycle: PENDING -> SUCCEEDED | FAILED; FAILED -> PENDING on retry.
 * <p>
 * Each attempt has its own gateway idempotency key: repeating an attempt that got no answer reuses its key, so the
 * gateway never pays out twice, while a retry after a definite rejection uses a new key so the gateway really
 * tries again. The booking is reachable through the payment, so it is not duplicated here.
 */
@Entity
@Table(name = "refund",
        uniqueConstraints = @UniqueConstraint(name = "uk_refund_payment", columnNames = "payment_id"),
        indexes = @Index(name = "idx_refund_status_requested", columnList = "status, requested_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    private static final String KEY_PREFIX = "refund-payment-";
    private static final int REFERENCE_MAX = 100;
    private static final int FAILURE_REASON_MAX = 500;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refund_payment"))
    private Payment payment;

    @NotNull
    @DecimalMin(value = "0.01")
    @Column(nullable = false, precision = 14, scale = MonetaryAmounts.SCALE)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Size(max = REFERENCE_MAX)
    @Column(name = "refund_reference", length = REFERENCE_MAX)
    private String refundReference;

    @Size(max = FAILURE_REASON_MAX)
    @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
    private String failureReason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Min(1)
    @Column(nullable = false)
    private int attempts;

    /**
     * When the current attempt was started.
     */
    @NotNull
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    private Refund(Payment payment, BigDecimal amount, LocalDateTime requestedAt) {
        this.payment = payment;
        this.amount = amount;
        this.status = RefundStatus.PENDING;
        this.attempts = 1;
        this.requestedAt = requestedAt;
    }

    public static Refund initiate(Payment payment, BigDecimal amount, LocalDateTime now) {
        DomainPreconditions.requireNonNull(payment, "payment");
        DomainPreconditions.requireNonNull(now, "now");
        if (!payment.isSuccessful()) {
            throw new InvalidPaymentStateException("Only a successful payment can be refunded; payment "
                    + payment.getId() + " is " + payment.getStatus());
        }
        BigDecimal refundAmount = MonetaryAmounts.requirePositive(amount, "refund amount");
        if (refundAmount.compareTo(payment.getAmount()) > 0) {
            throw new DomainValidationException("Refund amount " + refundAmount
                    + " exceeds paid amount " + payment.getAmount());
        }
        return new Refund(payment, refundAmount, now);
    }

    /**
     * Starts a new attempt after the gateway rejected the previous one.
     */
    public void retry(LocalDateTime now) {
        if (status != RefundStatus.FAILED) {
            throw new InvalidPaymentStateException("Only a failed refund can be retried; refund " + getId()
                    + " is " + status);
        }
        this.status = RefundStatus.PENDING;
        this.attempts++;
        this.failureReason = null;
        this.processedAt = null;
        this.requestedAt = DomainPreconditions.requireNonNull(now, "now");
    }

    public boolean isPending() {
        return status == RefundStatus.PENDING;
    }

    /**
     * Idempotency key of the current attempt.
     */
    public String gatewayIdempotencyKey() {
        String key = KEY_PREFIX + payment.getId();
        return attempts == 1 ? key : key + "-attempt-" + attempts;
    }

    public void markSucceeded(String refundReference, LocalDateTime now) {
        requirePending();
        this.refundReference = DomainPreconditions.requireText(refundReference, "refundReference", REFERENCE_MAX);
        this.status = RefundStatus.SUCCEEDED;
        this.processedAt = DomainPreconditions.requireNonNull(now, "processedAt");
    }

    public void markFailed(String failureReason, LocalDateTime now) {
        requirePending();
        this.failureReason = DomainPreconditions.optionalText(failureReason, "failureReason", FAILURE_REASON_MAX);
        this.status = RefundStatus.FAILED;
        this.processedAt = DomainPreconditions.requireNonNull(now, "processedAt");
    }

    private void requirePending() {
        if (status != RefundStatus.PENDING) {
            throw new InvalidPaymentStateException("Refund " + getId() + " is already " + status);
        }
    }
}
