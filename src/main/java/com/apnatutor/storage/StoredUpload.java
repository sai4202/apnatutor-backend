package com.apnatutor.storage;

import java.util.UUID;
import java.util.regex.Pattern;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;

/**
 * The validation and key-naming every {@link FileStorage} shares.
 *
 * <p>Extracted when the S3 implementation arrived ({@code M6-10.3}). Two backends duplicating these
 * rules is how they drift, and the way they would drift is the dangerous direction: a size or type
 * check that is stricter locally than in production means every test passes and production accepts
 * a file it should have refused.
 *
 * <p>The key format is deliberate. {@code kind-directory/uuid.ext} contains no part of the uploaded
 * filename, no user id and no tutor id — a key must not be guessable from knowing who uploaded it,
 * because for an ID document that would be the whole access control.
 */
public final class StoredUpload {

	/** Matches a key this application produced. Anything else is refused before it reaches storage. */
	public static final Pattern KEY_PATTERN =
			Pattern.compile("^[a-z-]{3,30}/[0-9a-f-]{36}[.][a-z]{2,5}$");

	private StoredUpload() {
	}

	/**
	 * Validates an upload and returns the key it should be stored under.
	 *
	 * @throws ApiException if the file is empty, too large, or of a type this kind does not accept
	 */
	public static String validateAndBuildKey(byte[] content, FileKind kind) {
		if (content == null || content.length == 0) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file is empty.");
		}
		if (content.length > kind.maxBytes()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That file is too large. The limit is %d MB."
							.formatted(kind.maxBytes() / (1024 * 1024)));
		}

		// Decided from the bytes. The declared Content-Type is never consulted: it is supplied by
		// the uploader, and trusting it is how a .jpg turns out to be a script.
		String contentType = ContentTypeDetector.detect(content);
		if (contentType == null || !kind.allows(contentType)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That file type is not supported. Please upload a %s.".formatted(describe(kind)));
		}

		return "%s/%s.%s".formatted(
				kind.directory(), UUID.randomUUID(), ContentTypeDetector.extensionFor(contentType));
	}

	/** A human list of what this kind accepts, for the error message. */
	public static String describe(FileKind kind) {
		return kind.allowedContentTypes().stream()
				.map(type -> switch (type) {
					case "image/jpeg" -> "JPG";
					case "image/png" -> "PNG";
					case "image/webp" -> "WebP";
					case "application/pdf" -> "PDF";
					default -> type;
				})
				.distinct()
				.reduce((a, b) -> a + ", " + b)
				.orElse("supported file");
	}
}
