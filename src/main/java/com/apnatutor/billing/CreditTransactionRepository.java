package com.apnatutor.billing;

import java.util.List;

import com.apnatutor.billing.domain.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Note there are no update or delete methods, and none should be added. The ledger is append-only
 * (SOURCE_OF_TRUTH.md Invariant 1) and the database raises on any attempt — a repository method
 * offering one would only produce a runtime failure at the worst moment.
 */
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

	List<CreditTransaction> findByTutorIdOrderByCreatedAtDesc(Long tutorId);

	/** The replayed balance, for reconciliation against the cached one. */
	@Query("SELECT SUM(t.amount) FROM CreditTransaction t WHERE t.tutorId = :tutorId")
	Integer sumAmountFor(@Param("tutorId") Long tutorId);
}
