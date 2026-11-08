package com.ticketflow.user;

/** Roles of a user. A user has exactly one; the JWT carries it as authority {@code ROLE_<name>}. */
public enum Role {
    CUSTOMER,
    ORGANIZER,
    ADMIN
}
