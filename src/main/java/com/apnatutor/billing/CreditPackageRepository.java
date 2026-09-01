package com.apnatutor.billing;

import java.util.List;

import com.apnatutor.billing.domain.CreditPackage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditPackageRepository extends JpaRepository<CreditPackage, Long> {

	/** The storefront. Retired packages stay resolvable by id but are never offered. */
	List<CreditPackage> findByActiveTrueOrderBySortOrderAscIdAsc();

	List<CreditPackage> findAllByOrderBySortOrderAscIdAsc();

	long countByActiveTrue();
}
