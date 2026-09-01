package com.apnatutor.notification;

import java.time.Clock;
import java.util.List;

import com.apnatutor.notification.domain.Notification;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records and delivers notifications.
 *
 * <h2>Delivery happens after the transaction commits, never inside it</h2>
 *
 * <p>This is the important design point. Sending an SMS inside the unlock transaction would mean a
 * provider timeout rolls back a <em>paid unlock</em> — the tutor's credits come back, but so does
 * the lead, and the two are no longer in step with whatever the SMS gateway actually did. Worse,
 * holding a database transaction open across a network call to a third party is how a slow provider
 * becomes a database outage.
 *
 * <p>So the notification row is written in the caller's transaction, and delivery is triggered by
 * {@link TransactionalEventListener} once that transaction has committed. If delivery then fails,
 * the record still exists and can be retried; the unlock is untouched either way.
 */
@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notifications;
	private final UserRepository users;
	private final SmsSender smsSender;
	private final MailSender mailSender;
	private final Clock clock;

	public NotificationService(
			NotificationRepository notifications,
			UserRepository users,
			SmsSender smsSender,
			MailSender mailSender,
			Clock clock) {
		this.notifications = notifications;
		this.users = users;
		this.smsSender = smsSender;
		this.mailSender = mailSender;
		this.clock = clock;
	}

	/** Raised after a notification is persisted; consumed once the transaction commits. */
	public record NotificationCreated(Long notificationId, boolean sendSms) {
	}

	/**
	 * Records a notification. Delivery follows once the caller's transaction commits.
	 *
	 * <p>Joins the caller's transaction deliberately: if the unlock rolls back, the "a tutor
	 * responded" notification must roll back with it. Telling a parent about a response that did
	 * not happen is worse than telling them nothing.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public Notification notify(
			Long userId,
			NotificationType type,
			String title,
			String body,
			String referenceType,
			Long referenceId) {

		return notifications.save(
				Notification.of(userId, type, title, body, referenceType, referenceId));
	}

	/**
	 * Delivers a recorded notification.
	 *
	 * <p>Runs in its own transaction after the original committed. Failures are logged and
	 * swallowed: a bounced email must not fail the request that caused it, and the row remains for
	 * a retry.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deliver(Long notificationId, boolean sendSms) {
		notifications.findById(notificationId).ifPresent(notification -> {
			User user = users.findById(notification.getUserId()).orElse(null);
			if (user == null) {
				return;
			}

			StringBuilder channels = new StringBuilder();

			try {
				if (sendSms) {
					smsSender.send(user.getPhone(), notification.getBody());
					channels.append("SMS");
				}

				if (user.getEmail() != null && !user.getEmail().isBlank()) {
					mailSender.send(user.getEmail(), notification.getTitle(), notification.getBody());
					channels.append(channels.isEmpty() ? "EMAIL" : ",EMAIL");
				}

				notification.recordDelivery(channels.toString());
				notifications.save(notification);
			} catch (RuntimeException e) {
				// Swallowed on purpose. A provider outage must not fail the unlock that has
				// already committed, and the row survives for a retry.
				log.warn("Could not deliver notification {}: {}",
						notificationId, e.getMessage());
			}
		});
	}

	@Transactional(readOnly = true)
	public List<Notification> forUser(Long userId) {
		return notifications.findByUserIdOrderByCreatedAtDesc(userId);
	}

	@Transactional(readOnly = true)
	public long unreadCount(Long userId) {
		return notifications.countByUserIdAndReadAtIsNull(userId);
	}

	@Transactional
	public void markAllRead(Long userId) {
		notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.filter(notification -> notification.getReadAt() == null)
				.forEach(notification -> {
					notification.markRead(clock.instant());
					notifications.save(notification);
				});
	}
}
