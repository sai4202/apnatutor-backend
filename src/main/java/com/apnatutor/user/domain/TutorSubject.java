package com.apnatutor.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One subject a tutor teaches, with the grades and boards they cover for it.
 *
 * <p>Per-subject rather than per-profile because a tutor may reasonably charge more for Class 12
 * Physics than Class 8 Maths, and may teach one subject only for CBSE.
 */
@Entity
@Table(name = "tutor_subjects")
public class TutorSubject {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
	@JoinColumn(name = "tutor_id", nullable = false)
	private TutorProfile tutor;

	@Column(name = "subject_id", nullable = false)
	private Long subjectId;

	@Column(name = "fee_paise")
	private Long feePaise;

	@Enumerated(EnumType.STRING)
	@Column(name = "fee_unit", length = 16)
	private FeeUnit feeUnit;

	// Arrays rather than join tables: these are short lists read only with their parent row, and
	// two more join tables would add two more joins to the search query.
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "grade_level_ids", columnDefinition = "bigint[]")
	private Long[] gradeLevelIds = new Long[0];

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "board_ids", columnDefinition = "bigint[]")
	private Long[] boardIds = new Long[0];

	protected TutorSubject() {
		// Required by JPA.
	}

	public TutorSubject(
			TutorProfile tutor,
			Long subjectId,
			Long feePaise,
			FeeUnit feeUnit,
			Long[] gradeLevelIds,
			Long[] boardIds) {
		this.tutor = tutor;
		this.subjectId = subjectId;
		this.feePaise = feePaise;
		this.feeUnit = feeUnit;
		this.gradeLevelIds = gradeLevelIds == null ? new Long[0] : gradeLevelIds;
		this.boardIds = boardIds == null ? new Long[0] : boardIds;
	}

	public Long getId() {
		return id;
	}

	public TutorProfile getTutor() {
		return tutor;
	}

	public Long getSubjectId() {
		return subjectId;
	}

	public Long getFeePaise() {
		return feePaise;
	}

	public FeeUnit getFeeUnit() {
		return feeUnit;
	}

	public Long[] getGradeLevelIds() {
		return gradeLevelIds;
	}

	public Long[] getBoardIds() {
		return boardIds;
	}
}
