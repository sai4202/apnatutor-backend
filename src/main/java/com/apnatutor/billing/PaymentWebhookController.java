package com.apnatutor.billing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the payment provider tells us money moved.
 *
 * <h2>Unauthenticated, and that is not a mistake</h2>
 *
 * <p>Razorpay holds no credential of ours, so this route is open. The HMAC signature is therefore
 * the only thing between this endpoint and free credits for anyone who finds the URL, which is why
 * {@link PaymentWebhookService} fails closed on a missing secret, a missing header or a mismatch —
 * and records every attempt with its raw body either way.
 *
 * <h2>Why the body is a String</h2>
 *
 * <p>The signature is computed over the exact bytes received. Letting Jackson bind the body to an
 * object and re-serialising it changes key order and whitespace, and the signature stops matching.
 * That is the most common way webhook verification is quietly broken, so the raw text is taken
 * deliberately and parsed only after it has been verified.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Payment provider callbacks. Not for client use.")
public class PaymentWebhookController {

	private final PaymentWebhookService webhooks;

	public PaymentWebhookController(PaymentWebhookService webhooks) {
		this.webhooks = webhooks;
	}

	@PostMapping(value = "/razorpay", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary = "Razorpay payment events",
			description = """
					Signature-verified. An unsigned or mismatched delivery is recorded and \
					rejected, never acted on.

					Idempotent: repeat deliveries of one event grant credits once. Providers \
					retry by design, so this is normal operation rather than a fault.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Processed, or already processed"),
			@ApiResponse(responseCode = "400", description = "Signature invalid — recorded, not acted on"),
			@ApiResponse(responseCode = "500", description = "Processing failed; provider should retry")
	})
	public ResponseEntity<String> razorpay(
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

		return switch (webhooks.handle(rawBody, signature)) {
			// The provider only needs to know whether to retry, so the bodies are deliberately
			// uninformative. Telling an unauthenticated caller why their signature failed would
			// help them fix it.
			case ACCEPTED -> ResponseEntity.ok("ok");
			case REJECTED -> ResponseEntity.badRequest().body("rejected");
			case FAILED -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("retry");
		};
	}
}
