package com.apnatutor.abuse;

import java.util.List;

import com.apnatutor.abuse.domain.AbuseReport;
import com.apnatutor.abuse.domain.ReportStatus;
import com.apnatutor.abuse.domain.ReportSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

	/** The triage queue. Oldest first: a report left sitting is somebody deciding we do not care. */
	Page<AbuseReport> findByStatusOrderByCreatedAtAsc(ReportStatus status, Pageable pageable);

	Page<AbuseReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

	/** Everything ever reported about one subject — the context for any decision about it. */
	List<AbuseReport> findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(
			ReportSubjectType subjectType, Long subjectId);

	/**
	 * How many separate reporters have complained about one subject.
	 *
	 * <p>{@code COUNT(DISTINCT reporter_id)}, for the same reason the disputed-requirement queue
	 * counts distinct tutors: one person filing repeatedly is one opinion, and five people
	 * independently reaching for the report button is a fact.
	 */
	@org.springframework.data.jpa.repository.Query("""
			SELECT COUNT(DISTINCT r.reporterId) FROM AbuseReport r
			WHERE r.subjectType = :subjectType AND r.subjectId = :subjectId
			  AND r.reporterId IS NOT NULL""")
	long countDistinctReportersFor(
			@org.springframework.data.repository.query.Param("subjectType")
			ReportSubjectType subjectType,
			@org.springframework.data.repository.query.Param("subjectId") Long subjectId);

	boolean existsBySubjectTypeAndSubjectIdAndStatus(
			ReportSubjectType subjectType, Long subjectId, ReportStatus status);

	List<AbuseReport> findByReporterIdOrderByCreatedAtDesc(Long reporterId);
}
