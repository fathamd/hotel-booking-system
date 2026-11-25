package com.rupeek.hotel_booking_system.service.pricing;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.common.MonetaryAmounts;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.domain.model.StayPeriod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flat pricing: {@code basePrice x nights x rooms}.
 */
@Component
public class BaseRatePriceCalculator implements PriceCalculator {

    @Override
    public BigDecimal totalPrice(RoomType roomType, StayPeriod stay, int rooms) {
        DomainPreconditions.requireNonNull(roomType, "roomType");
        DomainPreconditions.requireNonNull(stay, "stay");
        DomainPreconditions.requirePositive(rooms, "rooms");
        return MonetaryAmounts.normalize(roomType.getBasePrice()
                .multiply(BigDecimal.valueOf(stay.numberOfNights()))
                .multiply(BigDecimal.valueOf(rooms)));
    }
}
