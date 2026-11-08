package com.ticketflow.auth;

import org.springframework.security.core.AuthenticationException;

/**
 * The refresh token is unknown, expired, revoked or reused. It is an AuthenticationException so the
 * existing handler answers with the same generic 401; the reason is never revealed to the caller.
 */
class InvalidTokenException extends AuthenticationException {

    InvalidTokenException(String message) {
        super(message);
    }
}
