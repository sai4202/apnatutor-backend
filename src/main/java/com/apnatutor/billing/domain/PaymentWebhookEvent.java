package com.apnatutor.billing.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A webhook delivery, recorded before it is acted on.
 *
 * <p>The raw payload is kept deliberately. When a tutor says they paid and our records say they did
 * not, the provider's own signed payload is the only account both sides trust — and a webhook that
 * was mishandled is unreproducible unless the exact bytes were kept, because the provider will not
 * re-send it on request.
 *
 * <p>Rows with {@code signatureValid = false} are kept too. A run of them is the signal that someone
 * is probing the endpoint, and deleting them deletes the evidence.
 */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, length = 120)
	private String eventId;

	@Column(nullable = false, length = 20)
	private String provider = "RAZORPAY";

	@Column(name = "event_type", nullable = false, length = 60)
	private String eventType;

	@Column(name = "payment_id")
	private Long paymentId;

	@Column(name = "signature_valid", nullable = false)
	private boolean signatureValid;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String payload;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "processing_error", length = 500)
	private String processingError;

	@Column(name = "received_at", insertable = false, updatable = false)
	private Instant receivedAt;

	protected PaymentWebhookEvent() {
		// Required by JPA.
	}

	public static PaymentWebhookEvent received(
			String eventId, String eventType, boolean signatureValid, String payload) {
		PaymentWebhookEvent event = new PaymentWebhookEvent();
		event.eventId = eventId;
		event.eventType = eventType;
		event.signatureValid = signatureValid;
		event.payload = payload;
		return event;
	}

	public void markProcessed(Instant now, Long paymentId) {
		this.processedAt = now;
		this.paymentId = paymentId;
	}

	public void markFailed(Instant now, String error) {
		this.processedAt = now;
		this.processingError = error == null || error.length() <= 500
				? error
				: error.substring(0, 500);
	}

	public Long getId() {
		return id;
	}

	public String getEventId() {
		return eventId;
	}

	public String getEventType() {
		return eventType;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	public boolean isSignatureValid() {
		return signatureValid;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public String getProcessingError() {
		return processingError;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}
}
