package com.apnatutor.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores uploads on the local filesystem.
 *
 * <p>For development and a single-server deployment. Local disk does not survive a redeploy on most
 * hosts and cannot be shared between instances, so {@code M6-10.3} replaces this with S3-compatible
 * storage before launch.
 *
 * <h2>The two things this class exists to get right</h2>
 *
 * <p><strong>Path traversal.</strong> A storage key is matched against a strict pattern and the
 * resolved path is then checked to be inside the storage root. Both, not either: the pattern is the
 * intent and the containment check is the guarantee. A key like {@code ../../etc/passwd} must not
 * become a file read, and the second check holds even if the pattern is later loosened by someone
 * who does not realise why it is tight.
 *
 * <p><strong>Filenames are discarded entirely.</strong> The stored name is a fresh UUID plus an
 * extension derived from the detected type. Nothing the uploader chose survives, so there is no
 * null byte, no unicode direction override, no {@code .php}, and no way to collide with an existing
 * file on purpose.
 */
@Component
@ConditionalOnProperty(name = "apnatutor.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

	/** {@code kind-directory/uuid.ext} and nothing else. No slashes beyond the one, no dots. */
	private static final Pattern KEY_PATTERN =
			Pattern.compile("^[a-z-]{3,30}/[0-9a-f-]{36}\\.[a-z]{2,5}$");

	private final Path root;

	public LocalFileStorage(AppProperties properties) {
		this.root = Paths.get(properties.storage().localPath()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
			log.info("Local file storage rooted at {}", root);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not create storage directory " + root, e);
		}
	}

	@Override
	public StoredFile store(byte[] content, FileKind kind) {
		if (content == null || content.length == 0) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file is empty.");
		}
		if (content.length > kind.maxBytes()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That file is too large. The limit is %d MB."
							.formatted(kind.maxBytes() / (1024 * 1024)));
		}

		// Decided from the bytes. The declared Content-Type is never consulted.
		String contentType = ContentTypeDetector.detect(content);
		if (contentType == null || !kind.allows(contentType)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That file type is not supported. Please upload a %s."
							.formatted(describe(kind)));
		}

		String storageKey = "%s/%s.%s".formatted(
				kind.directory(), UUID.randomUUID(), ContentTypeDetector.extensionFor(contentType));

		Path target = resolve(storageKey);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, content);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + storageKey, e);
		}

		log.debug("Stored {} ({} bytes, {})", storageKey, content.length, contentType);
		return new StoredFile(storageKey, contentType, content.length);
	}

	@Override
	public Optional<FileContent> load(String storageKey) {
		if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
			return Optional.empty();
		}

		Path target = resolve(storageKey);
		if (!Files.isRegularFile(target)) {
			return Optional.empty();
		}

		try {
			byte[] bytes = Files.readAllBytes(target);
			// Re-detected on read rather than trusted from the extension: the type served in the
			// response header is then always the type of the bytes actually being sent.
			String contentType = ContentTypeDetector.detect(bytes);
			return contentType == null
					? Optional.empty()
					: Optional.of(new FileContent(bytes, contentType));
		} catch (IOException e) {
			log.warn("Could not read {}", storageKey, e);
			return Optional.empty();
		}
	}

	@Override
	public void delete(String storageKey) {
		if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
			return;
		}
		try {
			Files.deleteIfExists(resolve(storageKey));
		} catch (IOException e) {
			log.warn("Could not delete {}", storageKey, e);
		}
	}

	/**
	 * Resolves a key to a path, refusing anything that escapes the storage root.
	 *
	 * <p>The containment check is not redundant with {@link #KEY_PATTERN}. It is the guarantee that
	 * still holds if the pattern is ever relaxed by someone who has not read this class.
	 */
	private Path resolve(String storageKey) {
		Path resolved = root.resolve(storageKey).normalize();
		if (!resolved.startsWith(root)) {
			log.warn("Rejected path traversal attempt: {}", storageKey);
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid file reference.");
		}
		return resolved;
	}

	private static String describe(FileKind kind) {
		return kind.allowedContentTypes().contains("application/pdf")
				? "JPG, PNG or PDF file"
				: "JPG, PNG or WebP image";
	}
}
