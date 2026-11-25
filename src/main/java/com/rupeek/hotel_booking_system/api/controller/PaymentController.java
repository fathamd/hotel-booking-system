package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.PaymentResponse;
import com.rupeek.hotel_booking_system.domain.model.Payment;
import com.rupeek.hotel_booking_system.service.payment.PaymentOutcome;
import com.rupeek.hotel_booking_system.service.payment.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class PaymentController {

    static final String BASE_PATH = "/api/v1/payments";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 201 for a new payment attempt, whether approved or declined (see {@code status}); 200 with
     * {@value #REPLAYED_HEADER}: true when the idempotency key was already used.
     */
    @PostMapping(BookingController.BASE_PATH + "/{bookingId}/payments")
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable @Positive Long bookingId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = Payment.IDEMPOTENCY_KEY_MAX)
            @Pattern(regexp = "[A-Za-z0-9_.:-]+", message = "may contain only letters, digits and _ . : -")
            String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        PaymentOutcome outcome = paymentService.pay(bookingId, idempotencyKey, request);
        PaymentResponse payment = outcome.payment();
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(payment);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + payment.id()))
                .header(REPLAYED_HEADER, "false")
                .body(payment);
    }

    @GetMapping(BookingController.BASE_PATH + "/{bookingId}/payments")
    public List<PaymentResponse> getBookingPayments(@PathVariable @Positive Long bookingId) {
        return paymentService.getBookingPayments(bookingId);
    }

    @GetMapping(BASE_PATH + "/{paymentId}")
    public PaymentResponse getPayment(@PathVariable @Positive Long paymentId) {
        return paymentService.getPayment(paymentId);
    }

    @GetMapping(BASE_PATH)
    public PaymentResponse getPaymentByTransactionReference(
            @RequestParam @NotBlank @Size(max = 100) String transactionReference) {
        return paymentService.getPaymentByTransactionReference(transactionReference);
    }
}
