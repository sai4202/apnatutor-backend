package com.apnatutor.datarights.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

/** Export and deletion shapes — {@code M5-10}. */
public final class DataRightsDtos {

	private DataRightsDtos() {
	}

	/**
	 * Deleting an account.
	 *
	 * <p>An explicit acknowledgement rather than a bare {@code DELETE}. This is irreversible and
	 * takes a tutor's unspent credits with it; a request that can be made by a mis-routed link is
	 * the wrong shape for that.
	 */
	@Schema(description = "Confirm account deletion. Irreversible.")
	public record DeleteAccountRequest(
			@AssertTrue(message = "Confirm that you understand this cannot be undone")
			Boolean understood) {
	}

	@Schema(description = "What deletion did")
	public record DeletionReceipt(
			Instant deletedAt,
			@Schema(description = "Live enquiries withdrawn as part of the deletion")
			int enquiriesClosed,
			@Schema(description = "Identity and education documents destroyed")
			int documentsDestroyed,
			@Schema(description = "Unspent credits forfeited, for a tutor")
			int creditsForfeited,
			@Schema(description = "What is kept, and why")
			List<String> retained) {
	}

	/**
	 * Everything the platform holds about one person — {@code M5-10.1}.
	 *
	 * <p>Deliberately a map per section rather than typed records for each. An export must include
	 * whatever exists, and a typed shape per section is a shape somebody has to remember to extend
	 * when a table is added — which produces an export that is quietly incomplete, the one failure
	 * mode that matters here.
	 */
	@Schema(description = "A full copy of your data")
	public record DataExport(
			Instant generatedAt,
			Map<String, Object> account,
			Map<String, Object> profile,
			List<Map<String, Object>> enquiries,
			List<Map<String, Object>> leadsUnlocked,
			List<Map<String, Object>> creditLedger,
			List<Map<String, Object>> payments,
			List<Map<String, Object>> reviewsWritten,
			List<Map<String, Object>> reviewsReceived,
			List<Map<String, Object>> reportsFiled,
			List<Map<String, Object>> notifications,
			@Schema(description = "Plain-language notes about what is here and what is not")
			List<String> notes) {
	}
}
