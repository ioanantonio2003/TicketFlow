package com.ticketflow.common.web;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ForbiddenOperationException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into RFC 9457 {@link ProblemDetail} responses.
 * Every response gets {@code requestId}; nothing internal (SQL, class names, stack traces) is exposed.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ---- domain exceptions ----

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return respond(ex, HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), null, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return respond(ex, HttpStatus.CONFLICT, "Conflict", ex.getMessage(), null, request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, WebRequest request) {
        return respond(ex, HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated", ex.getMessage(), null, request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<Object> handleForbidden(ForbiddenOperationException ex, WebRequest request) {
        return respond(ex, HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), null, request);
    }

    // ---- validation ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            errors.add(toValidationError(error, error.getObjectName()));
        }
        return respond(ex, HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed.", errors, request);
    }

    /** Thrown for constraints on controller method parameters (e.g. {@code @RequestParam @Min(1)}). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(toValidationError(error, parameter));
            }
        });
        return respond(ex, HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed.", errors, request);
    }

    /** Thrown when bean validation is triggered programmatically or via {@code @Validated} on a bean. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<ValidationError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ValidationError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return respond(ex, HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed.", errors, request);
    }

    // ---- persistence ----

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        // the real cause (constraint names, SQL) goes to the log only
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(ex, HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data.", null, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
        return respond(ex, HttpStatus.CONFLICT, "Conflict",
                "The resource was modified by someone else. Reload it and try again.", null, request);
    }

    /** Unknown sort property in a {@code Pageable}. */
    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<Object> handleUnknownProperty(PropertyReferenceException ex, WebRequest request) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Invalid request",
                "Unknown sort or filter property.", null, request);
    }

    // ---- fallback ----

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(ex, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred.", null, request);
    }

    /**
     * Every response passes through here, including the framework's own problem details
     * (404 for unknown paths, 405, 415, ...): add {@code requestId}. The parent sets {@code instance}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // For framework exceptions `body` is still null here: the parent builds the ProblemDetail,
        // so add requestId to what it returns.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            problemDetail.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        }
        return response;
    }

    private ResponseEntity<Object> respond(Exception ex, HttpStatus status, String title, String detail,
                                           List<ValidationError> errors, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        if (errors != null) {
            problem.setProperty("errors", errors);
        }
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    private static ValidationError toValidationError(MessageSourceResolvable error, String defaultField) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : defaultField;
        return new ValidationError(field, error.getDefaultMessage());
    }
}
