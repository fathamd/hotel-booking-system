package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.enums.BookingStatus;
import com.rupeek.hotel_booking_system.domain.exception.BookingHoldExpiredException;
import com.rupeek.hotel_booking_system.domain.exception.InvalidBookingStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

/**
 * A reservation of {@code roomCount} rooms of one room type for a stay period.
 * <p>
 * Every booking starts in {@link BookingStatus#PENDING_PAYMENT}, which holds inventory until {@code holdExpiresAt}.
 * State changes go only through the lifecycle methods below, which enforce {@link BookingStatus#allowedTransitions()}.
 * Releasing and reserving inventory is the service layer's job; {@link #holdsInventory()} tells it whether
 * this booking currently occupies rooms.
 */
@Entity
@Table(name = "booking", indexes = {
        @Index(name = "idx_booking_room_type_status_stay",
                columnList = "room_type_id, status, check_in_date, check_out_date"),
        @Index(name = "idx_booking_property", columnList = "property_id"),
        @Index(name = "idx_booking_status_hold", columnList = "status, hold_expires_at"),
        @Index(name = "idx_booking_guest_email", columnList = "guest_email")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false, foreignKey = @ForeignKey(name = "fk_booking_property"))
    private Property property;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false, foreignKey = @ForeignKey(name = "fk_booking_room_type"))
    private RoomType roomType;

    @Valid
    @NotNull
    @Embedded
    private GuestDetails guest;

    @Valid
    @NotNull
    @Embedded
    private StayPeriod stayPeriod;

    @Min(1)
    @Column(name = "guest_count", nullable = false)
    private int guestCount;

    @Min(1)
    @Column(name = "room_count", nullable = false)
    private int roomCount;

    @NotNull
    @DecimalMin(value = "0.01")
    @Column(name = "total_price", nullable = false, precision = 14, scale = MonetaryAmounts.SCALE)
    private BigDecimal totalPrice;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingStatus status;

    @NotNull
    @Column(name = "hold_expires_at", nullable = false)
    private LocalDateTime holdExpiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    private Booking(RoomType roomType,
                    GuestDetails guest,
                    StayPeriod stayPeriod,
                    int guestCount,
                    int roomCount,
                    BigDecimal totalPrice,
                    LocalDateTime holdExpiresAt) {
        this.roomType = roomType;
        this.property = roomType.getProperty();
        this.guest = guest;
        this.stayPeriod = stayPeriod;
        this.guestCount = guestCount;
        this.roomCount = roomCount;
        this.totalPrice = totalPrice;
        this.holdExpiresAt = holdExpiresAt;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    /**
     * Creates a booking awaiting payment. The property is taken from the room type so the two can never disagree.
     */
    public static Booking createPending(RoomType roomType,
                                        GuestDetails guest,
                                        StayPeriod stayPeriod,
                                        int guestCount,
                                        int roomCount,
                                        BigDecimal totalPrice,
                                        LocalDateTime holdExpiresAt) {
        DomainPreconditions.requireNonNull(roomType, "roomType");
        DomainPreconditions.requireNonNull(guest, "guest");
        DomainPreconditions.requireNonNull(stayPeriod, "stayPeriod");
        DomainPreconditions.requireNonNull(holdExpiresAt, "holdExpiresAt");
        DomainPreconditions.requirePositive(guestCount, "guestCount");
        DomainPreconditions.requirePositive(roomCount, "roomCount");
        roomType.requireCanAccommodate(guestCount, roomCount);
        BigDecimal price = MonetaryAmounts.requirePositive(totalPrice, "totalPrice");
        return new Booking(roomType, guest, stayPeriod, guestCount, roomCount, price, holdExpiresAt);
    }

    /**
     * Confirms the booking with the payment initiated at {@code now}.
     */
    public void confirm(LocalDateTime now) {
        confirm(now, now);
    }

    /**
     * Confirms the booking after a successful payment. What matters is that the payment was started while the
     * hold was still valid; the gateway may answer a little after the hold expires, and the rooms are still held
     * because expiry never runs while a payment is in flight.
     */
    public void confirm(LocalDateTime paymentInitiatedAt, LocalDateTime now) {
        requireTransition(BookingStatus.CONFIRMED);
        DomainPreconditions.requireNonNull(paymentInitiatedAt, "paymentInitiatedAt");
        if (!paymentInitiatedAt.isBefore(holdExpiresAt)) {
            throw new BookingHoldExpiredException(getId());
        }
        status = BookingStatus.CONFIRMED;
        confirmedAt = DomainPreconditions.requireNonNull(now, "now");
    }

    public void markPaymentFailed() {
        requireTransition(BookingStatus.PAYMENT_FAILED);
        status = BookingStatus.PAYMENT_FAILED;
    }

    public void expire(LocalDateTime now) {
        requireTransition(BookingStatus.EXPIRED);
        if (!isHoldExpired(now)) {
            throw new InvalidBookingStateException("Booking " + getId() + " hold has not expired yet");
        }
        status = BookingStatus.EXPIRED;
    }

    /**
     * Cancels a pending or confirmed booking. A confirmed booking can only be cancelled before check-in time,
     * which is evaluated in the property's time zone.
     */
    public void cancel(ZonedDateTime now) {
        DomainPreconditions.requireNonNull(now, "now");
        requireTransition(BookingStatus.CANCELLED);
        if (status == BookingStatus.CONFIRMED && !now.toInstant().isBefore(checkInInstant())) {
            throw new InvalidBookingStateException("Booking " + getId() + " cannot be cancelled at or after check-in");
        }
        status = BookingStatus.CANCELLED;
        cancelledAt = now.toLocalDateTime();
    }

    public Instant checkInInstant() {
        return stayPeriod.checkInInstant(property.getTimeZone());
    }

    public boolean isHoldExpired(LocalDateTime now) {
        return status == BookingStatus.PENDING_PAYMENT && !now.isBefore(holdExpiresAt);
    }

    public boolean holdsInventory() {
        return status.holdsInventory();
    }

    private void requireTransition(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidBookingStateException(status, target);
        }
    }
}
