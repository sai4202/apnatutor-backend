package com.apnatutor.abuse.domain;

/** Who raised a report. */
public enum ReportSource {

	USER,

	/**
	 * The platform raised it about itself.
	 *
	 * <p>Kept distinct so the count a moderator reads stays meaningful: "four people reported this
	 * tutor" and "four people reported this tutor, three of which were us" are different facts.
	 */
	SYSTEM
}
