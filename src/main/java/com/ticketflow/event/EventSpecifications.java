package com.ticketflow.event;

import com.ticketflow.venue.Venue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the public search query. Filters and ordering live in one specification so the venue is joined
 * exactly once: for the page query it is a fetch join (the venue's name and city come back in the same
 * SELECT, no N+1), for the count query a plain join (a fetch join is not allowed there).
 */
final class EventSpecifications {

    static final String SORT_STARTS_AT = "startsAt";
    static final String SORT_TITLE = "title";
    static final String SORT_MIN_PRICE = "minPrice";
    static final Set<String> SORTABLE = Set.of(SORT_STARTS_AT, SORT_TITLE, SORT_MIN_PRICE);

    private EventSpecifications() {
    }

    /**
     * @param orders already validated against {@link #SORTABLE}; {@code id} is always appended so that
     *               pages are stable when many events share a date or title
     */
    static Specification<Event> search(EventSearchCriteria criteria, List<Sort.Order> orders) {
        return (root, query, cb) -> {
            boolean countQuery = isCountQuery(query);
            Join<Event, Venue> venue = countQuery ? root.join("venue") : castFetch(root);

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));
            predicates.add(cb.greaterThan(root.get("startsAt"), criteria.now()));
            if (criteria.city() != null) {
                predicates.add(cb.equal(cb.lower(venue.get("city")), criteria.city().toLowerCase()));
            }
            if (criteria.category() != null) {
                predicates.add(cb.equal(root.get("category"), criteria.category()));
            }
            if (criteria.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startsAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startsAt"), criteria.to()));
            }
            if (criteria.titleContains() != null) {
                predicates.add(cb.like(cb.lower(root.get("title")),
                        "%" + escapeLike(criteria.titleContains().toLowerCase()) + "%", '\\'));
            }
            if (criteria.maxPrice() != null) {
                predicates.add(cb.exists(hasPriceUpTo(root, query, cb, criteria.maxPrice())));
            }

            if (!countQuery) { // ordering has no place in the count query
                query.orderBy(toOrders(orders, root, query, cb));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** "Some section costs at most maxPrice", i.e. the cheapest price of the event is within the budget. */
    private static Subquery<Long> hasPriceUpTo(Root<Event> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                               BigDecimal maxPrice) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<PriceCategory> price = sub.from(PriceCategory.class);
        sub.select(cb.literal(1L)).where(
                cb.equal(price.get("event"), root),
                cb.lessThanOrEqualTo(price.<BigDecimal>get("price"), maxPrice));
        return sub;
    }

    private static List<Order> toOrders(List<Sort.Order> orders, Root<Event> root, CriteriaQuery<?> query,
                                        CriteriaBuilder cb) {
        List<Order> result = new ArrayList<>();
        for (Sort.Order order : orders) {
            jakarta.persistence.criteria.Expression<?> expression = switch (order.getProperty()) {
                case SORT_TITLE -> cb.lower(root.get("title"));
                case SORT_MIN_PRICE -> minPrice(root, query, cb);
                default -> root.get(SORT_STARTS_AT);
            };
            result.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        result.add(cb.asc(root.get("id"))); // tie-breaker: makes paging deterministic
        return result;
    }

    private static Subquery<BigDecimal> minPrice(Root<Event> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<BigDecimal> sub = query.subquery(BigDecimal.class);
        Root<PriceCategory> price = sub.from(PriceCategory.class);
        sub.select(cb.min(price.<BigDecimal>get("price"))).where(cb.equal(price.get("event"), root));
        return sub;
    }

    private static boolean isCountQuery(CriteriaQuery<?> query) {
        return Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
    }

    @SuppressWarnings("unchecked")
    private static Join<Event, Venue> castFetch(Root<Event> root) {
        return (Join<Event, Venue>) root.<Event, Venue>fetch("venue");
    }

    /** Makes %, _ and \ in the user's text match literally in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
