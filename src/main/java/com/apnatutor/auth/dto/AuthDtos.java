package com.apnatutor.auth.dto;

import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request and response shapes for the auth endpoints.
 *
 * <p>Records, not entities: entities never leave the service layer. That boundary is what prevents
 * a field being added to {@code User} and silently appearing in a public API response.
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	@Schema(description = "Request a one-time login code by SMS")
	public record OtpRequest(
			@Schema(example = "9876543210", description = "Indian mobile number, any common format")
			@NotBlank(message = "Enter your mobile number")
			String phone) {
	}

	@Schema(description = "Verify a code and sign in, creating the account if it is new")
	public record OtpVerifyRequest(
			@Schema(example = "9876543210")
			@NotBlank(message = "Enter your mobile number")
			String phone,

			@Schema(example = "123456")
			@NotBlank(message = "Enter the code we sent you")
			@Pattern(regexp = "\\d{6}", message = "The code is 6 digits")
			String code,

			@Schema(description = "Required only when creating a new account", example = "STUDENT")
			UserRole role) {
	}

	@Schema(description = "Issued access token and the signed-in user")
	public record AuthResponse(
			@Schema(description = "Bearer token for the Authorization header")
			String accessToken,
			@Schema(description = "Access token lifetime in seconds")
			long expiresIn,
			@Schema(description = "True if this request created the account")
			boolean newAccount,
			UserSummary user) {
	}

	/**
	 * The current user, as the account holder sees themselves.
	 *
	 * <p>The phone is returned in full here — and only here — because it is the viewer's own number.
	 * Any endpoint describing a <em>different</em> user must mask it.
	 */
	@Schema(description = "The signed-in user")
	public record UserSummary(
			Long id,
			String phone,
			String email,
			UserRole role,
			boolean phoneVerified) {

		public static UserSummary from(User user) {
			return new UserSummary(
					user.getId(),
					user.getPhone(),
					user.getEmail(),
					user.getRole(),
					user.getPhoneVerifiedAt() != null);
		}
	}

	@Schema(description = "Generic acknowledgement carrying no information about account existence")
	public record MessageResponse(String message) {
	}
}
