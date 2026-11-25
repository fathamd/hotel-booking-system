package com.rupeek.hotel_booking_system.service.payment.gateway;

import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a real gateway, active when {@code hotel.payment.gateway=mock} (the default).
 * <p>
 * Deterministic outcomes so the flows can be exercised manually and in tests:
 * <ul>
 *     <li>instruments ending in {@code 0002} are declined (card {@code 4000000000000002}, wallet {@code 9000000002})</li>
 *     <li>instruments starting with {@code fail@} are declined (UPI {@code fail@okbank})</li>
 *     <li>everything else is approved</li>
 * </ul>
 * Like a real gateway it is idempotent: the first response for an idempotency key is stored and replayed,
 * so a key is charged at most once. Refunds are always approved and are idempotent in the same way.
 */
@Component
@ConditionalOnProperty(name = "hotel.payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway, RefundGateway {

    static final String DECLINE_SUFFIX = "0002";
    static final String DECLINE_PREFIX = "fail@";

    private final Map<String, GatewayChargeResponse> responsesByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, GatewayRefundResponse> refundsByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger charges = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();

    @Override
    public GatewayChargeResponse charge(GatewayChargeRequest request) {
        if (request == null || request.idempotencyKey() == null || request.instrument() == null) {
            throw new PaymentGatewayException("Malformed gateway request");
        }
        return responsesByIdempotencyKey.computeIfAbsent(request.idempotencyKey(), key -> process(request));
    }

    @Override
    public Optional<GatewayChargeResponse> findCharge(String idempotencyKey) {
        return idempotencyKey == null ? Optional.empty()
                : Optional.ofNullable(responsesByIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public GatewayRefundResponse refund(GatewayRefundRequest request) {
        if (request == null || request.idempotencyKey() == null || request.originalTransactionReference() == null
                || request.amount() == null || request.amount().signum() <= 0) {
            throw new RefundGatewayException("Malformed refund request");
        }
        return refundsByIdempotencyKey.computeIfAbsent(request.idempotencyKey(), key -> {
            refunds.incrementAndGet();
            return GatewayRefundResponse.approved("MOCK-REFUND-" + UUID.randomUUID());
        });
    }

    /**
     * Number of distinct charges actually performed, i.e. excluding idempotent replays.
     */
    public int chargeCount() {
        return charges.get();
    }

    /**
     * Number of distinct refunds actually performed, i.e. excluding idempotent replays.
     */
    public int refundCount() {
        return refunds.get();
    }

    private GatewayChargeResponse process(GatewayChargeRequest request) {
        charges.incrementAndGet();
        String reference = newTransactionReference(request.method());
        String instrument = request.instrument().toLowerCase(Locale.ROOT);
        if (instrument.endsWith(DECLINE_SUFFIX) || instrument.startsWith(DECLINE_PREFIX)) {
            return GatewayChargeResponse.declined(reference, request.method() + " payment declined by issuer");
        }
        return GatewayChargeResponse.approved(reference);
    }

    private static String newTransactionReference(PaymentMethod method) {
        return "MOCK-" + method + "-" + UUID.randomUUID();
    }
}
