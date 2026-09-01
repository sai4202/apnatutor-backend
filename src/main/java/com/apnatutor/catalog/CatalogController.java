package com.apnatutor.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.apnatutor.catalog.domain.Location;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.catalog.dto.CatalogDtos.BoardDto;
import com.apnatutor.catalog.dto.CatalogDtos.CityDto;
import com.apnatutor.catalog.dto.CatalogDtos.GradeLevelDto;
import com.apnatutor.catalog.dto.CatalogDtos.LocationDto;
import com.apnatutor.catalog.dto.CatalogDtos.SubjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public, read-only reference data: subjects, boards, grades and locations.
 *
 * <p>Unauthenticated by design — this is the vocabulary the search page and the tutor onboarding
 * wizard are built from, and it is needed before anyone signs in.
 *
 * <p>Cached aggressively. It changes perhaps monthly, is requested on nearly every page load, and
 * every response is identical for every visitor.
 */
@RestController
@RequestMapping("/api/v1/public/catalog")
@Tag(name = "Catalog", description = "Subjects, boards, grade levels and locations")
public class CatalogController {

	/** Long enough to matter, short enough that a correction reaches users the same day. */
	private static final Duration CACHE_TTL = Duration.ofHours(6);

	private final SubjectRepository subjects;
	private final BoardRepository boards;
	private final GradeLevelRepository gradeLevels;
	private final LocationRepository locations;

	public CatalogController(
			SubjectRepository subjects,
			BoardRepository boards,
			GradeLevelRepository gradeLevels,
			LocationRepository locations) {
		this.subjects = subjects;
		this.boards = boards;
		this.gradeLevels = gradeLevels;
		this.locations = locations;
	}

	@GetMapping("/subjects")
	@Operation(
			summary = "The subject tree",
			description = "Top-level categories, each with its selectable child subjects.")
	public ResponseEntity<List<SubjectNode>> subjectTree() {
		List<Subject> all = subjects.findByActiveTrueOrderByDisplayOrderAsc();

		// Assembled in memory from a single query. The tree is small and read constantly; a mapped
		// JPA association would turn this into an N+1 for no benefit.
		Map<Long, List<Subject>> byParent = all.stream()
				.filter(s -> s.getParentId() != null)
				.collect(Collectors.groupingBy(Subject::getParentId));

		List<SubjectNode> roots = new ArrayList<>();
		all.stream()
				.filter(s -> s.getParentId() == null)
				.sorted(Comparator.comparingInt(Subject::getDisplayOrder))
				.forEach(root -> {
					List<SubjectNode> children = byParent
							.getOrDefault(root.getId(), List.of())
							.stream()
							.sorted(Comparator.comparingInt(Subject::getDisplayOrder))
							.map(c -> new SubjectNode(c.getId(), c.getName(), c.getSlug(), c.isLeaf(), List.of()))
							.toList();
					roots.add(new SubjectNode(root.getId(), root.getName(), root.getSlug(), root.isLeaf(), children));
				});

		return cached(roots);
	}

	@GetMapping("/boards")
	@Operation(summary = "Education boards")
	public ResponseEntity<List<BoardDto>> boards() {
		return cached(boards.findByActiveTrueOrderByDisplayOrderAsc().stream()
				.map(b -> new BoardDto(b.getId(), b.getName(), b.getSlug()))
				.toList());
	}

	@GetMapping("/grade-levels")
	@Operation(summary = "Class and stage levels")
	public ResponseEntity<List<GradeLevelDto>> gradeLevels() {
		return cached(gradeLevels.findByActiveTrueOrderByDisplayOrderAsc().stream()
				.map(g -> new GradeLevelDto(g.getId(), g.getName(), g.getSlug()))
				.toList());
	}

	@GetMapping("/cities")
	@Operation(summary = "Cities we operate in")
	public ResponseEntity<List<CityDto>> cities() {
		return cached(locations.findByCityLevelTrueAndActiveTrueOrderByDisplayOrderAsc().stream()
				.map(l -> new CityDto(l.getId(), l.getCity(), l.getState(), l.getSlug()))
				.toList());
	}

	@GetMapping("/cities/{citySlug}/localities")
	@Operation(summary = "Localities within a city")
	public ResponseEntity<List<LocationDto>> localities(@PathVariable String citySlug) {
		return locations.findBySlugAndActiveTrue(citySlug)
				.map(city -> cached(
						locations.findByCityAndCityLevelFalseAndActiveTrueOrderByDisplayOrderAsc(city.getCity())
								.stream()
								.map(CatalogController::toLocationDto)
								.toList()))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private static LocationDto toLocationDto(Location l) {
		return new LocationDto(l.getId(), l.getLocality(), l.getCity(), l.getSlug(), l.displayName());
	}

	private static <T> ResponseEntity<T> cached(T body) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
				.body(body);
	}
}
