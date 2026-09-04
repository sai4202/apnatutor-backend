package com.apnatutor.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.CreditTransaction;
import com.apnatutor.billing.domain.CreditWallet;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.observability.Alerts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit ledger — the only way credits ever move.
 *
 * <p>Every method here writes a ledger entry and updates the cached balance <strong>in the same
 * transaction</strong>. Nothing else in the application may touch {@code credit_wallets.balance};
 * the database rejects any attempt to edit the ledger itself.
 *
 * <p>SOURCE_OF_TRUTH.md Invariant 1. The reason this matters beyond tidiness: v2 adds commission,
 * escrow and tutor payouts, and those are new reasons plus a second ledger rather than a migration —
 * but only if the balance was never authoritative in the first place.
 */
@Service
public class CreditLedger {

	private static final Logger log = LoggerFactory.getLogger(CreditLedger.class);

	private final CreditWalletRepository wallets;
	private final CreditTransactionRepository transactions;
	private final Alerts alerts;
	private final Clock clock;

	public CreditLedger(
			CreditWalletRepository wallets,
			CreditTransactionRepository transactions,
			Alerts alerts,
			Clock clock) {
		this.wallets = wallets;
		this.transactions = transactions;
		this.alerts = alerts;
		this.clock = clock;
	}

	/** The wallet, created empty on first access. */
	@Transactional
	public CreditWallet walletFor(Long tutorId) {
		return wallets.findByTutorId(tutorId)
				.orElseGet(() -> wallets.save(CreditWallet.createFor(tutorId)));
	}

	@Transactional(readOnly = true)
	public int balanceOf(Long tutorId) {
		return wallets.findByTutorId(tutorId).map(CreditWallet::getBalance).orElse(0);
	}

	/**
	 * Grants credits.
	 *
	 * @param expiresAt when they lapse; null for credits that do not expire
	 */
	@Transactional
	public CreditTransaction grant(
			Long tutorId,
			int credits,
			CreditReason reason,
			String referenceType,
			Long referenceId,
			Instant expiresAt) {

		if (credits <= 0) {
			throw new IllegalArgumentException("A grant must be positive");
		}

		CreditWallet wallet = walletFor(tutorId);
		wallet.apply(credits);
		wallets.save(wallet);

		CreditTransaction entry = transactions.save(CreditTransaction.of(
				tutorId, credits, reason, referenceType, referenceId, expiresAt,
				wallet.getBalance()));

		log.info("Credits granted: tutor={} amount=+{} reason={} balance={}",
				tutorId, credits, reason, wallet.getBalance());

		return entry;
	}

	/**
	 * Spends credits, with the wallet row locked for the duration.
	 *
	 * <p>The lock is the point. Two unlocks racing on the same wallet would otherwise each read a
	 * balance of 5, each write 0, and hand the tutor two leads for the price of one. Loading the
	 * wallet {@code FOR UPDATE} serialises them, so the second sees the first's result.
	 *
	 * <p>Must run inside the caller's transaction — the debit and the unlock record have to commit
	 * or roll back together, or a tutor is charged for a lead they did not get.
	 *
	 * @throws ApiException with {@link ErrorCode#INSUFFICIENT_CREDITS} if the balance is too low,
	 *     before anything is written
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public CreditTransaction spend(
			Long tutorId, int credits, CreditReason reason, String referenceType, Long referenceId) {

		if (credits <= 0) {
			throw new IllegalArgumentException("A spend must be positive");
		}

		// FOR UPDATE. Everything below runs with this row held.
		CreditWallet wallet = wallets.findByTutorIdForUpdate(tutorId)
				.orElseGet(() -> wallets.save(CreditWallet.createFor(tutorId)));

		if (!wallet.canAfford(credits)) {
			// Checked before any write, so a failed unlock leaves no trace in the ledger. A
			// rejected attempt is not a financial event.
			throw new ApiException(ErrorCode.INSUFFICIENT_CREDITS,
					"You need %d credits for this lead but have %d."
							.formatted(credits, wallet.getBalance()));
		}

		wallet.apply(-credits);
		wallets.save(wallet);

		CreditTransaction entry = transactions.save(CreditTransaction.of(
				tutorId, -credits, reason, referenceType, referenceId, null, wallet.getBalance()));

		log.info("Credits spent: tutor={} amount=-{} reason={} balance={}",
				tutorId, credits, reason, wallet.getBalance());

		return entry;
	}

	@Transactional(readOnly = true)
	public List<CreditTransaction> history(Long tutorId) {
		return transactions.findByTutorIdOrderByCreatedAtDesc(tutorId);
	}

	/**
	 * Replays the ledger and compares it with the cached balance.
	 *
	 * <p>The cache is a performance choice, and every cache is a chance to be wrong. This is how we
	 * find out — run it against every wallet before trusting any revenue number, and treat a
	 * mismatch as a serious bug rather than something to paper over by correcting the cache.
	 *
	 * @return the difference, zero when they agree
	 */
	@Transactional(readOnly = true)
	public int reconcile(Long tutorId) {
		int cached = balanceOf(tutorId);
		Integer summed = transactions.sumAmountFor(tutorId);
		int replayed = summed == null ? 0 : summed;

		if (cached != replayed) {
			// Loud. A silent divergence between a ledger and a balance is how money quietly goes
			// missing, and it does not fix itself.
			log.error("LEDGER MISMATCH tutor={} cachedBalance={} ledgerSum={} difference={}",
					tutorId, cached, replayed, cached - replayed);
			alerts.raise(Alerts.Kind.LEDGER_MISMATCH,
					"tutor=%d cached=%d ledger=%d - credits exist or vanished outside the ledger"
							.formatted(tutorId, cached, replayed));
		}

		return cached - replayed;
	}
}
