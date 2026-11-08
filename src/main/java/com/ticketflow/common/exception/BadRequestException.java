package com.ticketflow.common.exception;

/** The request is understood but its parameters make no sense, e.g. an unsupported sort property (400). */
public class BadRequestException extends DomainException {

    public BadRequestException(String message) {
        super(message);
    }
}
