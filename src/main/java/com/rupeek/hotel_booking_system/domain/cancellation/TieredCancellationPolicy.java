package com.rupeek.hotel_booking_system.domain.cancellation;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * A policy defined by notice-period tiers. The most generous tier whose notice requirement is met applies;
 * if no tier applies there is no refund. Flexible and moderate policies are configurations of this class
 * (composition) rather than subclasses.
 */
public final class TieredCancellationPolicy implements CancellationPolicy {

    private final CancellationPolicyType type;
    private final List<RefundTier> tiersByNoticeDescending;

    public TieredCancellationPolicy(CancellationPolicyType type, List<RefundTier> tiers) {
        this.type = DomainPreconditions.requireNonNull(type, "type");
        DomainPreconditions.requireNonNull(tiers, "tiers");
        if (tiers.isEmpty()) {
            throw new DomainValidationException("A tiered cancellation policy needs at least one tier");
        }
        this.tiersByNoticeDescending = tiers.stream()
                .sorted(Comparator.comparing(RefundTier::minimumNotice).reversed())
                .toList();
    }

    /**
     * Full refund up to 24 hours before check-in.
     */
    public static TieredCancellationPolicy flexible() {
        return new TieredCancellationPolicy(CancellationPolicyType.FLEXIBLE, List.of(
                new RefundTier(Duration.ofHours(24), BigDecimal.valueOf(100))));
    }

    /**
     * Full refund up to 7 days before check-in, 50% up to 48 hours before.
     */
    public static TieredCancellationPolicy moderate() {
        return new TieredCancellationPolicy(CancellationPolicyType.MODERATE, List.of(
                new RefundTier(Duration.ofDays(7), BigDecimal.valueOf(100)),
                new RefundTier(Duration.ofHours(48), BigDecimal.valueOf(50))));
    }

    @Override
    public CancellationPolicyType type() {
        return type;
    }

    @Override
    public RefundDecision evaluate(CancellationContext context) {
        DomainPreconditions.requireNonNull(context, "context");
        Duration notice = context.noticeBeforeCheckIn();
        return tiersByNoticeDescending.stream()
                .filter(tier -> tier.appliesTo(notice))
                .findFirst()
                .map(tier -> new RefundDecision(
                        tier.refundPercentage(),
                        MonetaryAmounts.percentageOf(context.paidAmount(), tier.refundPercentage()),
                        type + ": cancelled at least " + describe(tier.minimumNotice()) + " before check-in"))
                .orElseGet(() -> RefundDecision.noRefund(
                        type + ": cancelled too close to check-in for a refund"));
    }

    private static String describe(Duration duration) {
        return duration.toHours() % 24 == 0 ? duration.toDays() + " day(s)" : duration.toHours() + " hour(s)";
    }
}
