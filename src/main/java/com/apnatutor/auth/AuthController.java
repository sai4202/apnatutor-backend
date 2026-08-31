package com.apnatutor.auth;

import com.apnatutor.auth.dto.AuthDtos.AuthResponse;
import com.apnatutor.auth.dto.AuthDtos.MessageResponse;
import com.apnatutor.auth.dto.AuthDtos.OtpRequest;
import com.apnatutor.auth.dto.AuthDtos.OtpVerifyRequest;
import com.apnatutor.auth.dto.AuthDtos.UserSummary;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.ApiError;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phone + OTP authentication.
 *
 * <p>The access token is returned in the body for the client to send as a Bearer header. The refresh
 * token never appears in a body — it is set as an {@code HttpOnly} cookie so JavaScript cannot read
 * it. See {@link RefreshCookie} for the CSRF reasoning that goes with that choice.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Phone and OTP sign-in")
public class AuthController {

	private final AuthService authService;
	private final UserRepository users;
	private final AppProperties properties;

	public AuthController(AuthService authService, UserRepository users, AppProperties properties) {
		this.authService = authService;
		this.users = users;
		this.properties = properties;
	}

	@PostMapping("/otp/request")
	@Operation(
			summary = "Request a login code",
			description = """
					Sends a 6-digit code by SMS. Works for both sign-in and sign-up.

					The response is deliberately identical whether or not the number has an \
					account — otherwise this endpoint would be a free oracle for discovering \
					which phone numbers are registered.

					Rate limited per number per hour.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Code sent, if the number is valid"),
			@ApiResponse(responseCode = "429", description = "OTP_SEND_LIMIT_EXCEEDED")
	})
	public ResponseEntity<MessageResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
		authService.requestLoginCode(request.phone());
		return ResponseEntity.ok(new MessageResponse(
				"If that number is valid, we have sent a code to it."));
	}

	@PostMapping("/otp/verify")
	@Operation(
			summary = "Verify a code and sign in",
			description = "Creates the account on first successful verification. `role` is required only then.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Signed in"),
			@ApiResponse(responseCode = "400", description = "OTP_INVALID or OTP_EXPIRED"),
			@ApiResponse(responseCode = "429", description = "OTP_ATTEMPTS_EXCEEDED")
	})
	public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
		AuthService.AuthResult result =
				authService.verifyAndAuthenticate(request.phone(), request.code(), request.role());
		return respondWithSession(result);
	}

	@PostMapping("/refresh")
	@Operation(
			summary = "Exchange the refresh cookie for a new access token",
			description = """
					Reads the HttpOnly refresh cookie and rotates it.

					Requires the `X-Refresh-Request` header. This is a CSRF defence: HTML forms \
					cannot set custom headers, and cross-origin scripted requests that try are \
					forced into a preflight that only the configured frontend origin passes.

					Presenting an already-rotated token revokes the entire token family — that \
					pattern means the token leaked.""")
	public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
		if (!RefreshCookie.hasCsrfHeader(request)) {
			throw new ApiException(ErrorCode.FORBIDDEN,
					"Missing " + RefreshCookie.CSRF_HEADER + " header.");
		}

		String refreshToken = RefreshCookie.read(request);
		if (refreshToken == null) {
			throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
					"Session expired. Please sign in again.");
		}

		return respondWithSession(authService.refresh(refreshToken));
	}

	@PostMapping("/logout")
	@Operation(summary = "End this session", description = "Revokes the refresh token and clears the cookie.")
	public ResponseEntity<MessageResponse> logout(HttpServletRequest request) {
		authService.logout(RefreshCookie.read(request));

		// The cookie is cleared regardless of whether a valid token was present: logout must always
		// leave the browser in a signed-out state, never fail halfway.
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, RefreshCookie.clear(isSecure()).toString())
				.body(new MessageResponse("Signed out."));
	}

	@GetMapping("/me")
	@Operation(summary = "The signed-in user", description = "Requires a valid access token.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The current user"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @io.swagger.v3.oas.annotations.media.Content(
							schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<UserSummary> me(CurrentUser currentUser) {
		User user = users.findById(currentUser.userId())
				.orElseThrow(() -> ApiException.notFound("User"));
		return ResponseEntity.ok(UserSummary.from(user));
	}

	private ResponseEntity<AuthResponse> respondWithSession(AuthService.AuthResult result) {
		ResponseCookie cookie = RefreshCookie.create(
				result.tokens().refreshToken(),
				properties.jwt().refreshTokenTtl(),
				isSecure());

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(new AuthResponse(
						result.tokens().accessToken(),
						result.tokens().accessTokenExpiresInSeconds(),
						result.newAccount(),
						UserSummary.from(result.user())));
	}

	/**
	 * {@code Secure} cookies are only sent over HTTPS, which would break local development on plain
	 * HTTP. Derived from the configured frontend URL so production — which is HTTPS — gets it
	 * automatically, with no separate flag to forget to flip.
	 */
	private boolean isSecure() {
		return properties.frontendUrl().startsWith("https://");
	}
}
