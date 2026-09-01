package com.apnatutor.storage;

import java.io.IOException;
import java.time.Duration;

import com.apnatutor.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves stored files.
 *
 * <p>Two endpoints, and the split between them is the security model:
 *
 * <ul>
 *   <li>{@link #servePublic} answers unauthenticated requests but <strong>only</strong> for kinds
 *       marked public. Ask it for an ID document and it returns 404 — not 403, which would confirm
 *       the file exists.
 *   <li>{@link #serveAdmin} answers for any kind and is admin-only. It is the only way an ID scan
 *       or degree certificate is ever read over HTTP.
 * </ul>
 *
 * <p>The kind is derived from the storage key's own directory, so the check needs no database
 * lookup and cannot be skipped by a caller who forgets one.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Files", description = "Serving uploaded photos and documents")
public class FileController {

	/** Photos are immutable — the key changes when the photo does — so they cache hard. */
	private static final Duration PUBLIC_CACHE = Duration.ofDays(30);

	private final FileStorage storage;

	public FileController(FileStorage storage) {
		this.storage = storage;
	}

	@GetMapping("/public/files/{directory}/{filename}")
	@Operation(
			summary = "Serve a public file",
			description = "Profile photos only. Any other kind returns 404, deliberately — a 403 "
					+ "would confirm the file exists.")
	public ResponseEntity<byte[]> servePublic(
			@PathVariable String directory, @PathVariable String filename) throws IOException {

		FileKind kind = FileKind.fromDirectory(directory);
		if (kind == null || !kind.isPublic()) {
			// 404 rather than 403: an authorization error here would let anyone confirm whether a
			// given ID document exists, which is itself a disclosure.
			throw ApiException.notFound("File");
		}

		return serve(directory + "/" + filename, PUBLIC_CACHE);
	}

	@GetMapping("/admin/files/{directory}/{filename}")
	@PreAuthorize("hasRole('ADMIN')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(
			summary = "Serve any file, including private documents",
			description = "The only route by which an ID or education document is read. Admin only.")
	public ResponseEntity<byte[]> serveAdmin(
			@PathVariable String directory, @PathVariable String filename) throws IOException {
		// Never cached: these are identity documents, and a shared cache holding one is a leak
		// waiting for a misconfigured proxy.
		return serve(directory + "/" + filename, null);
	}

	private ResponseEntity<byte[]> serve(String storageKey, Duration cacheFor) {
		FileStorage.FileContent content = storage.load(storageKey)
				.orElseThrow(() -> ApiException.notFound("File"));

		ResponseEntity.BodyBuilder response = ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				// Belt and braces against a stored file being interpreted rather than displayed.
				// The type comes from sniffed bytes, but nosniff means a browser will not
				// second-guess it either.
				.header("X-Content-Type-Options", "nosniff")
				// A PDF or image opened from our origin cannot script against it.
				.header("Content-Security-Policy", "default-src 'none'; sandbox");

		if (cacheFor != null) {
			response.cacheControl(CacheControl.maxAge(cacheFor).cachePublic().immutable());
		} else {
			response.cacheControl(CacheControl.noStore());
		}

		return response.body(content.bytes());
	}
}
