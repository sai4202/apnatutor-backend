package com.apnatutor.notification;

import com.apnatutor.auth.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development stub: prints the SMS to the log instead of sending it.
 *
 * <p>Lets the whole OTP flow be exercised locally with no provider account and no cost. The
 * recipient is masked but the message — including the code — is printed in full, which is the entire
 * point: you need to read the code to log in.
 *
 * <p><strong>Never enable this in production.</strong> {@code M6-10.5} exists specifically to verify
 * that no console stub survives into the production profile.
 */
@Component
@ConditionalOnProperty(name = "apnatutor.sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleSmsSender.class);

	@Override
	public void send(String phone, String message) {
		log.info("""

				  ┌─────────────────────────────────────────────
				  │ SMS (dev stub — not actually sent)
				  │ To : {}
				  │ Msg: {}
				  └─────────────────────────────────────────────""",
				PhoneNumbers.mask(phone), message);
	}
}
