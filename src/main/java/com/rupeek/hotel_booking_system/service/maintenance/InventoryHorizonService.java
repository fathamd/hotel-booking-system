package com.rupeek.hotel_booking_system.service.maintenance;

import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.onboarding.RoomInventoryProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

/**
 * Rolls the bookable horizon forward: tops up every room type's per-night inventory so it always reaches the
 * configured number of days ahead. Each room type is handled in its own transaction, so one failure does not
 * stop the others, and a concurrent run that already added the nights is caught by the unique
 * (room type, night) constraint.
 */
@Service
public class InventoryHorizonService {

    private static final Logger log = LoggerFactory.getLogger(InventoryHorizonService.class);

    private final RoomTypeRepository roomTypeRepository;
    private final RoomInventoryProvisioner provisioner;
    private final TransactionOperations transactions;

    public InventoryHorizonService(RoomTypeRepository roomTypeRepository,
                                   RoomInventoryProvisioner provisioner,
                                   TransactionOperations transactions) {
        this.roomTypeRepository = roomTypeRepository;
        this.provisioner = provisioner;
        this.transactions = transactions;
    }

    /**
     * @return the number of nights added across all room types
     */
    public int extendAll() {
        List<Long> roomTypeIds = roomTypeRepository.findAllIds();
        int added = 0;
        for (Long roomTypeId : roomTypeIds) {
            try {
                Integer nights = transactions.execute(status -> roomTypeRepository.findById(roomTypeId)
                        .map(provisioner::extendHorizon)
                        .orElse(0));
                added += nights == null ? 0 : nights;
            } catch (RuntimeException ex) {
                log.warn("Could not extend the inventory horizon of room type {}", roomTypeId, ex);
            }
        }
        return added;
    }
}
