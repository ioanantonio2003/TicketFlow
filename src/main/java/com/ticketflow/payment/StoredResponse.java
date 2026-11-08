package com.ticketflow.payment;

/** An HTTP answer kept for an Idempotency-Key, replayed byte for byte on a retry. */
record StoredResponse(int statusCode, String body) {
}
