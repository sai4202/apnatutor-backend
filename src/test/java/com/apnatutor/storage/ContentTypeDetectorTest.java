package com.apnatutor.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The declared content type and the filename are both attacker-controlled, so this is the only
 * thing standing between an upload form and stored XSS.
 */
class ContentTypeDetectorTest {

	private static byte[] withSignature(int... signature) {
		// Padded past the 12-byte minimum the detector needs to inspect a WebP header.
		byte[] bytes = new byte[32];
		for (int i = 0; i < signature.length; i++) {
			bytes[i] = (byte) signature[i];
		}
		return bytes;
	}

	@Test
	@DisplayName("recognises the formats we accept")
	void detectsAllowedFormats() {
		assertThat(ContentTypeDetector.detect(withSignature(0xFF, 0xD8, 0xFF)))
				.isEqualTo("image/jpeg");
		assertThat(ContentTypeDetector.detect(
				withSignature(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
				.isEqualTo("image/png");
		assertThat(ContentTypeDetector.detect(
				withSignature(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)))
				.isEqualTo("image/webp");
		assertThat(ContentTypeDetector.detect(withSignature(0x25, 0x50, 0x44, 0x46, 0x2D)))
				.isEqualTo("application/pdf");
	}

	@Test
	@DisplayName("rejects HTML, however it is named")
	void rejectsHtml() {
		// The classic upload attack: name it photo.jpg, declare it image/jpeg, and if it is served
		// back from our origin the script inside runs with our privileges.
		byte[] html = "<html><script>alert(document.cookie)</script></html>"
				.getBytes(StandardCharsets.UTF_8);
		assertThat(ContentTypeDetector.detect(html)).isNull();
	}

	@Test
	@DisplayName("rejects SVG — it is XML and can carry script")
	void rejectsSvg() {
		// Deliberately unsupported. SVG has no fixed magic number, is XML, and can contain
		// <script>. There is no safe way to accept one without a sanitising parser, and a profile
		// photo does not need vector graphics.
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8);
		assertThat(ContentTypeDetector.detect(svg)).isNull();
	}

	@Test
	@DisplayName("rejects a script disguised with an image extension")
	void rejectsScriptContent() {
		byte[] php = "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8);
		assertThat(ContentTypeDetector.detect(php)).isNull();
	}

	@Test
	@DisplayName("rejects empty, tiny and null input without throwing")
	void handlesDegenerateInput() {
		assertThat(ContentTypeDetector.detect(null)).isNull();
		assertThat(ContentTypeDetector.detect(new byte[0])).isNull();
		assertThat(ContentTypeDetector.detect(new byte[] { (byte) 0xFF, (byte) 0xD8 })).isNull();
	}

	@Test
	@DisplayName("extensions come from the detected type, never the upload")
	void extensionsFollowDetectedType() {
		assertThat(ContentTypeDetector.extensionFor("image/jpeg")).isEqualTo("jpg");
		assertThat(ContentTypeDetector.extensionFor("image/png")).isEqualTo("png");
		assertThat(ContentTypeDetector.extensionFor("application/pdf")).isEqualTo("pdf");
		assertThat(ContentTypeDetector.extensionFor("anything/else")).isEqualTo("bin");
	}
}
