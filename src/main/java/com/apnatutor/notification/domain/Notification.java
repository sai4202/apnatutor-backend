package com.apnatutor.notification.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A message sent to a user, kept so "did you tell me about this?" has an answer. */
@Entity
@Table(name = "notifications")
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private NotificationType type;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 1000)
	private String body;

	@Column(name = "reference_type", length = 32)
	private String referenceType;

	@Column(name = "reference_id")
	private Long referenceId;

	@Column(name = "sent_channels", length = 60)
	private String sentChannels;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected Notification() {
		// Required by JPA.
	}

	public static Notification of(
			Long userId,
			NotificationType type,
			String title,
			String body,
			String referenceType,
			Long referenceId) {

		Notification notification = new Notification();
		notification.userId = userId;
		notification.type = type;
		notification.title = title;
		notification.body = body;
		notification.referenceType = referenceType;
		notification.referenceId = referenceId;
		return notification;
	}

	public void recordDelivery(String channels) {
		this.sentChannels = channels;
	}

	public void markRead(Instant at) {
		if (readAt == null) {
			this.readAt = at;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public NotificationType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public Long getReferenceId() {
		return referenceId;
	}

	public String getSentChannels() {
		return sentChannels;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
