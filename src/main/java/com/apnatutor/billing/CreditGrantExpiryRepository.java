package com.apnatutor.billing;

import com.apnatutor.billing.domain.CreditGrantExpiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditGrantExpiryRepository extends JpaRepository<CreditGrantExpiry, Long> {
}
