package com.rupeek.hotel_booking_system.service.cancellation;

import com.rupeek.hotel_booking_system.api.dto.cancellation.CancellationResponse;
import com.rupeek.hotel_booking_system.api.dto.cancellation.RefundResponse;
import com.rupeek.hotel_booking_system.domain.cancellation.CancellationContext;
import com.rupeek.hotel_booking_system.domain.cancellation.CancellationPolicy;
import com.rupeek.hotel_booking_system.domain.cancellation.RefundDecision;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.enums.PaymentStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingAlreadyCancelledException;
import com.rupeek.hotel_booking_system.domain.exception.PaymentInProgressException;
import com.rupeek.hotel_booking_system.domain.model.Booking;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.domain.model.Refund;
import com.rupeek.hotel_booking_system.repository.BookingRepository;
import com.rupeek.hotel_booking_system.repository.PaymentRepository;
import com.rupeek.hotel_booking_system.repository.RefundRepository;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.inventory.InventoryReservationService;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException;
import com.rupeek.hotel_booking_system.service.refund.RefundResponseMapper;
import com.rupeek.hotel_booking_system.service.refund.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Cancels bookings and refunds them according to the applicable {@link CancellationPolicy}.
 * <p>
 * <b>Transaction boundary.</b> The cancellation itself is one read-write transaction: it locks the booking row,
 * moves the booking to CANCELLED, records the refund as {@code PENDING} and returns the rooms to inventory.
 * Either all of it commits or none of it does. The refund is paid out by {@link RefundService} after that
 * commit, outside any transaction. If the refund gateway does not answer, the cancellation still stands and the
 * refund stays pending; the refund retry job pays it out later with the same idempotency key.
 * <p>
 * <b>Locking.</b> The booking row lock serialises cancellation with payment, hold expiry and a second
 * cancellation of the same booking. Locks are taken booking row first, then inventory rows, the same order as
 * payments, so the flows cannot deadlock. A booking whose payment is still awaiting the gateway cannot be
 * cancelled until that payment is settled.
 * <p>
 * Which refund applies is decided entirely by the {@link CancellationPolicyResolver} and the policy it
 * returns, so policies can be replaced without touching this class or the booking service.
 */
@Service
public class BookingCancellationService {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationService.class);
    static final String NO_PAYMENT_RULE = "No payment was captured for this booking";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final InventoryReservationService inventoryReservationService;
    private final CancellationPolicyResolver policyResolver;
    private final RefundService refundService;
    private final TransactionOperations transactions;
    private final Clock clock;

    public BookingCancellationService(BookingRepository bookingRepository,
                                      PaymentRepository paymentRepository,
                                      RefundRepository refundRepository,
                                      InventoryReservationService inventoryReservationService,
                                      CancellationPolicyResolver policyResolver,
                                      RefundService refundService,
                                      TransactionOperations transactions,
                                      Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.policyResolver = policyResolver;
        this.refundService = refundService;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @throws ResourceNotFoundException        if the booking does not exist
     * @throws BookingAlreadyCancelledException if the booking was already cancelled
     * @throws PaymentInProgressException       if a payment for the booking is still awaiting the gateway
     * @throws com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException
     *                                          if the booking is payment-failed or expired, or check-in has passed
     */
    public CancellationResponse cancel(Long bookingId) {
        Cancellation cancellation = transactions.execute(status -> cancelAndRecordRefund(bookingId));
        RefundResponse refund = cancellation.refund();
        if (refund != null) {
            refund = payOut(refund);
        }
        return cancellation.response().withRefund(refund);
    }

    private Cancellation cancelAndRecordRefund(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BookingAlreadyCancelledException(bookingId);
        }
        if (paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.PENDING)) {
            throw new PaymentInProgressException(bookingId);
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        boolean heldInventory = booking.holdsInventory();
        booking.cancel(now);

        CancellationPolicy policy = policyResolver.resolve(booking);
        Optional<Payment> capturedPayment = paymentRepository.findFirstByBookingIdAndStatus(bookingId,
                PaymentStatus.SUCCEEDED);
        RefundDecision decision = capturedPayment
                .map(payment -> policy.evaluate(new CancellationContext(payment.getAmount(),
                        booking.checkInInstant(), now.toInstant())))
                .orElseGet(() -> RefundDecision.noRefund(NO_PAYMENT_RULE));
        Refund refund = decision.isRefundable()
                ? refundRepository.saveAndFlush(Refund.initiate(capturedPayment.orElseThrow(),
                decision.refundAmount(), now.toLocalDateTime()))
                : null;

        if (heldInventory) {
            inventoryReservationService.release(booking);
        }
        BigDecimal paidAmount = capturedPayment.map(Payment::getAmount).orElse(MonetaryAmounts.ZERO);
        CancellationResponse response = new CancellationResponse(booking.getId(), booking.getStatus(),
                booking.getCancelledAt(), policy.type(), paidAmount, decision.refundPercentage(),
                decision.refundAmount(), decision.rule(), null);
        return new Cancellation(response, RefundResponseMapper.toResponse(refund));
    }

    private RefundResponse payOut(RefundResponse pending) {
        try {
            return refundService.process(pending.id());
        } catch (RefundGatewayException ex) {
            log.warn("Refund {} could not be sent to the gateway; it stays pending and will be retried",
                    pending.id(), ex);
            return pending;
        }
    }

    private record Cancellation(CancellationResponse response, RefundResponse refund) {
    }
}
