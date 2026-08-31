package com.apnatutor.notification;

/**
 * Sends an SMS.
 *
 * <p>An interface from day one so the OTP flow can be built, tested and demoed without an SMS
 * account, and so swapping providers later is a new class rather than an edit to the auth code.
 * Which provider to use in production is still an open decision (PENDING.md D5) — per-message cost
 * in India varies severalfold, and OTP volume is the largest per-user variable cost in this
 * product.
 */
public interface SmsSender {

	/**
	 * @param phone canonical E.164 recipient
	 * @param message body to send
	 */
	void send(String phone, String message);
}
