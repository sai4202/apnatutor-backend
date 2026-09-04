package com.apnatutor.abuse.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One report — {@code M5-09}. */
@Entity
@Table(name = "abuse_reports")
public class AbuseReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Null only for a {@link ReportSource#SYSTEM} report. */
	@Column(name = "reporter_id")
	private Long reporterId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ReportSource source = ReportSource.USER;

	@Enumerated(EnumType.STRING)
	@Column(name = "subject_type", nullable = false, length = 16)
	private ReportSubjectType subjectType;

	@Column(name = "subject_id", nullable = false)
	private Long subjectId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ReportReason reason;

	@Column(length = 1000)
	private String details;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ReportStatus status = ReportStatus.OPEN;

	@Column(name = "reviewed_by")
	private Long reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "decision_note", length = 1000)
	private String decisionNote;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected AbuseReport() {
		// Required by JPA.
	}

	public static AbuseReport raisedBy(
			Long reporterId,
			ReportSubjectType subjectType,
			Long subjectId,
			ReportReason reason,
			String details) {

		AbuseReport report = new AbuseReport();
		report.reporterId = reporterId;
		report.source = ReportSource.USER;
		report.subjectType = subjectType;
		report.subjectId = subjectId;
		report.reason = reason;
		report.details = details;
		return report;
	}

	/**
	 * A report the platform raised about itself.
	 *
	 * <p>No reporter, and no notification when it is decided: there is nobody to tell.
	 */
	public static AbuseReport raisedBySystem(
			ReportSubjectType subjectType, Long subjectId, ReportReason reason, String details) {

		AbuseReport report = new AbuseReport();
		report.source = ReportSource.SYSTEM;
		report.subjectType = subjectType;
		report.subjectId = subjectId;
		report.reason = reason;
		report.details = details;
		return report;
	}

	/**
	 * Records a decision.
	 *
	 * <p>Deciding is not the same as acting. Upholding a report says a moderator agreed with it;
	 * what they then did — suspend the account, take the enquiry down, unpublish the review — is a
	 * separate action with its own rules and its own audit entry. Keeping them apart is what lets a
	 * report be upheld without anything drastic following automatically.
	 *
	 * @throws IllegalStateException if it has already been decided
	 */
	public void decide(ReportStatus outcome, Long adminUserId, String note, Instant at) {
		if (status != ReportStatus.OPEN) {
			throw new IllegalStateException("This report has already been " + status.name().toLowerCase());
		}
		if (outcome == ReportStatus.OPEN) {
			throw new IllegalArgumentException("A decision must be UPHELD or DISMISSED");
		}

		this.status = outcome;
		this.reviewedBy = adminUserId;
		this.reviewedAt = at;
		this.decisionNote = note;
	}

	public boolean isFromAPerson() {
		return source == ReportSource.USER && reporterId != null;
	}

	public Long getId() {
		return id;
	}

	public Long getReporterId() {
		return reporterId;
	}

	public ReportSource getSource() {
		return source;
	}

	public ReportSubjectType getSubjectType() {
		return subjectType;
	}

	public Long getSubjectId() {
		return subjectId;
	}

	public ReportReason getReason() {
		return reason;
	}

	public String getDetails() {
		return details;
	}

	public ReportStatus getStatus() {
		return status;
	}

	public Long getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public String getDecisionNote() {
		return decisionNote;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
