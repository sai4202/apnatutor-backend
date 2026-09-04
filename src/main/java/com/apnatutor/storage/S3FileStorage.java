package com.apnatutor.storage;

import java.net.URI;
import java.util.Optional;

import com.apnatutor.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible object storage — {@code M6-10.3}.
 *
 * <h2>Why local disk cannot go to production</h2>
 *
 * <p>{@link LocalFileStorage} writes to a directory. Most hosts replace the filesystem on every
 * redeploy, and no second instance can read the first one's disk — so a tutor's uploaded ID
 * document survives until the next deploy and then does not. It fails silently: the upload
 * succeeds, the verification queue shows the row, and the document is a 404 by the time an
 * administrator opens it.
 *
 * <p>Selected with {@code apnatutor.storage.provider=s3}. {@link com.apnatutor.common.config.DevModeGuard}
 * refuses to start a production profile with the local provider still configured.
 *
 * <h2>Compatible, not AWS-specific</h2>
 *
 * <p>An explicit endpoint and path-style access are supported, so this works against Cloudflare R2,
 * Backblaze B2, DigitalOcean Spaces or MinIO as well as S3 itself. That matters for an India-first
 * product: egress pricing differs by an order of magnitude between them, and file serving is the
 * one cost here that scales with traffic rather than with users.
 *
 * <h2>The bucket must be private</h2>
 *
 * <p>Nothing here sets an ACL, and nothing generates a public URL. Every file is served through
 * {@link FileController}, which is what applies the admin-only rule to identity documents. A bucket
 * configured for public read would route around that entirely, and no code in this application
 * would notice.
 */
@Component
@ConditionalOnProperty(name = "apnatutor.storage.provider", havingValue = "s3")
public class S3FileStorage implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

	private final S3Client client;
	private final String bucket;

	public S3FileStorage(AppProperties properties) {
		AppProperties.Storage config = properties.storage();
		this.bucket = require(config.bucket(), "apnatutor.storage.bucket");

		var builder = S3Client.builder()
				.region(Region.of(config.region() == null ? "ap-south-1" : config.region()));

		if (config.endpoint() != null && !config.endpoint().isBlank()) {
			// A non-AWS provider. Path-style addressing, because virtual-host style needs
			// wildcard DNS most compatible providers do not offer.
			builder.endpointOverride(URI.create(config.endpoint()))
					.serviceConfiguration(S3Configuration.builder()
							.pathStyleAccessEnabled(true)
							.build());
		}

		if (config.accessKey() != null && !config.accessKey().isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(config.accessKey(), config.secretKey())));
		} else {
			// No keys configured: fall back to the ambient credential chain, which is how this
			// should run on a host with an instance role. Keys in environment variables are the
			// fallback, not the intent.
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}

		this.client = builder.build();
		log.info("File storage: S3 bucket '{}'{}", bucket,
				config.endpoint() == null ? "" : " at " + config.endpoint());
	}

	@Override
	public StoredFile store(byte[] content, FileKind kind) {
		String storageKey = StoredUpload.validateAndBuildKey(content, kind);
		String contentType = ContentTypeDetector.detect(content);

		client.putObject(
				PutObjectRequest.builder()
						.bucket(bucket)
						.key(storageKey)
						// Set from the sniffed bytes, never from what the uploader declared. It is
						// what the browser will act on when an administrator opens the document.
						.contentType(contentType)
						.build(),
				RequestBody.fromBytes(content));

		log.debug("Stored s3://{}/{} ({} bytes, {})", bucket, storageKey, content.length, contentType);
		return new StoredFile(storageKey, contentType, content.length);
	}

	@Override
	public Optional<FileContent> load(String storageKey) {
		if (storageKey == null || !StoredUpload.KEY_PATTERN.matcher(storageKey).matches()) {
			// Refused before it reaches S3. A key this application did not produce has no business
			// being fetched, and checking here means a traversal attempt never becomes a request.
			return Optional.empty();
		}

		try {
			ResponseBytes<GetObjectResponse> object = client.getObjectAsBytes(
					GetObjectRequest.builder().bucket(bucket).key(storageKey).build());

			byte[] bytes = object.asByteArray();

			// Re-detected on the way out rather than trusting the stored metadata. The bytes are
			// the only thing that cannot have been changed by a misconfigured console upload.
			String contentType = ContentTypeDetector.detect(bytes);
			return Optional.of(new FileContent(bytes, contentType));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		} catch (S3Exception e) {
			// Logged rather than thrown. A storage outage should surface as a missing file — a 404
			// the caller already handles — not as a 500 on a verification queue an administrator
			// is trying to work through.
			log.error("Could not read s3://{}/{}", bucket, storageKey, e);
			return Optional.empty();
		}
	}

	@Override
	public void delete(String storageKey) {
		if (storageKey == null || !StoredUpload.KEY_PATTERN.matcher(storageKey).matches()) {
			return;
		}

		// S3 delete is already idempotent: removing a key that is not there succeeds. That suits
		// the contract, which says deletion must be safe to retry.
		client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
		log.info("Deleted s3://{}/{}", bucket, storageKey);
	}

	private static String require(String value, String property) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					"REFUSING TO START: apnatutor.storage.provider=s3 but " + property
							+ " is not set. Uploads would fail at the first tutor who tried one.");
		}
		return value;
	}
}
