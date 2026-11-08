package com.ticketflow.common.web;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ForbiddenOperationException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that throws each exception we map. Active only in the "error-test" profile,
 * so the full-application tests (which scan src/test too) never expose it.
 */
@RestController
@Profile("error-test")
@RequestMapping("/test-errors")
class ErrorTestController {

    record Body(@NotBlank String name, @Min(1) Integer quantity) {
    }

    private final Validator validator;

    ErrorTestController(Validator validator) {
        this.validator = validator;
    }

    @GetMapping("/not-found")
    void notFound() {
        throw new ResourceNotFoundException("Event 42 not found");
    }

    @GetMapping("/conflict")
    void conflict() {
        throw new ConflictException("Email already registered");
    }

    @GetMapping("/business-rule")
    void businessRule() {
        throw new BusinessRuleException("Event is already cancelled");
    }

    @GetMapping("/forbidden")
    void forbidden() {
        throw new ForbiddenOperationException("Not your order");
    }

    @GetMapping("/data-integrity")
    void dataIntegrity() {
        throw new DataIntegrityViolationException("ERROR: duplicate key violates constraint uk_secret_table");
    }

    @GetMapping("/optimistic-lock")
    void optimisticLock() {
        throw new OptimisticLockingFailureException("row was updated by another transaction");
    }

    @GetMapping("/unknown-property")
    void unknownProperty() {
        throw new PropertyReferenceException("nope", TypeInformation.of(String.class), List.of());
    }

    @GetMapping("/bad-credentials")
    void badCredentials() {
        throw new BadCredentialsException("password does not match for user secret@example.com");
    }

    @GetMapping("/access-denied")
    void accessDenied() {
        throw new AccessDeniedException("internal rule details");
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("internal detail that must not leak");
    }

    @GetMapping("/constraint-violation")
    void constraintViolation() {
        throw new ConstraintViolationException(validator.validate(new Body("", 0)));
    }

    @PostMapping("/body")
    void body(@Valid @RequestBody Body body) {
    }

    @GetMapping("/param")
    void param(@RequestParam @Min(1) int count) {
    }
}
