package com.ticketflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Refunds, event cancellation and the emails, over real HTTP (the simulated provider is called through the network).
 * Mail goes to a capturing sender instead of an SMTP server, so no real mail is ever sent. The batch size is 2 so
 * that cancelling an event with 5 paid orders needs 3 batches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.notification.enabled=true", "app.booking.expiry-batch-size=2"})
@Import({TestcontainersConfig.class, RefundAndCancellationIT.TestBeans.class})
@ActiveProfiles("test")
class RefundAndCancellationIT {

    private static final String PASSWORD = "correct-horse-battery";
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

    /** Keeps every email instead of sending it. */
    static class CapturingMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
            sent.addAll(List.of(mimeMessages));
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        CapturingMailSender mailSender() {
            return new CapturingMailSender();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingMailSender mailSender;

    private final RestClient http = RestClient.builder().baseUrl("http://localhost:" + PORT)
            .defaultStatusHandler(status -> true, (request, response) -> { }).build();

    private record Account(long id, String email, String token) {
    }

    private BookingTestData data() {
        return new BookingTestData(jdbc);
    }

    // ---- refunds ----

    @Test
    void payingSendsAnEmailWithTheTicketCodes() {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 5);

        long orderId = pay(customer, event, 0, 1);

        List<String> codes = jdbc.queryForList("select code from tickets where order_id = ?", String.class, orderId);
        MimeMessage mail = awaitMail(customer.email(), "Your tickets for Concert");
        assertThat(body(mail)).contains(codes.get(0), codes.get(1), "order #" + orderId);
    }

    @Test
    void aRefundGivesBackTheMoneyTheSeatsAndInvalidatesTheTickets() {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 5);
        long orderId = pay(customer, event, 0, 1);
        assertThat(seats(event, "SOLD")).isEqualTo(2);

        ResponseEntity<String> refund = refund(customer, orderId);

