package com.ticketflow.common.web;

/** One entry of the {@code errors[]} array in a 400 response. */
public record ValidationError(String field, String message) {
}
