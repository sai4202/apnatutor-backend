package com.apnatutor.billing;

import java.util.Optional;

import com.apnatutor.billing.domain.CreditWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditWalletRepository extends JpaRepository<CreditWallet, Long> {

	Optional<CreditWallet> findByTutorId(Long tutorId);

	/**
	 * Loads the wallet with a {@code SELECT ... FOR UPDATE} row lock.
	 *
	 * <p>Used only when spending. Two unlocks racing on the same wallet would otherwise each read
	 * the same balance and each write a debit against it, handing the tutor two leads for the price
	 * of one. The lock serialises them, so the second attempt sees the first's result and either
	 * succeeds against the reduced balance or is correctly rejected.
	 *
	 * <p>Held only for the length of the enclosing transaction, which is a handful of statements.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT w FROM CreditWallet w WHERE w.tutorId = :tutorId")
	Optional<CreditWallet> findByTutorIdForUpdate(@Param("tutorId") Long tutorId);
}
