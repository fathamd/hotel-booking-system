package com.rupeek.hotel_booking_system.service.refund;

import com.rupeek.hotel_booking_system.api.dto.cancellation.RefundResponse;
import com.rupeek.hotel_booking_system.domain.enums.RefundStatus;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.payment.PaymentCharge;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundRequest;
import com.rupeek.hotel_booking_system.service.payment.gateway.GatewayRefundResponse;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pays out recorded refunds through the {@link RefundGateway}.
 * <p>
 * A refund is created {@code PENDING} by the caller's transaction (for example a cancellation) and paid out
 * afterwards, so the gateway is never called while a database transaction or row lock is held:
 * <ol>
 *     <li>transaction 1 locks the refund and reads what to send;</li>
 *     <li>the gateway is called with no transaction open;</li>
 *     <li>transaction 2 locks the refund again and records the answer, unless another worker already did.</li>
 * </ol>
 * If the gateway gives no answer the refund stays {@code PENDING} and {@link #retryPendingRefunds} sends it again
 * with the same idempotency key, so it is paid out at most once. A rejected refund becomes {@code FAILED};
 * {@link #retry} starts a new attempt with a new key.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final RefundGateway refundGateway;
    private final TransactionOperations transactions;
    private final Clock clock;

    public RefundService(RefundRepository refundRepository,
                         RefundGateway refundGateway,
                         TransactionOperations transactions,
                         Clock clock) {
        this.refundRepository = refundRepository;
        this.refundGateway = refundGateway;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Sends a pending refund to the gateway and records the answer. A refund that is no longer pending is
     * returned unchanged.
     *
     * @throws com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException if the gateway gave
     *         no answer; the refund stays pending
     */
    public RefundResponse process(Long refundId) {
        GatewayRefundRequest request = transactions.execute(status -> {
            Refund refund = lock(refundId);
            return refund.isPending() ? toGatewayRequest(refund) : null;
        });
        if (request == null) {
            return getRefund(refundId);
        }
        GatewayRefundResponse response = refundGateway.refund(request);
        return transactions.execute(status -> {
            Refund refund = lock(refundId);
            if (refund.isPending() && request.idempotencyKey().equals(refund.gatewayIdempotencyKey())) {
                LocalDateTime now = LocalDateTime.now(clock);
                if (response.approved()) {
                    refund.markSucceeded(response.refundReference(), now);
                    refund.getPayment().recordRefund(refund.getAmount());
                } else {
                    refund.markFailed(response.rejectionReason(), now);
                }
            }
            return RefundResponseMapper.toResponse(refund);
        });
    }

    /**
     * Starts a new attempt for a refund the gateway rejected and sends it.
     *
     * @throws com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentStateException if the refund has
     *         not failed
     */
    public RefundResponse retry(Long refundId) {
        transactions.executeWithoutResult(status -> lock(refundId).retry(LocalDateTime.now(clock)));
        return process(refundId);
    }

    /**
     * Re-sends refunds that have been pending for longer than {@code grace}.
     *
     * @return the number of refunds that received an answer
     */
    public int retryPendingRefunds(Duration grace, int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(grace);
        List<Long> pending = refundRepository.findIdsByStatusRequestedBefore(RefundStatus.PENDING, cutoff,
                PageRequest.of(0, batchSize));
        int answered = 0;
        for (Long refundId : pending) {
            try {
                process(refundId);
                answered++;
            } catch (RuntimeException ex) {
                log.warn("Could not process refund {}; will retry on the next run", refundId, ex);
            }
        }
        return answered;
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefund(Long refundId) {
        return refundRepository.findById(refundId)
                .map(RefundResponseMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
    }

    private Refund lock(Long refundId) {
        return refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
    }

    private static GatewayRefundRequest toGatewayRequest(Refund refund) {
        return new GatewayRefundRequest(refund.gatewayIdempotencyKey(),
                refund.getPayment().getTransactionReference(), refund.getAmount(), PaymentCharge.DEFAULT_CURRENCY);
    }
}
