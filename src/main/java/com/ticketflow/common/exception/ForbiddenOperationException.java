package com.ticketflow.common.exception;

/** The caller is authenticated but not allowed to perform the operation (403). */
public class ForbiddenOperationException extends DomainException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
