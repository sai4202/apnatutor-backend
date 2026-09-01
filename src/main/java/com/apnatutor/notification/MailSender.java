package com.apnatutor.notification;

/**
 * Sends an email.
 *
 * <p>An interface with a console stub, exactly as {@link SmsSender} is, so notification flows can be
 * built and tested without a provider account. Which provider to use in production is `M6-10.2`.
 */
public interface MailSender {

	/**
	 * @param to recipient address
	 * @param subject line
	 * @param body plain text
	 */
	void send(String to, String subject, String body);
}
