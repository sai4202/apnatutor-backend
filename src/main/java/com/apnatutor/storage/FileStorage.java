package com.apnatutor.storage;

import java.util.Optional;

/**
 * Stores and retrieves uploaded files.
 *
 * <p>An interface from the start so the local-disk implementation can be swapped for S3-compatible
 * object storage at {@code M6-10.3} without touching a line of profile or verification code. Local
 * disk does not survive a redeploy on most hosts, so that swap is a launch requirement rather than
 * an optimisation.
 *
 * <p>Implementations must treat every input as hostile: the filename, the declared content type and
 * the bytes themselves all come from the internet.
 */
public interface FileStorage {

	/**
	 * A stored file.
	 *
	 * @param storageKey opaque {@code kind-directory/uuid.ext} identifier. Contains no part of the
	 *     uploaded filename, and no user or tutor id — a key must not be guessable from knowing who
	 *     uploaded it.
	 * @param contentType the type detected from the bytes, never the declared one
	 * @param sizeBytes stored size
	 */
	record StoredFile(String storageKey, String contentType, long sizeBytes) {
	}

	/** Raw bytes plus the type they were stored as. */
	record FileContent(byte[] bytes, String contentType) {
	}

	/**
	 * Validates and stores an upload.
	 *
	 * @throws com.apnatutor.common.exception.ApiException if the file is empty, too large, or of a
	 *     type this kind does not accept
	 */
	StoredFile store(byte[] content, FileKind kind);

	/** Empty rather than throwing: a missing file is an expected 404, not a fault. */
	Optional<FileContent> load(String storageKey);

	/** No-op if the key does not exist, so deletion is safe to retry. */
	void delete(String storageKey);
}
