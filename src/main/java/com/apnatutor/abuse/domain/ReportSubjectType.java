package com.apnatutor.abuse.domain;

/**
 * What is being reported.
 *
 * <p>{@code TUTOR} and {@code STUDENT} both address a {@code users.id}. They are kept apart anyway,
 * because the two reports mean different things and lead to different checks — a reported tutor is
 * about a profile and a paid relationship, a reported student is about an enquiry. Collapsing them
 * into {@code USER} would make the queue unreadable at a glance.
 */
public enum ReportSubjectType {
	TUTOR,
	STUDENT,
	REVIEW,
	REQUIREMENT
}
