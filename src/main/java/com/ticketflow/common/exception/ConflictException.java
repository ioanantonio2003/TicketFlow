package com.ticketflow.common.exception;

/** The request conflicts with the current state of a resource, e.g. a duplicate or a taken seat (409). */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
