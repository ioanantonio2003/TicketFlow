package com.ticketflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.OrderPaid;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/**
 * The whole payment flow over real HTTP, because the simulated provider calls our webhook through the network.
 * A fixed free port is chosen up front so that {@code app.payment.base-url} (read when the beans are created)
 * can point at the running server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({TestcontainersConfig.class, PaymentFlowIT.TestBeans.class})
@ActiveProfiles("test")
class PaymentFlowIT {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String WEBHOOK_SECRET = "test-only-fakepay-secret-0123456789";
    private static final int PORT = freePort();

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.payment.base-url", () -> "http://localhost:" + PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Collects OrderPaid events that reached an AFTER_COMMIT listener. */
    static class PaidOrders {
        private final List<OrderPaid> received = new ArrayList<>();

        @TransactionalEventListener
        synchronized void on(OrderPaid event) {
            received.add(event);
        }

        synchronized long count(long orderId) {
            return received.stream().filter(e -> e.orderId() == orderId).count();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        PaidOrders paidOrders() {
            return new PaidOrders();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaidOrders paidOrders;

    /** Does not throw on 4xx/5xx: the tests look at the status codes. */
    private final RestClient http = RestClient.builder().baseUrl("http://localhost:" + PORT)
            .defaultStatusHandler(status -> true, (request, response) -> { }).build();

    private BookingTestData data() {
        return new BookingTestData(jdbc);
    }

    // ---- the flow ----

    @Test
    void holdCheckoutCompleteAndTheOrderIsPaidAndTheSeatsSold() {
        PublishedEvent event = data().publishedEvent(5, "40.00");
        String token = customerToken();
        long orderId = hold(token, event, 0, 1);

        ResponseEntity<String> checkout = checkout(token, orderId, UUID.randomUUID().toString());
        assertThat(checkout.getStatusCode().value()).isEqualTo(201);
        String providerId = JsonPath.read(checkout.getBody(), "$.providerPaymentId");
        assertThat((String) JsonPath.read(checkout.getBody(), "$.status")).isEqualTo("PENDING");
        assertThat(((Number) JsonPath.read(checkout.getBody(), "$.amount")).doubleValue()).isEqualTo(80.00);
        assertThat((String) JsonPath.read(checkout.getBody(), "$.payUrl")).contains(providerId);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING"); // nothing is paid before the customer pays

        assertThat(complete(providerId, "success").getStatusCode().value()).isEqualTo(202);

        awaitOrderStatus(orderId, "PAID"); // the webhook arrives asynchronously
        assertThat(paymentStatus(providerId)).isEqualTo("SUCCEEDED");
        assertThat(seats(event, "SOLD")).isEqualTo(2);
        assertThat(seats(event, "AVAILABLE")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select hold_expires_at from orders where id = ?", Instant.class, orderId))
                .isNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(paidOrders.count(orderId)).isEqualTo(1));
    }

    @Test
    void checkingOutTwiceWithTheSameKeyGivesTheSameAnswerAndOnePayment() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = checkout(token, orderId, key);
        ResponseEntity<String> second = checkout(token, orderId, key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody()); // byte for byte
        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherOrderIs422() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderA = hold(token, event, 0);
        long orderB = hold(token, event, 1);
        String key = UUID.randomUUID().toString();
        checkout(token, orderA, key);

        ResponseEntity<String> reused = checkout(token, orderB, key);

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(count("select count(*) from payments where order_id = ?", orderB)).isZero();
    }

    @Test
    void aNewKeyStartsANewPaymentAttempt() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);

        checkout(token, orderId, UUID.randomUUID().toString());
        checkout(token, orderId, UUID.randomUUID().toString());

        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isEqualTo(2);
    }

    @Test
    void parallelCheckoutsWithTheSameKeyCreateOnePayment() throws Exception {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return checkout(token, orderId, key).getStatusCode().value();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                // the winner and any replay answer 201; a request that arrived mid-flight may get 409
                assertThat(result.get(30, TimeUnit.SECONDS)).isIn(201, 409);
            }
        }
        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void checkoutNeedsTheKeyAndAnOwnPendingOrder() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String owner = customerToken();
        String stranger = customerToken();
        long orderId = hold(owner, event, 0);

        assertThat(http.post().uri("/api/orders/" + orderId + "/checkout")
                .header("Authorization", "Bearer " + owner).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(400);                                                   // no Idempotency-Key
        assertThat(checkout(stranger, orderId, "k-" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        assertThat(http.post().uri("/api/orders/" + orderId + "/checkout").header("Idempotency-Key", "k")
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);

        jdbc.update("update orders set status = 'CANCELLED' where id = ?", orderId);
        assertThat(checkout(owner, orderId, "k-" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(409);
        jdbc.update("update orders set status = 'PENDING', hold_expires_at = now() - interval '1 minute' where id = ?",
                orderId);
        assertThat(checkout(owner, orderId, "k-" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(409);
        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isZero();
    }

    // ---- webhooks ----

    @Test
    void theSameWebhookDeliveredTwiceMakesTheOrderPaidOnce() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0, 1);
        String providerId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");
        String body = "{\"providerPaymentId\":\"" + providerId + "\",\"outcome\":\"SUCCESS\"}";

        assertThat(webhook(body, now(), sign(now(), body)).getStatusCode().value()).isEqualTo(200);
        assertThat(webhook(body, now(), sign(now(), body)).getStatusCode().value()).isEqualTo(200); // the retry

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paymentStatus(providerId)).isEqualTo("SUCCEEDED");
        assertThat(seats(event, "SOLD")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select max(version) from event_seats where order_id = ?", Long.class, orderId))
                .isEqualTo(2L); // held (+1) and sold (+1), not sold twice
        assertThat(paidOrders.count(orderId)).isEqualTo(1);
    }

    @Test
    void aWebhookWithABadSignatureOrAnOldTimestampChangesNothing() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String providerId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");
        String body = "{\"providerPaymentId\":\"" + providerId + "\",\"outcome\":\"SUCCESS\"}";
        String oldTimestamp = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());

        assertThat(webhook(body, now(), "0".repeat(64)).getStatusCode().value()).isEqualTo(401);
        assertThat(webhook(body, now(), WebhookSignature.sign("another-secret", now(), body)).getStatusCode().value())
                .isEqualTo(401);
        assertThat(webhook(body, oldTimestamp, sign(oldTimestamp, body)).getStatusCode().value()).isEqualTo(401);
        assertThat(http.post().uri("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(providerId)).isEqualTo("PENDING");
        assertThat(seats(event, "SOLD")).isZero();
    }

    @Test
    void aSignedWebhookForAnUnknownPaymentIs404() {
        String body = "{\"providerPaymentId\":\"fp_does_not_exist\",\"outcome\":\"SUCCESS\"}";

        assertThat(webhook(body, now(), sign(now(), body)).getStatusCode().value()).isEqualTo(404);
    }

    // ---- outcomes ----

    @Test
    void aFailedPaymentLeavesTheOrderPendingAndTheCustomerCanPayAgain() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String firstProviderId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");

        complete(firstProviderId, "failure");
        awaitPaymentStatus(firstProviderId, "FAILED");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(seats(event, "HELD")).isEqualTo(1);

        String secondProviderId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");
        complete(secondProviderId, "success");

        awaitOrderStatus(orderId, "PAID");
        assertThat(paymentStatus(secondProviderId)).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(firstProviderId)).isEqualTo("FAILED");
        assertThat(seats(event, "SOLD")).isEqualTo(1);
    }

    @Test
    void paymentArrivingAfterTheHoldExpiredIsMarkedRefundedAndTheOrderStaysExpired() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0, 1);
        String providerId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");
        // what the expiry job does, done directly: the customer was too slow
        jdbc.update("update orders set status = 'EXPIRED' where id = ?", orderId);
        jdbc.update("update event_seats set status = 'AVAILABLE', order_id = null, hold_expires_at = null "
                + "where order_id = ?", orderId);

        complete(providerId, "success");

        awaitPaymentStatus(providerId, "REFUNDED");
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(seats(event, "SOLD")).isZero();
        assertThat(seats(event, "AVAILABLE")).isEqualTo(3);
        assertThat(paidOrders.count(orderId)).isZero();
    }

    @Test
    void payingTwiceForTheSameOrderRefundsTheSecondPayment() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String first = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");
        String second = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");

        complete(first, "success");
        awaitOrderStatus(orderId, "PAID");
        complete(second, "success");

        awaitPaymentStatus(second, "REFUNDED");
        assertThat(paymentStatus(first)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paidOrders.count(orderId)).isEqualTo(1);
    }

    @Test
    void aProviderPaymentCanBeCompletedOnlyOnce() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        String token = customerToken();
        long orderId = hold(token, event, 0);
        String providerId = JsonPath.read(checkout(token, orderId, UUID.randomUUID().toString()).getBody(),
                "$.providerPaymentId");

        assertThat(complete(providerId, "success").getStatusCode().value()).isEqualTo(202);
        assertThat(complete(providerId, "success").getStatusCode().value()).isEqualTo(409);
        assertThat(complete("fp_unknown", "success").getStatusCode().value()).isEqualTo(404);
        assertThat(complete(providerId, "maybe").getStatusCode().value()).isEqualTo(400);
    }

    // ---- helpers ----

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String sign(String timestamp, String body) {
        return WebhookSignature.sign(WEBHOOK_SECRET, timestamp, body);
    }

    private ResponseEntity<Void> webhook(String body, String timestamp, String signature) {
        return http.post().uri("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                .header(WebhookSignature.SIGNATURE_HEADER, signature)
                .body(body).retrieve().toBodilessEntity();
    }

    private ResponseEntity<String> checkout(String token, long orderId, String key) {
        return http.post().uri("/api/orders/" + orderId + "/checkout")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<Void> complete(String providerId, String outcome) {
        return http.post().uri("/fakepay/payments/" + providerId + "/complete?outcome=" + outcome)
                .retrieve().toBodilessEntity();
    }

    /** Holds the seats at the given positions of the event's seat list and returns the order id. */
    private long hold(String token, PublishedEvent event, int... positions) {
        List<Long> ids = new ArrayList<>();
        for (int position : positions) {
            ids.add(event.seatIds().get(position));
        }
        ResponseEntity<String> response = http.post().uri("/api/orders")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .body("{\"eventId\":%d,\"seatIds\":%s}".formatted(event.eventId(), ids))
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(response.getBody(), "$.id")).longValue();
    }

    private String customerToken() {
        String email = "pay-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> registered = http.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}".formatted(email, PASSWORD))
                .retrieve().toEntity(String.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        String login = http.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
                .retrieve().body(String.class);
        return JsonPath.read(login, "$.accessToken");
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private String paymentStatus(String providerId) {
        return jdbc.queryForObject("select status from payments where provider_payment_id = ?", String.class,
                providerId);
    }

    private int seats(PublishedEvent event, String status) {
        return count("select count(*) from event_seats where event_id = ? and status = ?", event.eventId(), status);
    }

    private int count(String sql, Object... args) {
        return data().count(sql, args);
    }

    private void awaitOrderStatus(long orderId, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo(expected));
    }

    private void awaitPaymentStatus(String providerId, String expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(paymentStatus(providerId)).isEqualTo(expected));
    }
}
