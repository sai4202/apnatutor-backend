package com.apnatutor.requirement;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.apnatutor.catalog.BoardRepository;
import com.apnatutor.catalog.GradeLevelRepository;
import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.domain.Board;
import com.apnatutor.catalog.domain.GradeLevel;
import com.apnatutor.catalog.domain.Location;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.lead.domain.UnlockStatus;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.dto.RequirementDtos.LeadPreview;
import com.apnatutor.requirement.dto.RequirementDtos.RespondingTutor;
import com.apnatutor.requirement.dto.RequirementDtos.StudentView;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns requirements into the right view for whoever is asking.
 *
 * <p>The distinction this class exists to enforce: a {@link LeadPreview} names an <em>area</em> and
 * never a person, while a {@link StudentView} may reveal the phone numbers of tutors who have paid
 * to reach that student. Getting these the wrong way round is the single most damaging bug
 * available in this product, so the mapping lives in one place rather than being repeated at each
 * call site.
 */
@Component
public class RequirementViewMapper {

	private final SubjectRepository subjects;
	private final GradeLevelRepository gradeLevels;
	private final BoardRepository boards;
	private final LocationRepository locations;
	private final LeadUnlockRepository unlocks;
	private final UserRepository users;
	private final TutorProfileRepository tutorProfiles;

	public RequirementViewMapper(
			SubjectRepository subjects,
			GradeLevelRepository gradeLevels,
			BoardRepository boards,
			LocationRepository locations,
			LeadUnlockRepository unlocks,
			UserRepository users,
			TutorProfileRepository tutorProfiles) {
		this.subjects = subjects;
		this.gradeLevels = gradeLevels;
		this.boards = boards;
		this.locations = locations;
		this.unlocks = unlocks;
		this.users = users;
		this.tutorProfiles = tutorProfiles;
	}

	/** Catalog lookups, loaded once for a whole list rather than per row. */
	public record Catalog(
			Map<Long, Subject> subjects,
			Map<Long, GradeLevel> grades,
			Map<Long, Board> boards,
			Map<Long, Location> locations) {

		public String subjectName(Long id) {
			Subject subject = subjects.get(id);
			return subject == null ? null : subject.getName();
		}

		public String gradeName(Long id) {
			GradeLevel grade = grades.get(id);
			return grade == null ? null : grade.getName();
		}

		public String boardName(Long id) {
			Board board = boards.get(id);
			return board == null ? null : board.getName();
		}

		public String locationName(Long id) {
			Location location = locations.get(id);
			return location == null ? null : location.displayName();
		}
	}

	public Catalog loadCatalog() {
		return new Catalog(
				subjects.findAll().stream().collect(Collectors.toMap(Subject::getId, Function.identity())),
				gradeLevels.findAll().stream().collect(Collectors.toMap(GradeLevel::getId, Function.identity())),
				boards.findAll().stream().collect(Collectors.toMap(Board::getId, Function.identity())),
				locations.findAll().stream().collect(Collectors.toMap(Location::getId, Function.identity())));
	}

	/**
	 * The masked preview a tutor sees before paying.
	 *
	 * <p>Carries no student name and no phone. The location is deliberately the whole locality
	 * string — "Gachibowli, Hyderabad" — never anything narrower, because an exact address plus a
	 * child's class and timings is enough to find a specific family.
	 */
	@Transactional(readOnly = true)
	public LeadPreview toPreview(Requirement requirement, Catalog catalog) {
		return LeadPreview.from(
				requirement,
				catalog.subjectName(requirement.getSubjectId()),
				catalog.gradeName(requirement.getGradeLevelId()),
				catalog.boardName(requirement.getBoardId()),
				catalog.locationName(requirement.getLocationId()));
	}

	/**
	 * The student's own view, including who has responded.
	 *
	 * <p>Responding tutors' phone numbers <em>are</em> revealed here. That is not a leak: the tutor
	 * chose to spend credits specifically to start this conversation, and a student who cannot call
	 * back has been sold nothing.
	 */
	@Transactional(readOnly = true)
	public StudentView toStudentView(Requirement requirement, Catalog catalog) {
		List<LeadUnlock> responses =
				unlocks.findByRequirementIdAndStatus(requirement.getId(), UnlockStatus.ACTIVE);

		List<RespondingTutor> tutors = responses.stream()
				.map(unlock -> {
					User tutorUser = users.findById(unlock.getTutorId()).orElse(null);
					TutorProfile profile = tutorProfiles.findByUserId(unlock.getTutorId()).orElse(null);
					return new RespondingTutor(
							profile == null ? null : profile.getId(),
							profile == null ? "Tutor" : profile.getDisplayName(),
							profile == null ? null : profile.getHeadline(),
							tutorUser == null ? null : tutorUser.getPhone(),
							unlock.getIntroMessage(),
							unlock.getUnlockedAt());
				})
				.toList();

		return new StudentView(
				requirement.getId(),
				catalog.subjectName(requirement.getSubjectId()),
				catalog.gradeName(requirement.getGradeLevelId()),
				catalog.boardName(requirement.getBoardId()),
				catalog.locationName(requirement.getLocationId()),
				requirement.getMode(),
				requirement.getBudgetAmountPaise(),
				requirement.getBudgetUnit(),
				requirement.getFrequency(),
				requirement.getPreferredTiming(),
				requirement.getGenderPreference(),
				requirement.getDescription(),
				requirement.getStatus(),
				requirement.getUnlockCount(),
				requirement.remainingSlots(),
				requirement.getExpiresAt(),
				requirement.getCreatedAt(),
				tutors);
	}
}
