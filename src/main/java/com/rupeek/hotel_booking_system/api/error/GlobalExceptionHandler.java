package com.rupeek.hotel_booking_system.api.error;

import com.rupeek.hotel_booking_system.domain.exception.DomainException;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.service.exception.DuplicateResourceException;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGatewayException;
import com.rupeek.hotel_booking_system.service.payment.gateway.RefundGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Stream;

/**
 * Maps exceptions to RFC 9457 problem details so that every API error has the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        problem.setProperty("errors", violations);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleInvalidParameters(HandlerMethodValidationException ex) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(GlobalExceptionHandler::toViolations)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        problem.setProperty("errors", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "Request body is missing, malformed or contains an unsupported value");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "Parameter '" + ex.getName() + "' has an invalid value");
    }

    @ExceptionHandler(DomainValidationException.class)
    public ProblemDetail handleDomainValidation(DomainValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate resource", ex.getMessage());
    }

    /**
     * Business rule violations that depend on current state, e.g. an illegal booking transition.
     */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleBusinessRuleViolation(DomainException ex) {
        return problem(HttpStatus.CONFLICT, "Business rule violated", ex.getMessage());
    }

    /**
     * Last line of defence against races that slip past the service checks, such as two concurrent
     * registrations with the same email. Constraint details are not leaked to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with existing data");
    }

    /**
     * Raised when an inventory row lock cannot be obtained in time because of heavy contention.
     * Nothing was changed, so the client may simply retry.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Pessimistic lock could not be acquired", ex);
        return problem(HttpStatus.CONFLICT, "Concurrent update",
                "The requested inventory is being updated by another request; please retry");
    }

    /**
     * The gateway gave no decision. The payment stays pending with its rooms held, and a retry with the same
     * idempotency key is safe because the gateway replays its original outcome. A refund stays pending and is
     * re-sent by the refund retry job.
     */
    @ExceptionHandler(PaymentGatewayException.class)
    public ProblemDetail handleGatewayFailure(PaymentGatewayException ex) {
        log.error("Payment gateway failure", ex);
        if (ex instanceof RefundGatewayException) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable",
                    "The refund could not be sent and remains pending; it will be retried automatically");
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable",
                "The payment is pending; please retry with the same Idempotency-Key");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    private static FieldViolation toViolation(FieldError error) {
        String message = error.isBindingFailure() ? "has an invalid value" : error.getDefaultMessage();
        return new FieldViolation(error.getField(), message);
    }

    /**
     * Request bodies report their individual field errors; simple parameters such as path variables
     * are reported under the parameter name.
     */
    private static Stream<FieldViolation> toViolations(ParameterValidationResult result) {
        if (result instanceof ParameterErrors bodyErrors) {
            return bodyErrors.getFieldErrors().stream().map(GlobalExceptionHandler::toViolation);
        }
        String parameterName = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream()
                .map(error -> new FieldViolation(parameterName, error.getDefaultMessage()));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
