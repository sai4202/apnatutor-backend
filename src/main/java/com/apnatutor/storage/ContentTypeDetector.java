package com.apnatutor.storage;

/**
 * Determines a file's real type from its leading bytes.
 *
 * <p><strong>The filename and the browser-supplied {@code Content-Type} are both attacker
 * controlled.</strong> Anyone can name a file {@code photo.jpg} and declare it {@code image/jpeg}
 * while uploading an HTML document, an SVG carrying a script, or a polyglot file that is a valid
 * image and a valid script at once. If that is then served back from our domain, it executes with
 * our origin's privileges — which is stored XSS with a file upload as the delivery mechanism.
 *
 * <p>So the type is decided here, by looking at the bytes, and the declared type is never consulted.
 * A file whose signature is not recognised is rejected rather than guessed at.
 *
 * <p>SVG is deliberately absent from every allowed set. It is XML, it can contain
 * {@code <script>}, and it has no fixed magic number to check — there is no safe way to accept one
 * without a sanitising parser, and profile photos do not need vector graphics.
 */
public final class ContentTypeDetector {

	private ContentTypeDetector() {
	}

	/**
	 * @return the detected MIME type, or null if the signature is not one we accept
	 */
	public static String detect(byte[] content) {
		if (content == null || content.length < 12) {
			return null;
		}

		// JPEG: FF D8 FF
		if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
			return "image/jpeg";
		}

		// PNG: 89 50 4E 47 0D 0A 1A 0A
		if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
			return "image/png";
		}

		// WebP: "RIFF" .... "WEBP" — the size field sits between the two markers.
		if (startsWith(content, 0x52, 0x49, 0x46, 0x46)
				&& content[8] == 0x57 && content[9] == 0x45
				&& content[10] == 0x42 && content[11] == 0x50) {
			return "image/webp";
		}

		// PDF: "%PDF-"
		if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
			return "application/pdf";
		}

		return null;
	}

	private static boolean startsWith(byte[] content, int... signature) {
		if (content.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if ((content[i] & 0xFF) != signature[i]) {
				return false;
			}
		}
		return true;
	}

	/** The canonical extension for a detected type. Never taken from the uploaded filename. */
	public static String extensionFor(String contentType) {
		return switch (contentType) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			case "image/webp" -> "webp";
			case "application/pdf" -> "pdf";
			default -> "bin";
		};
	}
}
