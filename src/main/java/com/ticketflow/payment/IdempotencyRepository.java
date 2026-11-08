package com.ticketflow.payment;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stored results of requests that carry an {@code Idempotency-Key}. Plain SQL: one atomic statement per step. */
@Repository
@RequiredArgsConstructor
class IdempotencyRepository {

    /** {@code statusCode} is null while the first request is still running. */
    record StoredKey(String requestHash, Integer statusCode, String responseBody) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Claims the key. Exactly one of several concurrent requests with the same key gets true: the primary key
     * makes the others wait for the first transaction and then change nothing.
     */
    boolean tryStart(Long customerId, String key, String requestHash) {
        return jdbc.update("""
                        insert into idempotency_keys (key, customer_id, request_hash, created_at)
                        values (:key, :customerId, :hash, :now)
                        on conflict (customer_id, key) do nothing
                        """, params(customerId, key).addValue("hash", requestHash)
                .addValue("now", OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))) == 1;
    }

    Optional<StoredKey> find(Long customerId, String key) {
        return jdbc.query("select request_hash, status_code, response_body from idempotency_keys "
                                + "where customer_id = :customerId and key = :key", params(customerId, key),
                (rs, i) -> new StoredKey(rs.getString("request_hash"), (Integer) rs.getObject("status_code"),
                        rs.getString("response_body"))).stream().findFirst();
    }

    void complete(Long customerId, String key, int statusCode, String responseBody) {
        jdbc.update("update idempotency_keys set status_code = :status, response_body = :body "
                        + "where customer_id = :customerId and key = :key",
                params(customerId, key).addValue("status", statusCode).addValue("body", responseBody));
    }

    /** Forgets a key whose request failed in a way the client may retry. */
    void delete(Long customerId, String key) {
        jdbc.update("delete from idempotency_keys where customer_id = :customerId and key = :key",
                params(customerId, key));
    }

    private static MapSqlParameterSource params(Long customerId, String key) {
        return new MapSqlParameterSource().addValue("customerId", customerId).addValue("key", key);
    }
}
