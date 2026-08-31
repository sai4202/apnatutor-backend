package com.apnatutor.common.web;

import java.util.HashMap;
import java.util.Map;

import com.apnatutor.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the one {@link ApiError} shape.
 *
 * <p>Two rules drive the design:
 *
 * <ol>
 *   <li><strong>Never leak internals.</strong> Stack traces, SQL, and class names are logged, never
 *       returned. An attacker learns nothing from a 500 here.
 *   <li><strong>Every response carries an {@link ErrorCode}.</strong> No bare status codes, so the
 *       frontend always has something stable to branch on.
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Deliberately-raised application errors. Expected outcomes, so logged at debug. */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApiException(ApiException ex) {
		ErrorCode code = ex.getErrorCode();
		log.debug("API error {}: {}", code, ex.getMessage());
		return ResponseEntity.status(code.status()).body(ApiError.of(code, ex.getMessage()));
	}

	/** Bean validation on a {@code @RequestBody}. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new HashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				// On duplicate keys keep the first message; reporting one problem per field is
				// clearer to the user than concatenating several.
				fieldErrors.putIfAbsent(error.getField(),
						error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()));

		return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
				.body(ApiError.validation("Request validation failed", fieldErrors));
	}

	/** Bean validation on path variables and request parameters. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
		return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
				.body(ApiError.of(ErrorCode.VALIDATION_FAILED, "Request validation failed"));
	}

	/**
	 * Unparseable body — malformed JSON, wrong type, unknown enum value.
	 *
	 * <p>The exception message is not echoed back: it can contain fragments of the payload and
	 * internal type names.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
		log.debug("Malformed request body: {}", ex.getMessage());
		return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status())
				.body(ApiError.of(ErrorCode.MALFORMED_REQUEST, "Request body could not be parsed"));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
		log.debug("Authentication failed: {}", ex.getMessage());
		return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.status())
				.body(ApiError.of(ErrorCode.UNAUTHENTICATED, "Authentication required"));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
		log.debug("Access denied: {}", ex.getMessage());
		return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
				.body(ApiError.of(ErrorCode.FORBIDDEN, "You do not have permission to do that"));
	}

	/** Unmapped URL. Handled explicitly so 404s get an ErrorCode like everything else. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
				.body(ApiError.of(ErrorCode.NOT_FOUND, "Resource not found"));
	}

	/**
	 * Everything unanticipated.
	 *
	 * <p>Logged at error with the full trace, because this always represents a bug. The caller gets
	 * a fixed, information-free message.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of(ErrorCode.INTERNAL_ERROR, "Something went wrong on our side"));
	}
}
