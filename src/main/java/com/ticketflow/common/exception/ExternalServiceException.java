package com.ticketflow.common.exception;

/** A service we depend on (for example the payment provider) failed or did not answer (502). */
public class ExternalServiceException extends DomainException {

    public ExternalServiceException(String message) {
        super(message);
    }
}