        assertThat(refund.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(refund.getBody(), "$.status")).isEqualTo("REFUNDED");
        assertThat(((Number) JsonPath.read(refund.getBody(), "$.amount")).doubleValue()).isEqualTo(50.00);
        assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select status from payments where order_id = ?", String.class, orderId))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForList("select status from tickets where order_id = ?", String.class, orderId))
                .containsOnly("CANCELLED");
        assertThat(seats(event, "AVAILABLE")).isEqualTo(5);
        assertThat(seats(event, "SOLD")).isZero();
        assertThat(body(awaitMail(customer.email(), "Refund for Concert"))).contains("50.00 RON");
        // the freed seats can be bought again, and the new ticket does not clash with the cancelled one
        long again = pay(account("CUSTOMER"), event, 0, 1);
        assertThat(jdbc.queryForList("select status from tickets where order_id = ?", String.class, again))
                .containsOnly("VALID");
    }

    @Test
    void aSecondRefundOfTheSameOrderIs409() {
        Account customer = account("CUSTOMER");
        long orderId = pay(customer, eventOf(account("ORGANIZER"), 3), 0);
        assertThat(refund(customer, orderId).getStatusCode().value()).isEqualTo(200);

        assertThat(refund(customer, orderId).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void aRefundLessThan24HoursBeforeTheEventIs422AndChangesNothing() {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);
        long orderId = pay(customer, event, 0);
        jdbc.update("update events set starts_at = now() + interval '23 hours' where id = ?", event.eventId());

        ResponseEntity<String> refund = refund(customer, orderId);

        assertThat(refund.getStatusCode().value()).isEqualTo(422);
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(seats(event, "SOLD")).isEqualTo(1);
        assertThat(jdbc.queryForList("select status from tickets where order_id = ?", String.class, orderId))
                .containsOnly("VALID");
    }

    @Test
    void onlyThePaidOrderOfTheCallerCanBeRefunded() {
        Account owner = account("CUSTOMER");
        Account stranger = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 4);
        long paid = pay(owner, event, 0);
        long pending = hold(owner, event, 1);

        assertThat(refund(stranger, paid).getStatusCode().value()).isEqualTo(404);
        assertThat(refund(owner, pending).getStatusCode().value()).isEqualTo(409);
        assertThat(orderStatus(paid)).isEqualTo("PAID");
    }

    // ---- event cancellation ----

    @Test
    void cancellingAnEventRefundsPaidOrdersCancelsPendingOnesAndNotifiesEveryone() {
        Account organizer = account("ORGANIZER");
        Account first = account("CUSTOMER");
        Account second = account("CUSTOMER");
        Account third = account("CUSTOMER");
        PublishedEvent event = eventOf(organizer, 6);
        long paid1 = pay(first, event, 0, 1);
        long paid2 = pay(second, event, 2);
        long pending = hold(third, event, 3);

        ResponseEntity<String> cancel = cancelEvent(organizer, event);

        assertThat(cancel.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(cancel.getBody(), "$.status")).isEqualTo("CANCELLED");
        assertThat(orderStatus(paid1)).isEqualTo("REFUNDED");
        assertThat(orderStatus(paid2)).isEqualTo("REFUNDED");
        assertThat(orderStatus(pending)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("select distinct status from payments where order_id in (?, ?)", String.class,
                paid1, paid2)).containsOnly("REFUNDED");
        assertThat(jdbc.queryForList("select distinct status from tickets where order_id in (?, ?)", String.class,
                paid1, paid2)).containsOnly("CANCELLED");
        assertThat(seats(event, "AVAILABLE")).isEqualTo(6);
        awaitMail(first.email(), "Event cancelled: Concert");
        awaitMail(second.email(), "Event cancelled: Concert");
        assertThat(body(awaitMail(third.email(), "Event cancelled: Concert"))).contains("You have not been charged");

        // the event cannot be booked any more, nor cancelled twice
        assertThat(holdSeats(third, event, false, 4)).isEqualTo(-1L);
        assertThat(cancelEvent(organizer, event).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void aDifferentOrganizerCannotCancelTheEvent() {
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);

        assertThat(cancelEvent(account("ORGANIZER"), event).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, event.eventId()))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void manyPaidOrdersAreRefundedInSeveralBatches() {
        Account organizer = account("ORGANIZER");
        PublishedEvent event = eventOf(organizer, 6);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {   // the batch size is 2: three batches
            orders.add(pay(account("CUSTOMER"), event, i));
        }

        assertThat(cancelEvent(organizer, event).getStatusCode().value()).isEqualTo(200);

        assertThat(orders).allSatisfy(id -> assertThat(orderStatus(id)).isEqualTo("REFUNDED"));
        assertThat(seats(event, "AVAILABLE")).isEqualTo(6);
        assertThat(data().count("select count(*) from payments where status = 'REFUNDED' and order_id in "
                + "(select id from orders where event_id = ?)", event.eventId())).isEqualTo(5);
    }

    @Test
    void cancellingADraftEventJustCancelsIt() {
        Account organizer = account("ORGANIZER");
        PublishedEvent event = eventOf(organizer, 3);
        jdbc.update("update events set status = 'DRAFT' where id = ?", event.eventId());

        assertThat(cancelEvent(organizer, event).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, event.eventId()))
                .isEqualTo("CANCELLED");
    }

    // ---- helpers ----

    private Account account(String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String registered = http.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}".formatted(email, PASSWORD))
                .retrieve().body(String.class);
        long id = ((Number) JsonPath.read(registered, "$.id")).longValue();
        jdbc.update("update users set role = ? where id = ?", role, id);
        String login = http.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)).retrieve().body(String.class);
        return new Account(id, email, JsonPath.read(login, "$.accessToken"));
    }

    private PublishedEvent eventOf(Account organizer, int seats) {
        PublishedEvent event = data().publishedEvent(seats, "25.00");
        jdbc.update("update events set organizer_id = ? where id = ?", organizer.id(), event.eventId());
        return event;
    }

    private long hold(Account customer, PublishedEvent event, int position) {
        return holdSeats(customer, event, true, position);
    }

    /** With {@code expectSuccess=false} returns -1 when the server refuses instead of failing the test. */
    private long holdSeats(Account customer, PublishedEvent event, boolean expectSuccess, int... positions) {
        List<Long> ids = new ArrayList<>();
        for (int position : positions) {
            ids.add(event.seatIds().get(position));
        }
        ResponseEntity<String> response = http.post().uri("/api/orders")
                .header("Authorization", "Bearer " + customer.token()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"eventId\":%d,\"seatIds\":%s}".formatted(event.eventId(), ids))
                .retrieve().toEntity(String.class);
        if (!expectSuccess) {
            return response.getStatusCode().is2xxSuccessful()
                    ? ((Number) JsonPath.read(response.getBody(), "$.id")).longValue() : -1L;
        }
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return ((Number) JsonPath.read(response.getBody(), "$.id")).longValue();
    }

    /** Holds, checks out, "pays" at the simulated provider and waits until the order is PAID and has its tickets. */
    private long pay(Account customer, PublishedEvent event, int... positions) {
        long orderId = holdSeats(customer, event, true, positions);
        ResponseEntity<String> checkout = http.post().uri("/api/orders/" + orderId + "/checkout")
                .header("Authorization", "Bearer " + customer.token())
                .header("Idempotency-Key", UUID.randomUUID().toString()).retrieve().toEntity(String.class);
        assertThat(checkout.getStatusCode().value()).as(checkout.getBody()).isEqualTo(201);
        String providerId = JsonPath.read(checkout.getBody(), "$.providerPaymentId");
        http.post().uri("/fakepay/payments/" + providerId + "/complete?outcome=success").retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(orderStatus(orderId)).isEqualTo("PAID");
            assertThat(data().count("select count(*) from tickets where order_id = ?", orderId))
                    .isEqualTo(positions.length);
        });
        return orderId;
    }

    private ResponseEntity<String> refund(Account customer, long orderId) {
        return http.post().uri("/api/orders/" + orderId + "/refund")
                .header("Authorization", "Bearer " + customer.token()).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> cancelEvent(Account organizer, PublishedEvent event) {
        return http.post().uri("/api/events/" + event.eventId() + "/cancel")
                .header("Authorization", "Bearer " + organizer.token()).retrieve().toEntity(String.class);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int seats(PublishedEvent event, String status) {
        return data().count("select count(*) from event_seats where event_id = ? and status = ?", event.eventId(),
                status);
    }

    /** Emails are sent in the background: wait until one with this subject has arrived for this address. */
    private MimeMessage awaitMail(String to, String subject) {
        await().atMost(Duration.ofSeconds(10)).until(() -> mailSender.sent.stream().anyMatch(m -> is(m, to, subject)));
        return mailSender.sent.stream().filter(m -> is(m, to, subject)).findFirst().orElseThrow();
    }

    private static boolean is(MimeMessage message, String to, String subject) {
        try {
            return subject.equals(message.getSubject()) && message.getAllRecipients()[0].toString().equals(to);
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String body(MimeMessage message) {
        try {
            return message.getContent().toString();
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException(e);
        }
    }
}
