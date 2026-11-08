package com.ticketflow.booking;

import com.ticketflow.common.exception.ConflictException;

/**
 * At least one requested seat could not be held. It is a 409 and deliberately does not say which seat
 * or who holds it. Throwing it rolls the transaction back, so no seat of the request stays held.
 */
public class SeatsUnavailableException extends ConflictException {

    public SeatsUnavailableException() {
        super("One or more of the selected seats are no longer available.");
    }
}
