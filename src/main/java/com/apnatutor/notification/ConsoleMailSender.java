package com.apnatutor.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development stub: prints the email to the log rather than sending it.
 *
 * <p>Never enable in production — {@code M6-10.5} exists to verify no console stub survives into
 * the production profile.
 */
@Component
@ConditionalOnProperty(name = "apnatutor.mail.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleMailSender.class);

	@Override
	public void send(String to, String subject, String body) {
		log.info("""

				  ┌─────────────────────────────────────────────
				  │ EMAIL (dev stub — not actually sent)
				  │ To      : {}
				  │ Subject : {}
				  │
				  │ {}
				  └─────────────────────────────────────────────""", to, subject, body);
	}
}
