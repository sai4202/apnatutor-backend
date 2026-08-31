package com.apnatutor.auth.domain;

/**
 * What an OTP is for.
 *
 * <p>One value today. It exists as an enum rather than being implicit because later flows —
 * confirming a phone change, or authorising a sensitive action — must not be satisfied by a code
 * issued for plain login. Adding the distinction now costs a column; retrofitting it after codes are
 * interchangeable is a security fix.
 */
public enum OtpPurpose {
	/** Login, and registration on first successful verification. */
	AUTH
}
