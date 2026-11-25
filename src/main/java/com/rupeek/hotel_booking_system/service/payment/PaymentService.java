package com.rupeek.hotel_booking_system.service.payment;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentResponse;
import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingHoldExpiredException;
import com.rupeek.hotel_booking_system.domain.exception.IdempotencyKeyReuseException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import com.rupeek.hotel_booking_system.domain.exception.PaymentInProgressException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.PendingPaymentRef;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Pays for bookings through the {@link PaymentProcessor} registered for the requested method.
 * <p>
 * <b>Flow and transactions.</b> {@link #pay} is not one transaction; the gateway is never called while a
 * database transaction or row lock is held:
 * <ol>
 *     <li><b>Validate</b> the instrument ({@link PaymentProcessor#prepare}); nothing touches the database.</li>
 *     <li><b>Begin</b> (transaction 1): lock the booking row, apply the idempotency rules below, check the
 *     booking is payable and insert a {@code PENDING} payment. Commit.</li>
 *     <li><b>Charge</b> through the gateway, with no transaction open.</li>
 *     <li><b>Complete</b> (transaction 2): lock the booking row again and record the decision. Success confirms
 *     the booking; a decline marks it {@code PAYMENT_FAILED} and releases its inventory.</li>
 * </ol>
 * If the gateway gives no decision the payment stays {@code PENDING} and the booking keeps its rooms. The client
 * may retry with the same key, which resumes the charge; the gateway is idempotent on the key so the customer is
 * charged at most once. If nobody retries, {@link #reconcileStalePayments} asks the gateway for the decision.
 * <p>
 * <b>Idempotency.</b> A key already recorded is accepted only for the same booking and the same request
 * fingerprint (method and instrument); anything else is {@link IdempotencyKeyReuseException}. A completed payment
 * is replayed without charging; a pending one is resumed. Only one payment may be pending per booking, so two
 * different keys cannot both charge it.
 * <p>
 * <b>Locking.</b> Lock order is booking row, then inventory rows, as in cancellation and hold expiry. Booking
 * creation locks only inventory rows, so the flows cannot deadlock.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String NO_GATEWAY_DECISION = "No decision received from the payment gateway";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProcessorRegistry processorRegistry;
    private final PaymentGateway paymentGateway;
    private final InventoryReservationService inventoryReservationService;
    private final TransactionOperations transactions;
    private final Clock clock;

    public PaymentService(BookingRepository bookingRepository,
                          PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          PaymentProcessorRegistry processorRegistry,
                          PaymentGateway paymentGateway,
                          InventoryReservationService inventoryReservationService,
                          TransactionOperations transactions,
                          Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.processorRegistry = processorRegistry;
        this.paymentGateway = paymentGateway;
        this.inventoryReservationService = inventoryReservationService;
        this.transactions = transactions;
        this.clock = clock;
    }

    public PaymentOutcome pay(Long bookingId, String idempotencyKey, PaymentRequest request) {
        DomainPreconditions.requireNonNull(bookingId, "bookingId");
        DomainPreconditions.requireNonNull(request, "request");
        String key = DomainPreconditions.requireText(idempotencyKey, "idempotencyKey", Payment.IDEMPOTENCY_KEY_MAX);
        PaymentMethod method = DomainPreconditions.requireNonNull(request.method(), "method");
        PaymentProcessor processor = processorRegistry.processorFor(method);
        PaymentInstrument instrument = processor.prepare(request);
        String fingerprint = fingerprint(bookingId, instrument);

        Attempt attempt = begin(bookingId, key, fingerprint, method);
        return switch (attempt) {
            case Attempt.Completed completed -> new PaymentOutcome(completed.payment(), true);
            case Attempt.HoldExpired _ -> throw new BookingHoldExpiredException(bookingId);
            case Attempt.Charge charge -> {
                PaymentResult result = processor.charge(charge.charge(), instrument);
                PaymentResponse payment = complete(bookingId, charge.paymentId(), result);
                yield new PaymentOutcome(payment, charge.resumed());
            }
        };
    }

    /**
     * Settles payments that have been awaiting a gateway decision for longer than {@code grace}, for example
     * because the gateway timed out and the client never retried. The gateway is asked for the decision recorded
     * under the payment's key; if it never received the charge, the payment is marked failed.
     *
     * @param grace must exceed the gateway timeout, so requests still in flight are left alone
     * @return the number of payments settled
     */
    public int reconcileStalePayments(Duration grace, int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(grace);
        List<PendingPaymentRef> stale = paymentRepository.findRefsByStatusInitiatedBefore(PaymentStatus.PENDING,
                cutoff, PageRequest.of(0, batchSize));
        int settled = 0;
        for (PendingPaymentRef ref : stale) {
            try {
                PaymentResult result = paymentGateway.findCharge(ref.idempotencyKey())
                        .map(PaymentResult::from)
                        .orElseGet(() -> PaymentResult.failure(null, NO_GATEWAY_DECISION));
                complete(ref.bookingId(), ref.paymentId(), result);
                settled++;
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile payment {}; will retry on the next run", ref.paymentId(), ex);
            }
        }
        return settled;
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentResponseMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTransactionReference(String transactionReference) {
        return paymentRepository.findByTransactionReference(transactionReference)
                .map(PaymentResponseMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "transaction reference",
                        transactionReference));
    }

    /**
     * Payment attempts for a booking, newest first.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getBookingPayments(Long bookingId) {
        if (!bookingRepository.existsById(bookingId)) {
            throw new ResourceNotFoundException("Booking", bookingId);
        }
        return paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .map(PaymentResponseMapper::toResponse)
                .toList();
    }

    private Attempt begin(Long bookingId, String key, String fingerprint, PaymentMethod method) {
        try {
            return transactions.execute(status -> {
                Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                        .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
                Optional<Payment> previous = paymentRepository.findByIdempotencyKey(key);
                if (previous.isPresent()) {
                    return retryOf(previous.get(), bookingId, fingerprint);
                }
                requirePayable(booking);
                if (paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)) {
                    throw new PaymentInProgressException(bookingId);
                }
                LocalDateTime now = LocalDateTime.now(clock);
                if (booking.isHoldExpired(now)) {
                    booking.expire(now);
                    inventoryReservationService.release(booking);
                    return new Attempt.HoldExpired();
                }
                Payment payment = paymentRepository.saveAndFlush(
                        Payment.initiate(booking, method, key, fingerprint, now));
                return new Attempt.Charge(payment.getId(), chargeFor(payment), false);
            });
        } catch (DataIntegrityViolationException ex) {
            // The same key was inserted concurrently for another booking.
            throw new IdempotencyKeyReuseException(key);
        }
    }

    private PaymentResponse complete(Long bookingId, Long paymentId, PaymentResult result) {
        return transactions.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
            if (!payment.isPending()) {
                return PaymentResponseMapper.toResponse(payment);
            }
            LocalDateTime now = LocalDateTime.now(clock);
            boolean awaitingPayment = booking.getStatus() == BookingStatus.PENDING_PAYMENT;
            if (result.successful()) {
                payment.markSucceeded(result.transactionReference(), now);
                if (awaitingPayment) {
                    booking.confirm(payment.getInitiatedAt(), now);
                } else {
                    // Money arrived for a booking that can no longer be confirmed; give it back.
                    log.warn("Payment {} succeeded for booking {} in state {}; refunding", paymentId, bookingId,
                            booking.getStatus());
                    refundRepository.save(Refund.initiate(payment, payment.getAmount(), now));
                }
            } else {
                payment.markFailed(result.transactionReference(), result.failureReason(), now);
                if (awaitingPayment) {
                    booking.markPaymentFailed();
                    inventoryReservationService.release(booking);
                }
            }
            return PaymentResponseMapper.toResponse(payment);
        });
    }

    private static Attempt retryOf(Payment previous, Long bookingId, String fingerprint) {
        if (!previous.isSameRequest(bookingId, fingerprint)) {
            throw new IdempotencyKeyReuseException(previous.getIdempotencyKey());
        }
        if (previous.isPending()) {
            return new Attempt.Charge(previous.getId(), chargeFor(previous), true);
        }
        return new Attempt.Completed(PaymentResponseMapper.toResponse(previous));
    }

    private static PaymentCharge chargeFor(Payment payment) {
        return new PaymentCharge(payment.getIdempotencyKey(), payment.getBooking().getId(), payment.getAmount());
    }

    private static void requirePayable(Booking booking) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new InvalidBookingStateException("Booking " + booking.getId() + " is " + booking.getStatus()
                    + "; only bookings in PENDING_PAYMENT can be paid");
        }
    }

    /**
     * Identifies "the same request" for idempotency. The instrument is hashed so it is never stored in clear.
     */
    static String fingerprint(Long bookingId, PaymentInstrument instrument) {
        String material = bookingId + "|" + instrument.method() + "|" + instrument.identifier();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private sealed interface Attempt {

        record Charge(Long paymentId, PaymentCharge charge, boolean resumed) implements Attempt {
        }

        record Completed(PaymentResponse payment) implements Attempt {
        }

        record HoldExpired() implements Attempt {
        }
    }
}
