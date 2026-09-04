package com.apnatutor.abuse.domain;

/** Where a report stands. */
public enum ReportStatus {

	OPEN,

	/** The moderator agreed. What they then did about it is a separate, audited action. */
	UPHELD,

	/** The moderator found nothing to act on. The reporter is told, with the reason. */
	DISMISSED
}
