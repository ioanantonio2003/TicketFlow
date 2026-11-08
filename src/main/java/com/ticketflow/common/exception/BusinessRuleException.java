package com.ticketflow.common.exception;

/** The request is well-formed but a business rule forbids it, e.g. an illegal state transition (422). */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
