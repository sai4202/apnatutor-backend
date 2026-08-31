package com.apnatutor.auth;

import java.util.Optional;

/**
 * Normalises Indian mobile numbers to canonical E.164.
 *
 * <p>People type the same number in many shapes — {@code 9876543210}, {@code 98765 43210}, {@code
 * 098765-43210}, {@code +91 9876543210}, {@code 919876543210}. Every one of those is the same
 * person. Storing them verbatim would let one human hold several accounts, split their reviews and
 * credits across them, and defeat the OTP send-rate limit by varying the formatting.
 *
 * <p>So normalisation happens once, at the edge, and only the canonical form is ever stored or
 * rate-limited against.
 *
 * <p>Deliberately India-only for v1, matching the market (ADR: India-first). Supporting other
 * countries means a real library such as libphonenumber, not more regexes.
 */
public final class PhoneNumbers {

	private static final String INDIA_CC = "91";

	/** Indian mobile numbers are 10 digits and begin 6-9. Landlines are not accepted. */
	private static final String NSN_PATTERN = "[6-9][0-9]{9}";

	private PhoneNumbers() {
	}

	/**
	 * Returns the canonical {@code +91XXXXXXXXXX} form, or empty if this is not a valid Indian
	 * mobile number.
	 *
	 * <p>Returns {@code Optional} rather than throwing: invalid input here is an ordinary user typo,
	 * not an exceptional condition, and the caller turns it into a field-level validation message.
	 */
	public static Optional<String> normalise(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}

		// Strip everything humans add for readability: spaces, dashes, brackets, dots, and a
		// leading + which we re-add ourselves.
		String digits = raw.replaceAll("[^0-9]", "");

		// 0XXXXXXXXXX - the trunk prefix used when dialling domestically.
		if (digits.length() == 11 && digits.startsWith("0")) {
			digits = digits.substring(1);
		}

		// 91XXXXXXXXXX - country code with no plus.
		if (digits.length() == 12 && digits.startsWith(INDIA_CC)) {
			digits = digits.substring(INDIA_CC.length());
		}

		if (!digits.matches(NSN_PATTERN)) {
			return Optional.empty();
		}

		return Optional.of("+" + INDIA_CC + digits);
	}

	/**
	 * Masks a canonical number for display and logging: {@code +919876543210} to {@code
	 * +91XXXXXX3210}.
	 *
	 * <p>Use this anywhere a phone number would otherwise reach a log file. Contact details are the
	 * product — they are what tutors pay for — so they should not sit in plaintext in logs that get
	 * shipped to a third-party aggregator.
	 */
	public static String mask(String canonical) {
		if (canonical == null || canonical.length() < 4) {
			return "***";
		}
		String lastFour = canonical.substring(canonical.length() - 4);
		return "+" + INDIA_CC + "XXXXXX" + lastFour;
	}
}
