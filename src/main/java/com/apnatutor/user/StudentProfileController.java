package com.apnatutor.user;

import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.domain.Location;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.domain.StudentProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student or parent's own profile.
 *
 * <p>Small enough that the controller holds the logic directly rather than delegating to a service
 * that would only forward two calls. If this grows past reading and writing two fields, it should be
 * split out.
 *
 * <p>Like the tutor endpoints, there is no route that takes a profile id — everything acts on the
 * token's own user, so ownership is structural rather than a check that could be omitted.
 */
@RestController
@RequestMapping("/api/v1/student/profile")
@PreAuthorize("hasRole('STUDENT')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Student profile", description = "The student or parent's own details")
public class StudentProfileController {

	@Schema(description = "Student or parent details. Both fields optional.")
	public record UpdateRequest(
			@Schema(example = "Priya Sharma") @Size(max = 120) String name,
			@Schema(description = "City or locality from the catalog") Long locationId) {
	}

	@Schema(description = "The student's own profile")
	public record ProfileView(Long id, String name, Long locationId, String locationName) {
	}

	private final StudentProfileRepository profiles;
	private final LocationRepository locations;

	public StudentProfileController(
			StudentProfileRepository profiles, LocationRepository locations) {
		this.profiles = profiles;
		this.locations = locations;
	}

	@GetMapping
	@Transactional
	@Operation(
			summary = "My profile",
			description = "Created empty on first call. Nothing here is required before posting a "
					+ "requirement — the requirement itself carries subject, budget and area.")
	public ResponseEntity<ProfileView> myProfile(CurrentUser currentUser) {
		return ResponseEntity.ok(toView(getOrCreate(currentUser.userId())));
	}

	@PutMapping
	@Transactional
	@Operation(summary = "Update my name and location")
	public ResponseEntity<ProfileView> update(
			CurrentUser currentUser, @Valid @RequestBody UpdateRequest request) {

		StudentProfile profile = getOrCreate(currentUser.userId());

		// Validated against the catalog rather than accepted blindly: an arbitrary id would
		// surface later as a profile pointing at a location that does not exist.
		if (request.locationId() != null) {
			locations.findById(request.locationId()).orElseThrow(
					() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown location: " + request.locationId()));
		}

		profile.update(request.name(), request.locationId());
		return ResponseEntity.ok(toView(profiles.save(profile)));
	}

	/**
	 * Created lazily rather than at registration, so signing up stays a single OTP step and a parent
	 * can go straight to posting what they need.
	 */
	private StudentProfile getOrCreate(Long userId) {
		return profiles.findByUserId(userId)
				.orElseGet(() -> profiles.save(StudentProfile.createFor(userId)));
	}

	private ProfileView toView(StudentProfile profile) {
		String locationName = profile.getLocationId() == null
				? null
				: locations.findById(profile.getLocationId())
						.map(Location::displayName)
						.orElse(null);

		return new ProfileView(
				profile.getId(), profile.getName(), profile.getLocationId(), locationName);
	}
}
