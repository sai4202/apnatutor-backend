package com.apnatutor.storage;

import java.util.Set;

/**
 * What a stored file is for, which decides who may read it and what may be uploaded.
 *
 * <p>The distinction that matters is {@link #isPublic()}. A profile photo is meant to be seen by
 * anyone browsing tutors. An ID document is a scan of somebody's Aadhaar or PAN card, and serving
 * one publicly is a data-protection incident rather than a feature — so the two can never share a
 * serving path.
 *
 * <p>The kind is encoded in the storage key itself ({@code profile-photo/uuid.jpg}), so the public
 * endpoint can refuse anything outside its own directory without a database lookup. Access control
 * that depends on a lookup is access control that can be skipped.
 */
public enum FileKind {

	/** A tutor's profile picture. Publicly served. */
	PROFILE_PHOTO("profile-photo", true, 5 * 1024 * 1024,
			Set.of("image/jpeg", "image/png", "image/webp")),

	/** Aadhaar, PAN or similar. Admin-only, forever. */
	ID_DOCUMENT("id-document", false, 10 * 1024 * 1024,
			Set.of("image/jpeg", "image/png", "application/pdf")),

	/** Degree certificates and similar. Admin-only. */
	EDUCATION_DOCUMENT("education-document", false, 10 * 1024 * 1024,
			Set.of("image/jpeg", "image/png", "application/pdf"));

	private final String directory;
	private final boolean publiclyServed;
	private final long maxBytes;
	private final Set<String> allowedContentTypes;

	FileKind(String directory, boolean publiclyServed, long maxBytes, Set<String> allowedContentTypes) {
		this.directory = directory;
		this.publiclyServed = publiclyServed;
		this.maxBytes = maxBytes;
		this.allowedContentTypes = allowedContentTypes;
	}

	public String directory() {
		return directory;
	}

	public boolean isPublic() {
		return publiclyServed;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public boolean allows(String contentType) {
		return allowedContentTypes.contains(contentType);
	}

	public Set<String> allowedContentTypes() {
		return allowedContentTypes;
	}

	/** Resolves a kind from the leading directory of a storage key. */
	public static FileKind fromDirectory(String directory) {
		for (FileKind kind : values()) {
			if (kind.directory.equals(directory)) {
				return kind;
			}
		}
		return null;
	}
}
