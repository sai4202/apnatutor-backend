package com.apnatutor.user.dto;

import java.time.Instant;

import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin views of an account — {@code M5-05.3}.
 *
 * <p>These carry the phone number in the clear, which is exactly what the rest of the API works to
 * avoid. It is not an oversight: support answers the phone, and the only thing a caller can identify
 * themselves by is the number they are calling about. The endpoints serving these are
 * {@code ADMIN}-only and every call is audit-logged at M5-08.
 *
 * <p>What they still do <strong>not</strong> carry is anyone else's contact details. An admin
 * looking at a tutor sees that tutor's number, not the numbers of the students they unlocked —
 * those were sold, and reselling them through a support screen is the same leak by another route.
 */
public final class AdminUserDtos {

	private AdminUserDtos() {
	}

	@Schema(description = "Why an account is being suspended. Shown to the user.")
	public record SuspendRequest(
			@NotBlank(message = "Say why this account is being suspended")
			@Size(max = 500, message = "Keep the reason under 500 characters")
			String reason) {
	}

	/** One row in the admin user list. Deliberately thin — the detail view is a second call. */
	@Schema(description = "An account, as it appears in the admin list")
	public record UserRow(
			Long id,
			String phone,
			String email,
			UserRole role,
			UserStatus status,
			boolean phoneVerified,
			Instant createdAt,
			Instant lastActiveAt,
			Instant suspendedAt,
			String suspensionReason) {

		public static UserRow from(User user) {
			return new UserRow(
					user.getId(),
					user.getPhone(),
					user.getEmail(),
					user.getRole(),
					user.getStatus(),
					user.getPhoneVerifiedAt() != null,
					user.getCreatedAt(),
					user.getLastActiveAt(),
					user.getSuspendedAt(),
					user.getSuspensionReason());
		}
	}

	/**
	 * An account with the context needed to decide whether to suspend it.
	 *
	 * <p>The counts are the point. "Suspend this tutor" is a different decision for one who has
	 * bought two leads than for one who has bought two hundred and disputed a hundred and ninety of
	 * them, and an admin who has to open four screens to find that out will skip it.
	 */
	@Schema(description = "An account with the activity that explains it")
	public record UserDetail(
			UserRow account,
			@Schema(description = "Which admin suspended this account, if it is suspended")
			Long suspendedBy,
			@Schema(description = "Tutors only: credits currently in the wallet")
			Integer walletBalance,
			@Schema(description = "Tutors only: leads bought and still held")
			Long leadsHeld,
			@Schema(description = "Tutors only: leads they have disputed")
			Long disputesRaised,
			@Schema(description = "Students only: enquiries ever posted")
			Long enquiriesPosted,
			@Schema(description = "Students only: enquiries still live")
			Long enquiriesLive,
			@Schema(description = "Students only: enquiries a moderator has taken down")
			Long enquiriesRemoved) {
	}

	/** The outcome of a suspension, including what it did to the account's live enquiries. */
	@Schema(description = "The result of suspending an account")
	public record SuspensionOutcome(
			UserDetail user,
			@Schema(description = "Live enquiries taken down as a consequence, refunding their "
					+ "tutors. Zero for a tutor account.")
			int enquiriesRemoved) {
	}
}
