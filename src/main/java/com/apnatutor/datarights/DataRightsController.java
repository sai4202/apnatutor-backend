package com.apnatutor.datarights;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.datarights.dto.DataRightsDtos.DataExport;
import com.apnatutor.datarights.dto.DataRightsDtos.DeleteAccountRequest;
import com.apnatutor.datarights.dto.DataRightsDtos.DeletionReceipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your own data — {@code M5-10}.
 *
 * <p>Both endpoints act on the caller and only the caller. There is deliberately no admin route to
 * export or delete somebody else's account: a support tool that can produce a full copy of any
 * user's data on request is a social-engineering target, and deletion on someone else's behalf is
 * a request that should arrive through a process, not a button.
 */
@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Your data", description = "Export and deletion under the DPDP Act")
public class DataRightsController {

	private final DataRightsService service;

	public DataRightsController(DataRightsService service) {
		this.service = service;
	}

	@GetMapping("/export")
	@Operation(
			summary = "A copy of everything we hold about you",
			description = "Includes the credit ledger and payment records, which are usually the "
					+ "part people actually want. Contact details of other people are never "
					+ "included, even where you paid to see them — that is their data, not yours.")
	public ResponseEntity<DataExport> export(CurrentUser currentUser) {
		return ResponseEntity.ok()
				// Offered as a download rather than a page. The response contains everything about
				// one person, and a browser rendering it inline is one screenshot away from being
				// somewhere it should not be.
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"apnatutor-data-export.json\"")
				.body(service.export(currentUser.userId()));
	}

	@PostMapping("/delete")
	@Operation(
			summary = "Delete your account",
			description = "Irreversible. Your phone number, email, profile and uploaded identity "
					+ "documents are erased. Financial ledger entries are kept without your "
					+ "details: they are append-only records of money, and removing them would "
					+ "break the accounts of people you dealt with. Unspent credits are forfeited.")
	public ResponseEntity<DeletionReceipt> delete(
			CurrentUser currentUser, @Valid @RequestBody DeleteAccountRequest request) {

		return ResponseEntity.ok(service.deleteAccount(currentUser.userId()));
	}
}
