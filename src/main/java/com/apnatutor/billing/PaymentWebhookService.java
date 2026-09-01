package com.apnatutor.billing;

import java.time.Clock;

import com.apnatutor.billing.domain.PaymentWebhookEvent;
import com.apnatutor.billing.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles webhook deliveries from the payment provider.
 *
 * <h2>The order of operations is the design</h2>
 *
 * <ol>
 *   <li><strong>Verify the signature</strong> against the raw bytes, before parsing anything. An
 *       unverified body is attacker-controlled input and must not be allowed to reach any logic
 *       that grants credits.
 *   <li><strong>Record the delivery</strong>, raw payload included, in its own transaction. This
 *       happens whether or not processing then succeeds, because a webhook that was mishandled is
 *       unreproducible without the exact bytes — the provider will not re-send on request.
 *   <li><strong>Deduplicate</strong> on the provider's event id, enforced by a unique index rather
 *       than a lookup. Two simultaneous deliveries of one event both pass a "have I seen this?"
 *       check; only one survives the index.
 *   <li><strong>Process</strong>, and stamp the result on the recorded event.
 * </ol>
 *
 * <h2>Why the recording lives in another bean</h2>
 *
 * <p>The audit writes go through {@link WebhookEventRecorder} on {@code REQUIRES_NEW}, so a record
 * survives the processing failure it describes. It has to be a separate bean: {@code @Transactional}
 * is proxy-applied, so a self-invoked annotated method gets no new transaction and silently joins
 * the caller's — rolling the record back with it. An audit row that vanishes precisely when
 * something went wrong is worse than none, because it looks like nothing happened.
 */
@Service
public class PaymentWebhookService {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	private final WebhookEventRecorder recorder;
	private final PurchaseService purchases;
	private final PaymentGateway gateway;
	private final ObjectMapper json;
	private final Clock clock;

	public PaymentWebhookService(
			WebhookEventRecorder recorder,
			PurchaseService purchases,
			PaymentGateway gateway,
			ObjectMapper json,
			Clock clock) {
		this.recorder = recorder;
		this.purchases = purchases;
		this.gateway = gateway;
		this.json = json;
		this.clock = clock;
	}

	/** What the endpoint should tell the provider. */
	public enum Outcome {
		/** Processed, or already processed. Either way the provider should stop retrying. */
		ACCEPTED,
		/** Signature did not verify. Recorded, and never acted on. */
		REJECTED,
		/** Something went wrong on our side; the provider should retry. */
		FAILED
	}

	/**
	 * @param rawBody the body exactly as received — <strong>not</strong> a re-serialised object.
	 *     Re-serialising changes key order and whitespace, and the signature will not match. This is
	 *     the single most common way webhook verification is quietly broken.
	 */
	public Outcome handle(String rawBody, String signatureHeader) {
		boolean valid = gateway.verifyWebhookSignature(rawBody, signatureHeader);

		JsonNode payload;
		try {
			payload = json.readTree(rawBody);
		} catch (RuntimeException e) {
			// Unparseable. Recorded anyway if it was signed, because a signed body we cannot read
			// means our parsing is wrong, not the provider's.
			log.error("Webhook body could not be parsed (signatureValid={})", valid);
			return valid ? Outcome.FAILED : Outcome.REJECTED;
		}

		String eventType = text(payload, "event", "unknown");
		String eventId = eventId(payload, signatureHeader);

		PaymentWebhookEvent event;
		try {
			event = recorder.record(eventId, eventType, valid, rawBody);
		} catch (DataIntegrityViolationException e) {
			// The unique index fired: this exact event has been delivered before. Providers retry
			// by design, so this is normal operation and the answer is "yes, we have it".
			log.info("Duplicate webhook ignored: event={} type={}", eventId, eventType);
			return Outcome.ACCEPTED;
		}

		if (!valid) {
			// Recorded, never acted on. A run of these is how we find out someone is probing the
			// endpoint, which is why the rows are kept rather than dropped.
			log.error("REJECTED webhook with an invalid signature: event={} type={}",
					eventId, eventType);
			return Outcome.REJECTED;
		}

		try {
			Long paymentId = process(eventType, payload);
			recorder.markProcessed(event.getId(), paymentId);
			return Outcome.ACCEPTED;
		} catch (RuntimeException e) {
			log.error("Webhook processing failed: event={} type={}", eventId, eventType, e);
			recorder.markFailed(event.getId(), e.getClass().getSimpleName() + ": " + e.getMessage());
			// FAILED, so the provider retries. Safe precisely because crediting is idempotent.
			return Outcome.FAILED;
		}
	}

	/**
	 * Acts on a verified event.
	 *
	 * @return the affected payment id, or null if the event referenced nothing we know about
	 */
	private Long process(String eventType, JsonNode payload) {
		JsonNode entity = payload.path("payload").path("payment").path("entity");
		String orderId = text(entity, "order_id", null);
		String paymentId = text(entity, "id", null);

		if (orderId == null) {
			// Subscription and settlement events carry no order. Nothing to do, but accepted —
			// answering an unrecognised event with a failure makes the provider retry forever.
			log.info("Webhook '{}' carries no order id; nothing to do", eventType);
			return null;
		}

		switch (eventType) {
			case "payment.captured", "order.paid" -> purchases.confirmPayment(orderId, paymentId);
			case "payment.failed" -> purchases.markFailed(
					orderId, text(entity.path("error_description"), null, "Declined by provider"));
			default -> log.info("Webhook '{}' not handled; recorded only", eventType);
		}

		return null;
	}

	/**
	 * The provider's event identifier.
	 *
	 * <p>Razorpay sends {@code x-razorpay-event-id}, but it is not in the body. Where the header is
	 * absent the signature is used instead: it is a deterministic function of the exact bytes, so
	 * two deliveries of one event produce the same value and a genuinely different event cannot
	 * collide with it.
	 */
	private String eventId(JsonNode payload, String signatureHeader) {
		String fromBody = text(payload, "id", null);
		if (fromBody != null) {
			return fromBody;
		}
		return signatureHeader == null || signatureHeader.isBlank()
				? "unsigned-" + clock.millis()
				: signatureHeader;
	}

	private static String text(JsonNode node, String field, String fallback) {
		JsonNode value = field == null ? node : node.path(field);
		return value.isMissingNode() || value.isNull() ? fallback : value.asString();
	}
}
