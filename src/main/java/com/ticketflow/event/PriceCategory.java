package com.ticketflow.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The price of all seats of one section of the venue, for one event. */
@Entity
@Table(name = "price_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PriceCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private Event event;

    @Column(name = "section_id", nullable = false, updatable = false)
    private Long sectionId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    PriceCategory(Event event, Long sectionId, BigDecimal price) {
        this.event = event;
        this.sectionId = sectionId;
        this.price = price;
    }

    void changePrice(BigDecimal newPrice) {
        this.price = newPrice;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PriceCategory other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
