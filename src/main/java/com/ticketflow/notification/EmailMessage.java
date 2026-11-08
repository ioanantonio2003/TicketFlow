package com.ticketflow.notification;

/** A ready-to-send plain-text email. */
record EmailMessage(String to, String subject, String body) {
}
