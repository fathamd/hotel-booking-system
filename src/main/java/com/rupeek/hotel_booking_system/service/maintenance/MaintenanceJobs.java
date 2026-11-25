package com.rupeek.hotel_booking_system.service.maintenance;

import com.rupeek.hotel_booking_system.config.MaintenanceProperties;
import com.rupeek.hotel_booking_system.service.payment.PaymentService;
import com.rupeek.hotel_booking_system.service.refund.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the maintenance work on a schedule. Scheduling is enabled by
 * {@link com.rupeek.hotel_booking_system.config.SchedulingConfiguration}; the logic lives in the services so
 * it can be tested and invoked directly.
 * <p>
 * Every job is safe to run concurrently on several instances: each record is processed under its own row lock
 * and re-checked before it is changed.
 */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final BookingHoldExpiryService holdExpiryService;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final InventoryHorizonService inventoryHorizonService;
    private final MaintenanceProperties properties;

    public MaintenanceJobs(BookingHoldExpiryService holdExpiryService,
                           PaymentService paymentService,
                           RefundService refundService,
                           InventoryHorizonService inventoryHorizonService,
                           MaintenanceProperties properties) {
        this.holdExpiryService = holdExpiryService;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.inventoryHorizonService = inventoryHorizonService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hotel.maintenance.hold-expiry-interval:PT1M}")
    public void expireOverdueHolds() {
        logIfAny("Expired {} overdue booking holds", holdExpiryService.expireOverdueHolds(properties.batchSize()));
    }

    @Scheduled(fixedDelayString = "${hotel.maintenance.payment-reconciliation-interval:PT1M}")
    public void reconcileStalePayments() {
        logIfAny("Reconciled {} stale payments", paymentService.reconcileStalePayments(
                properties.paymentReconciliationGrace(), properties.batchSize()));
    }

    @Scheduled(fixedDelayString = "${hotel.maintenance.refund-retry-interval:PT5M}")
    public void retryPendingRefunds() {
        logIfAny("Processed {} pending refunds", refundService.retryPendingRefunds(
                properties.refundRetryGrace(), properties.batchSize()));
    }

    @Scheduled(cron = "${hotel.maintenance.inventory-horizon-cron:0 5 0 * * *}")
    public void extendInventoryHorizon() {
        logIfAny("Added {} inventory nights", inventoryHorizonService.extendAll());
    }

    private static void logIfAny(String message, int count) {
        if (count > 0) {
            log.info(message, count);
        }
    }
}
