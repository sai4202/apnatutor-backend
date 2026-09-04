package com.apnatutor.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The things somebody should be woken up for — {@code M6-08.4}.
 *
 * <h2>Why this exists rather than another {@code log.error}</h2>
 *
 * <p>Everything in this application already logs its failures, and that is the problem: a log full
 * of errors has no signal left in it. These are the few events where the correct response is a
 * person looking within the hour, and giving them a distinct call site with a distinct marker is
 * what lets an alerting rule find them without matching on message text that will be reworded.
 *
 * <p>Every alert is emitted under the marker {@code ALERT} and a stable {@code kind}. An alerting
 * rule matches on those two, not on the sentence.
 *
 * <h2>It logs, for now</h2>
 *
 * <p>There is no pager here and no third-party client, because choosing one is a decision with a
 * bill attached that has not been made (PENDING D8). What this class does is make the switch a
 * one-file change: today every alert goes to the log at ERROR with a greppable marker, and the day
 * a provider is chosen, it is added here and every existing call site starts paging.
 *
 * <p>The alternative — scattering a provider's SDK through the payment and webhook code now —
 * would mean choosing the provider before knowing what needs alerting, and undoing it in a dozen
 * files if the choice turned out wrong.
 */
@Component
public class Alerts {

	private static final Logger log = LoggerFactory.getLogger(Alerts.class);

	/** What happened. Stable strings: an alerting rule matches these, so renaming one breaks it. */
	public enum Kind {

		/**
		 * A webhook arrived with a signature that did not verify.
		 *
		 * <p>One is a misconfiguration. A run of them is somebody probing an endpoint that grants
		 * credits, and the HMAC is the only thing between them and free ones.
		 */
		WEBHOOK_SIGNATURE_REJECTED,

		/**
		 * A verified webhook could not be processed.
		 *
		 * <p>The most expensive failure available here: the money reached Razorpay and the credits
		 * did not reach the tutor. Every minute this goes unnoticed is a tutor who paid and got
		 * nothing, and they will not always tell us.
		 */
		WEBHOOK_PROCESSING_FAILED,

		/**
		 * The credit ledger disagrees with a cached wallet balance.
		 *
		 * <p>Should be impossible. If it happens, credits have been created or destroyed outside
		 * the ledger, and nothing downstream can be trusted until somebody understands why.
		 */
		LEDGER_MISMATCH,

		/** A payment was captured but the credits were never granted. */
		PAYMENT_UNCREDITED
	}

	/**
	 * Raises an alert.
	 *
	 * <p>Never throws, and callers must not handle it. This is called from failure paths, and an
	 * alerting mechanism that can itself fail the request it is reporting on is worse than none.
	 *
	 * @param context short, structured, and safe to put in a third-party system — ids and counts,
	 *     never a phone number, a document key or a raw webhook body
	 */
	public void raise(Kind kind, String context) {
		try {
			log.error("ALERT kind={} — {}", kind, context);
		} catch (RuntimeException e) {
			// Deliberately swallowed. See the javadoc.
		}
	}
}
