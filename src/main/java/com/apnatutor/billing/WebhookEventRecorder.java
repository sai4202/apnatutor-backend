package com.apnatutor.billing;

import java.time.Clock;

import com.apnatutor.billing.domain.PaymentWebhookEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes webhook audit records in their own transaction.
 *
 * <h2>Why this is a separate bean rather than three methods on the service</h2>
 *
 * <p>{@code @Transactional} is applied by a proxy, so a method calling its own annotated method
 * bypasses it entirely — {@code REQUIRES_NEW} on a self-invoked method does nothing at all, silently.
 * The record would then join the caller's transaction and roll back with it, which defeats the whole
 * purpose: the audit row is most valuable precisely when the processing it describes failed.
 *
 * <p>This project has hit that trap twice already (the OTP attempt counter and refresh-token reuse
 * detection), and both were fixed the same way. Sibling classes: {@code OtpAttemptRecorder},
 * {@code TokenFamilyRevoker}.
 */
@Component
public class WebhookEventRecorder {

	private final PaymentWebhookEventRepository events;
	private final Clock clock;

	public WebhookEventRecorder(PaymentWebhookEventRepository events, Clock clock) {
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Records a delivery.
	 *
	 * @throws DataIntegrityViolationException if this event id has already been recorded. That
	 *     unique index — not a prior lookup — is what deduplicates: two simultaneous deliveries of
	 *     one event both pass a "have I seen this?" check, but only one survives the index.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentWebhookEvent record(
			String eventId, String eventType, boolean signatureValid, String rawBody) {

		// saveAndFlush, not save: without the flush the constraint fires at commit time, outside
		// the caller's try/catch, and an ordinary duplicate would surface as a processing failure.
		return events.saveAndFlush(
				PaymentWebhookEvent.received(eventId, eventType, signatureValid, rawBody));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markProcessed(Long eventRowId, Long paymentId) {
		events.findById(eventRowId).ifPresent(event -> {
			event.markProcessed(clock.instant(), paymentId);
			events.save(event);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long eventRowId, String error) {
		events.findById(eventRowId).ifPresent(event -> {
			event.markFailed(clock.instant(), error);
			events.save(event);
		});
	}
}
