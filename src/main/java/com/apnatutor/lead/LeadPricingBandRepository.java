package com.apnatutor.lead;

import java.util.List;

import com.apnatutor.lead.domain.LeadPricingBand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadPricingBandRepository extends JpaRepository<LeadPricingBand, Long> {

	List<LeadPricingBand> findAllByOrderByMinBudgetPaiseAsc();

	boolean existsByMinBudgetPaise(long minBudgetPaise);
}
