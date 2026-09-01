package com.apnatutor.billing;

import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
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

	/**
	 * The friendly check for "has this tutor had their signup bonus?".
	 *
	 * <p>Not the guarantee — that is the partial unique index on
	 * {@code (tutor_id) WHERE reason = 'SIGNUP_BONUS'}. Two approvals landing together would both
	 * pass this check.
	 */
	boolean existsByTutorIdAndReason(Long tutorId, CreditReason reason);

	/**
	 * Grants that have lapsed and not yet been through the expiry job.
	 *
	 * <p>"Already processed" is a row in {@code credit_grant_expiries}, not the presence of a
	 * compensating ledger entry. A grant fully spent before it lapsed produces no entry — nothing
	 * moved — so keying off the ledger would return it on every run forever.
	 */
	@Query("""
			SELECT t FROM CreditTransaction t
			WHERE t.amount > 0
			  AND t.expiresAt IS NOT NULL
			  AND t.expiresAt <= :now
			  AND NOT EXISTS (
			      SELECT 1 FROM CreditGrantExpiry e WHERE e.creditTxnId = t.id)
			ORDER BY t.expiresAt ASC
			""")
	List<CreditTransaction> findLapsedGrants(@Param("now") Instant now);

	/** Grants lapsing inside a window, for the warning notification. */
	@Query("""
			SELECT t FROM CreditTransaction t
			WHERE t.amount > 0
			  AND t.expiresAt IS NOT NULL
			  AND t.expiresAt > :now
			  AND t.expiresAt <= :until
			  AND NOT EXISTS (
			      SELECT 1 FROM CreditGrantExpiry e WHERE e.creditTxnId = t.id)
			""")
	List<CreditTransaction> findGrantsLapsingBetween(
			@Param("now") Instant now, @Param("until") Instant until);
}
