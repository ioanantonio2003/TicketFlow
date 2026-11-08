package com.ticketflow.common.exception;

/**
 * Base class for business errors raised by services. Each subclass maps to one HTTP status
 * in {@code GlobalExceptionHandler}; the message is written by us and safe to show to clients.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
