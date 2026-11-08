package com.ticketflow.common.exception;

/** The requested resource does not exist, or the caller may not know it exists (404). */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
