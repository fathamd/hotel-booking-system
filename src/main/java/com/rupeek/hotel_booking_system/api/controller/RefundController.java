package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.cancellation.RefundResponse;
import com.rupeek.hotel_booking_system.service.refund.RefundService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RefundController.BASE_PATH)
public class RefundController {

    static final String BASE_PATH = "/api/v1/refunds";

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @GetMapping("/{refundId}")
    public RefundResponse getRefund(@PathVariable @Positive Long refundId) {
        return refundService.getRefund(refundId);
    }

    /**
     * Starts a new attempt for a refund the gateway rejected.
     */
    @PostMapping("/{refundId}/retry")
    public RefundResponse retryRefund(@PathVariable @Positive Long refundId) {
        return refundService.retry(refundId);
    }
}
