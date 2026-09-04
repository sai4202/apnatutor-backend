package com.apnatutor.abuse.dto;

import java.time.Instant;

import com.apnatutor.abuse.domain.AbuseReport;
import com.apnatutor.abuse.domain.ReportReason;
import com.apnatutor.abuse.domain.ReportSource;
import com.apnatutor.abuse.domain.ReportStatus;
import com.apnatutor.abuse.domain.ReportSubjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Report shapes — {@code M5-09}. */
public final class AbuseDtos {

	private AbuseDtos() {
	}

	@Schema(description = "Report a tutor, student, review or enquiry")
	public record ReportRequest(
			@NotNull(message = "Say what you are reporting") ReportSubjectType subjectType,
			@Schema(description = "For a tutor this is the profile id, as everywhere else")
			@NotNull(message = "Say which one") Long subjectId,
			@NotNull(message = "Choose a reason") ReportReason reason,
			@Size(max = 1000, message = "Keep it under 1000 characters") String details) {
	}

	@Schema(description = "A decision on a report")
	public record DecisionRequest(
			@Schema(description = "Sent to whoever reported it, upheld or not")
			@Size(max = 1000) String note) {
	}

	/**
	 * What the reporter sees back.
	 *
	 * <p>Carries no detail about the subject beyond what they already sent. A reporter learning
	 * anything new about the account they reported turns the report button into a lookup tool.
	 */
	@Schema(description = "A report you filed")
	public record ReportView(
			Long id,
			ReportSubjectType subjectType,
			Long subjectId,
			ReportReason reason,
			ReportStatus status,
			Instant filedAt,
			Instant reviewedAt,
			@Schema(description = "The moderator's note, once decided")
			String outcome) {

		public static ReportView from(AbuseReport report) {
			return new ReportView(
					report.getId(),
					report.getSubjectType(),
					report.getSubjectId(),
					report.getReason(),
					report.getStatus(),
					report.getCreatedAt(),
					report.getReviewedAt(),
					report.getDecisionNote());
		}
	}

	/**
	 * The moderator's view.
	 *
	 * <p>{@code distinctReporters} is the number that decides most of these. One person filing
	 * repeatedly is one opinion; five people independently reaching for the report button about the
	 * same tutor is a fact about that tutor — the same reasoning as the disputed-enquiry queue.
	 */
	@Schema(description = "A report, with the context needed to decide it")
	public record TriageView(
			Long id,
			ReportSource source,
			Long reporterId,
			ReportSubjectType subjectType,
			Long subjectId,
			ReportReason reason,
			String details,
			ReportStatus status,
			@Schema(description = "How many different people have reported this same subject")
			long distinctReporters,
			@Schema(description = "How many reports of any kind exist against this subject")
			int totalReports,
			Instant filedAt,
			Long reviewedBy,
			Instant reviewedAt,
			String decisionNote) {

		public static TriageView from(AbuseReport report, long distinctReporters, int totalReports) {
			return new TriageView(
					report.getId(),
					report.getSource(),
					report.getReporterId(),
					report.getSubjectType(),
					report.getSubjectId(),
					report.getReason(),
					report.getDetails(),
					report.getStatus(),
					distinctReporters,
					totalReports,
					report.getCreatedAt(),
					report.getReviewedBy(),
					report.getReviewedAt(),
					report.getDecisionNote());
		}
	}
}
