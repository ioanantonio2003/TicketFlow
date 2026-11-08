# ADR-0003: Modular monolith

- Status: accepted
- Date: 2026-11-08

## Context

TicketFlow has several areas (accounts, venues, events, booking, payments, tickets, notifications, reporting). They
could be separate services or one application. The team is one person, the data is strongly related (a booking
changes orders, seats and later payments and tickets), and the most important rule, "never sell a seat twice", is
easiest to guarantee inside one database transaction.

## Options considered

1. **Microservices.** Independent deployment and scaling, but a booking would span several services: distributed
   transactions or sagas, network failures between them, several databases to keep consistent, and much more
   infrastructure. Far too heavy for the size of this project.
2. **A single "layered" application** (all controllers in one package, all services in another). Simple, but nothing
   stops any class from using any other, and the code turns into one tangle.
3. **A modular monolith, package by feature.** One deployable application and one database, with clear feature
   boundaries inside it.

## Decision

Option 3. Each feature (`auth`, `user`, `venue`, `event`, `booking`, `payment`, `ticket`, `notification`,
`reporting`, `common`, `config`) is a package with its own controller, service, repository and entities. Rules:

- Classes are package-private unless another feature needs them.
- A feature uses another one only through a small public class (`EventCatalog`, `OrderPayments`, `OrderRefunds`,
  `OrderSeats`, `UserService`, ...) that exposes plain values, never entities or repositories.
- Features refer to each other's data by id (`Long`), not by JPA relations. The one exception is the read-only
  `Event -> Venue` association used by queries.
- Side effects across features go through application events (`OrderPaid`, `TicketsIssued`, `EventCancelled`, ...),
  so the sender does not depend on the receivers. Events that must not be lost half-way (tickets, emails) are
  handled after the commit.
- Reporting is the one deliberate exception: it is a read model that joins the tables of several features in SQL.

## Consequences

- One transaction can change orders and seats together, which is what makes the booking rule simple and testable.
- The boundaries are only a convention in the code; nothing enforces them at compile time. Reviews (and, later,
  an architecture test) have to keep them.
- Because the features already talk through events and small public classes, a feature can be moved into its own
  service later without rewriting the others. The cost of that move is the distributed consistency problem described
  above, which is deliberately not paid now.
- Events handled after the commit are not retried if the process dies at that moment. A transactional outbox would
  close the gap when it matters.
