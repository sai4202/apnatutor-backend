package com.apnatutor.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage behaviour, tested against a real temporary directory rather than a mock — the things
 * worth checking here (path traversal, what actually lands on disk) are precisely the things a mock
 * would paper over.
 */
class LocalFileStorageTest {

	private static AppProperties propertiesFor(Path root) {
		return new AppProperties(
				"http://localhost:3000",
				new AppProperties.Jwt(
						"a-secret-that-is-comfortably-longer-than-32-bytes",
						Duration.ofMinutes(15),
						Duration.ofDays(30)),
				new AppProperties.Otp(Duration.ofMinutes(10), 5, 5),
				new AppProperties.Sms("console"),
				new AppProperties.Storage("local", root.toString()),
				new AppProperties.Dev(false, "123456"),
				// Irrelevant to file storage; any valid values will do.
				new AppProperties.RateLimit(20, 120, 3000, 300, 30),
				new AppProperties.Razorpay("", "", ""));
	}

	private static byte[] jpeg(int sizeBytes) {
		byte[] bytes = new byte[Math.max(sizeBytes, 16)];
		bytes[0] = (byte) 0xFF;
		bytes[1] = (byte) 0xD8;
		bytes[2] = (byte) 0xFF;
		return bytes;
	}

	@Test
	@DisplayName("stores a valid image and reads it back")
	void storesAndLoads(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));

		FileStorage.StoredFile stored = storage.store(jpeg(512), FileKind.PROFILE_PHOTO);

		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.storageKey()).startsWith("profile-photo/").endsWith(".jpg");
		assertThat(storage.load(stored.storageKey())).isPresent();
	}

	@Test
	@DisplayName("the stored name keeps nothing from the upload")
	void discardsUploadedFilename(@TempDir Path root) {
		// Nothing the uploader chose reaches the filesystem: no null byte, no unicode direction
		// override, no second extension, and no way to collide with an existing file on purpose.
		// The key is a fresh UUID plus an extension derived from the detected type.
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));

		FileStorage.StoredFile stored = storage.store(jpeg(64), FileKind.PROFILE_PHOTO);

		assertThat(stored.storageKey()).matches("^profile-photo/[0-9a-f-]{36}\\.jpg$");
	}

	@Test
	@DisplayName("rejects a file whose bytes are not an accepted type")
	void rejectsDisguisedContent(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));
		byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> storage.store(html, FileKind.PROFILE_PHOTO))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("not supported");
	}

	@Test
	@DisplayName("rejects a PDF uploaded as a profile photo")
	void enforcesPerKindTypes(@TempDir Path root) {
		// A PDF is a valid document but not a valid photo. The kind decides, not the detector.
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));
		byte[] pdf = new byte[32];
		byte[] signature = { 0x25, 0x50, 0x44, 0x46, 0x2D };
		System.arraycopy(signature, 0, pdf, 0, signature.length);

		assertThatThrownBy(() -> storage.store(pdf, FileKind.PROFILE_PHOTO))
				.isInstanceOf(ApiException.class);

		assertThat(storage.store(pdf, FileKind.EDUCATION_DOCUMENT).contentType())
				.isEqualTo("application/pdf");
	}

	@Test
	@DisplayName("rejects a file over the kind's size limit")
	void enforcesSizeLimit(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));
		byte[] tooBig = jpeg((int) FileKind.PROFILE_PHOTO.maxBytes() + 1);

		assertThatThrownBy(() -> storage.store(tooBig, FileKind.PROFILE_PHOTO))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("too large");
	}

	@Test
	@DisplayName("rejects an empty file")
	void rejectsEmpty(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));

		assertThatThrownBy(() -> storage.store(new byte[0], FileKind.PROFILE_PHOTO))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("a traversal key cannot read a file outside the storage root")
	void refusesPathTraversal(@TempDir Path root) throws IOException {
		// A real secret one directory above the storage root, exactly what a traversal would target.
		Path secret = root.getParent().resolve("secret-" + System.nanoTime() + ".txt");
		Files.writeString(secret, "credentials");

		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));

		for (String key : new String[] {
				"../" + secret.getFileName(),
				"profile-photo/../../" + secret.getFileName(),
				"../../etc/passwd",
				"/etc/passwd",
				"profile-photo/..%2f..%2fsecret",
		}) {
			assertThat(storage.load(key))
					.as("traversal attempt: %s", key)
					.isEmpty();
		}

		Files.deleteIfExists(secret);
	}

	@Test
	@DisplayName("a malformed key returns empty rather than throwing")
	void malformedKeysAreEmpty(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));

		assertThat(storage.load(null)).isEmpty();
		assertThat(storage.load("")).isEmpty();
		assertThat(storage.load("not-a-key")).isEmpty();
		assertThat(storage.load("profile-photo/not-a-uuid.jpg")).isEmpty();
	}

	@Test
	@DisplayName("delete removes the file and is safe to repeat")
	void deleteIsIdempotent(@TempDir Path root) {
		LocalFileStorage storage = new LocalFileStorage(propertiesFor(root));
		FileStorage.StoredFile stored = storage.store(jpeg(64), FileKind.PROFILE_PHOTO);

		storage.delete(stored.storageKey());
		assertThat(storage.load(stored.storageKey())).isEmpty();

		// Deleting again must not throw — cleanup runs on paths that may already have run.
		storage.delete(stored.storageKey());
		storage.delete("profile-photo/00000000-0000-0000-0000-000000000000.jpg");
	}

	@Test
	@DisplayName("ID documents are never a public kind")
	void privateKindsStayPrivate() {
		// The one invariant the whole serving split rests on.
		assertThat(FileKind.PROFILE_PHOTO.isPublic()).isTrue();
		assertThat(FileKind.ID_DOCUMENT.isPublic()).isFalse();
		assertThat(FileKind.EDUCATION_DOCUMENT.isPublic()).isFalse();
	}
}
