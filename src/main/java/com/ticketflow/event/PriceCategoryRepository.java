package com.ticketflow.event;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PriceCategoryRepository extends JpaRepository<PriceCategory, Long> {

    List<PriceCategory> findByEventIdOrderBySectionId(Long eventId);
}
