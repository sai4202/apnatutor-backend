package com.apnatutor.search;

/**
 * Masks contact details for display before a lead is unlocked.
 *
 * <p><strong>Contact details are the product.</strong> They are the thing tutors spend credits to
 * unlock (SOURCE_OF_TRUTH.md §1). A public response that leaks a phone number does not degrade the
 * business model, it removes it — a tutor who can read a student's number from search has no reason
 * ever to pay.
 *
 * <p>One implementation, used everywhere, so there is no second copy to fall out of step. The
 * primary defence is still structural: public DTOs simply have no contact fields on them, so there
 * is no code path that could leak one. This exists for the M3 lead feed, where a masked form is
 * shown deliberately — "+91XXXXXX3210" tells a tutor the enquiry is real without telling them who
 * it is.
 */
public final class ContactMasking {

	private ContactMasking() {
	}

	/**
	 * {@code +919876543210} to {@code +91XXXXXX3210}.
	 *
	 * <p>The last four digits are kept so a student can recognise their own number when a tutor
	 * quotes it back, and so support can match a complaint to a record. Six digits hidden leaves a
	 * million possibilities — not brute-forceable by hand, and the platform rate-limits anyway.
	 */
	public static String maskPhone(String canonicalPhone) {
		if (canonicalPhone == null || canonicalPhone.length() < 4) {
			return "***";
		}
		return "+91XXXXXX" + canonicalPhone.substring(canonicalPhone.length() - 4);
	}

	/**
	 * {@code Priya Sharma} to {@code Priya S.}
	 *
	 * <p>Enough for a tutor to greet someone naturally once unlocked, not enough to find them
	 * elsewhere. A full name plus a locality is often sufficient to identify a person.
	 */
	public static String maskName(String fullName) {
		if (fullName == null || fullName.isBlank()) {
			return "Student";
		}

		String[] parts = fullName.trim().split("\\s+");
		if (parts.length == 1) {
			return parts[0];
		}
		return parts[0] + " " + Character.toUpperCase(parts[1].charAt(0)) + ".";
	}

	/**
	 * Masks an email as {@code p****a@example.com}.
	 *
	 * <p>The domain is kept — it is rarely identifying on its own and helps a tutor judge whether an
	 * enquiry looks genuine.
	 */
	public static String maskEmail(String email) {
		if (email == null || !email.contains("@")) {
			return "***";
		}

		int at = email.indexOf('@');
		String local = email.substring(0, at);
		String domain = email.substring(at);

		if (local.length() <= 2) {
			return "*".repeat(Math.max(local.length(), 1)) + domain;
		}
		return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1)
				+ domain;
	}
}
