package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.OrderPayments;
import com.ticketflow.booking.SettleResult;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pay -> tickets -> QR -> scan. The payment is settled through the same public call the webhook uses
 * ({@code OrderPayments.settle}), so the real OrderPaid event and the real AFTER_COMMIT listener run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class TicketFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderPayments orderPayments;
    @Autowired
    private TicketService ticketService;

    private BookingTestData data() {
        return new BookingTestData(jdbc);
    }

    /** A registered user with the given role (set directly in the database) and a token for them. */
    private record Account(long id, String token) {
    }

    private Account account(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(registered, "$.id")).longValue();
        jdbc.update("update users set role = ? where id = ?", role, id);
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Account(id, JsonPath.read(login, "$.accessToken"));
    }

    /** An event whose organizer is the given account. */
    private PublishedEvent eventOf(Account organizer, int seats) {
        PublishedEvent event = data().publishedEvent(seats, "25.00");
        jdbc.update("update events set organizer_id = ? where id = ?", organizer.id(), event.eventId());
        return event;
    }

    private long hold(Account customer, PublishedEvent event, int... positions) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int position : positions) {
            ids.add(event.seatIds().get(position));
        }
        String body = mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + customer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":%d,\"seatIds\":%s}".formatted(event.eventId(), ids)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Holds and pays; returns the order id once the tickets exist. */
    private long payFor(Account customer, PublishedEvent event, int... positions) throws Exception {
        long orderId = hold(customer, event, positions);
        assertThat(orderPayments.settle(orderId)).isEqualTo(SettleResult.PAID);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(data().count("select count(*) from tickets where order_id = ?", orderId))
                        .isEqualTo(positions.length));
        return orderId;
    }

    private ResultActions listTickets(Account customer) throws Exception {
        return mockMvc.perform(get("/api/tickets").header("Authorization", "Bearer " + customer.token()));
    }

    private ResultActions validate(Account organizer, String code) throws Exception {
        return mockMvc.perform(post("/api/tickets/validate").header("Authorization", "Bearer " + organizer.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code)));
    }

    private String codeOf(long ticketId) {
        return jdbc.queryForObject("select code from tickets where id = ?", String.class, ticketId);
    }

    // ---- issuing ----

    @Test
    void payingCreatesOneTicketPerSeatWithUniqueCodes() throws Exception {
        Account organizer = account("ORGANIZER");
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(organizer, 5);

        long orderId = payFor(customer, event, 0, 1, 2);

        List<String> codes = jdbc.queryForList("select code from tickets where order_id = ?", String.class, orderId);
        assertThat(new HashSet<>(codes)).hasSize(3);
        assertThat(codes).allSatisfy(code -> assertThat(code).matches("[A-Za-z0-9_-]{22}"));
        listTickets(customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].status").value("VALID"))
                .andExpect(jsonPath("$.content[0].eventTitle").value("Concert"))
                .andExpect(jsonPath("$.content[0].section").value("Floor"));
    }

    @Test
    void anOrderThatIsNotPaidHasNoTickets() throws Exception {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);

        long orderId = hold(customer, event, 0);

        assertThat(data().count("select count(*) from tickets where order_id = ?", orderId)).isZero();
        listTickets(customer).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void issuingAgainForTheSameOrderDoesNotDuplicateTickets() throws Exception {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);
        long orderId = payFor(customer, event, 0, 1);

        int again = ticketService.issue(new com.ticketflow.booking.OrderPaid(orderId, customer.id(), event.eventId()));

        assertThat(again).isZero();
        assertThat(data().count("select count(*) from tickets where order_id = ?", orderId)).isEqualTo(2);
    }

    @Test
    void theDatabaseAlsoRefusesASecondLiveTicketForTheSameSeatButAllowsOneAfterACancellation() throws Exception {
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);
        long orderId = payFor(customer, event, 0);
        long seat = event.seatIds().get(0);
        String insert = "insert into tickets (order_id, event_id, event_seat_id, customer_id, code, status, event_title, "
                + "section_name, row_label, seat_number, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, 'Concert', 'Floor', '1', 1, now(), now())";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, orderId, event.eventId(), seat,
                customer.id(), "dup-" + UUID.randomUUID(), "VALID")).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("update tickets set status = 'CANCELLED' where order_id = ?", orderId); // e.g. after a refund
        jdbc.update(insert, orderId, event.eventId(), seat, customer.id(), "ok-" + UUID.randomUUID(), "VALID");
    }

    // ---- reading and the QR ----

    @Test
    void theQrOfATicketDecodesToItsCodeAndOnlyTheOwnerGetsIt() throws Exception {
        Account customer = account("CUSTOMER");
        Account stranger = account("CUSTOMER");
        PublishedEvent event = eventOf(account("ORGANIZER"), 3);
        long orderId = payFor(customer, event, 0);
        long ticketId = jdbc.queryForObject("select id from tickets where order_id = ?", Long.class, orderId);

        byte[] png = mockMvc.perform(get("/api/tickets/" + ticketId + "/qr")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png)))))).getText();
        assertThat(decoded).isEqualTo(codeOf(ticketId));
        mockMvc.perform(get("/api/tickets/" + ticketId + "/qr").header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
        listTickets(stranger).andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- scanning ----

    @Test
    void validatingTwiceFirstWorksThenIs409() throws Exception {
        Account organizer = account("ORGANIZER");
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(organizer, 3);
        long orderId = payFor(customer, event, 0);
        long ticketId = jdbc.queryForObject("select id from tickets where order_id = ?", Long.class, orderId);
        String code = codeOf(ticketId);

        validate(organizer, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticketId))
                .andExpect(jsonPath("$.eventTitle").value("Concert"));
        validate(organizer, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This ticket has already been used."));

        mockMvc.perform(get("/api/tickets/" + ticketId).header("Authorization", "Bearer " + customer.token()))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.usedAt").isNotEmpty());
    }

    @Test
    void anotherOrganizerCannotValidateAndTheTicketStaysValid() throws Exception {
        Account owner = account("ORGANIZER");
        Account other = account("ORGANIZER");
        Account customer = account("CUSTOMER");
        PublishedEvent event = eventOf(owner, 3);
        long orderId = payFor(customer, event, 0);
        long ticketId = jdbc.queryForObject("select id from tickets where order_id = ?", Long.class, orderId);

        validate(other, codeOf(ticketId)).andExpect(status().isNotFound());
        validate(owner, "no-such-code-at-all").andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from tickets where id = ?", String.class, ticketId))
                .isEqualTo("VALID");
        validate(owner, codeOf(ticketId)).andExpect(status().isOk());
    }

    @Test
    void aCancelledTicketCannotBeScanned() throws Exception {
        Account organizer = account("ORGANIZER");
        PublishedEvent event = eventOf(organizer, 3);
        long orderId = payFor(account("CUSTOMER"), event, 0);
        jdbc.update("update tickets set status = 'CANCELLED' where order_id = ?", orderId);
        String code = jdbc.queryForObject("select code from tickets where order_id = ?", String.class, orderId);

        validate(organizer, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This ticket is not valid."));
    }

    /**
     * Two entrance scanners read the same ticket at the same moment: exactly one gets in. Repeated because a race
     * that is lost only sometimes would otherwise pass by luck.
     */
    @RepeatedTest(5)
    void simultaneousValidationsOfOneTicketLetExactlyOneThrough() throws Exception {
        Account organizer = account("ORGANIZER");
        PublishedEvent event = eventOf(organizer, 3);
        long orderId = payFor(account("CUSTOMER"), event, 0);
        String code = jdbc.queryForObject("select code from tickets where order_id = ?", String.class, orderId);
        int scanners = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < scanners; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        ticketService.validate(organizer.id(), code);
                        accepted.incrementAndGet();
                    } catch (com.ticketflow.common.exception.ConflictException lost) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS); // rethrows anything unexpected
            }
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(scanners - 1);
        assertThat(jdbc.queryForObject("select status from tickets where order_id = ?", String.class, orderId))
                .isEqualTo("USED");
    }
}
