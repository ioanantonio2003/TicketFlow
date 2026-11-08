package com.ticketflow.venue;

import com.ticketflow.common.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Aggregate root: a venue owns its sections, and each section owns its seats. Sections and seats are
 * only created and removed through the venue.
 */
@Entity
@Table(name = "venues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Venue extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id of the owning user. A plain id because the venue feature does not touch the user entities. */
    @Column(name = "organizer_id", nullable = false, updatable = false)
    private Long organizerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false)
    private String address;

    @Version
    private Long version;

    // The database removes sections (and their seats) when the venue is deleted (on delete cascade).
    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("sortOrder asc, id asc")
    private List<Section> sections = new ArrayList<>();

    Venue(Long organizerId, String name, String city, String address) {
        this.organizerId = organizerId;
        this.name = name;
        this.city = city;
        this.address = address;
    }

    Section addSection(String sectionName) {
        Section section = new Section(this, sectionName, sections.size());
        sections.add(section);
        return section;
    }

    /** Partial update: a null argument means "leave unchanged". */
    void update(String newName, String newCity, String newAddress) {
        if (newName != null) {
            this.name = newName;
        }
        if (newCity != null) {
            this.city = newCity;
        }
        if (newAddress != null) {
            this.address = newAddress;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Venue other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
