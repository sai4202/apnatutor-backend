package com.apnatutor.billing.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.apnatutor.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Razorpay, over its REST API.
 *
 * <h2>Why not the Razorpay Java SDK</h2>
 *
 * <p>The parts of the API this project uses are one POST and two HMACs. The SDK would add
 * {@code org.json} and its own HTTP client to the dependency tree, plus a version to keep compatible
 * with Spring Boot 4's Jackson 3 — for code that is shorter written directly than configured.
 * Reconsider if subscriptions, settlements or payouts are ever needed; those are genuinely fiddly.
 *
 * <p>Selected by {@link PaymentGatewayConfig} only when credentials are present. Without them the
 * application falls back to {@link StubPaymentGateway}, so the purchase flow is fully testable
 * before a Razorpay account exists.
 */
public class RazorpayGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

	private static final String ORDERS_URL = "https://api.razorpay.com/v1/orders";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	private final AppProperties.Razorpay config;
	private final ObjectMapper json;
	private final HttpClient http;

	public RazorpayGateway(AppProperties properties, ObjectMapper json) {
		this.config = properties.razorpay();
		this.json = json;
		this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

		if (!config.canVerifyWebhooks()) {
			// Loud, because the consequence is specific: without the secret every webhook fails
			// verification, so no purchase is ever credited even though the money left the account.
			log.warn("Razorpay is configured but apnatutor.razorpay.webhook-secret is not set. "
					+ "Webhooks cannot be verified, so no purchase will be credited.");
		}
	}

	@Override
	public String name() {
		return "RAZORPAY";
	}

	@Override
	public GatewayOrder createOrder(long amountPaise, String currency, String receipt) {
		// Razorpay speaks paise natively, which is why money is stored in paise throughout — there
		// is no conversion here to get wrong.
		String body = json.writeValueAsString(java.util.Map.of(
				"amount", amountPaise,
				"currency", currency,
				"receipt", receipt,
				// The order is only ever completed by the webhook, never by the browser. Capturing
				// automatically means we do not have to make a second call to take the money.
				"payment_capture", 1));

		HttpRequest request = HttpRequest.newBuilder(URI.create(ORDERS_URL))
				.timeout(TIMEOUT)
				.header("Authorization", basicAuth())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> response = http.send(
					request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				// The body is logged but never returned: a provider error message can carry account
				// detail, and SOURCE_OF_TRUTH.md forbids leaking internals in a response.
				log.error("Razorpay order creation failed: status={} body={}",
						response.statusCode(), response.body());
				throw new PaymentGatewayException(
						"Razorpay refused the order (HTTP " + response.statusCode() + ")");
			}

			JsonNode order = json.readTree(response.body());
			return new GatewayOrder(
					order.get("id").asString(),
					order.get("amount").asLong(),
					order.get("currency").asString());

		} catch (java.io.IOException e) {
			throw new PaymentGatewayException("Could not reach Razorpay", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PaymentGatewayException("Interrupted calling Razorpay", e);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Razorpay signs the raw request body with the webhook secret and sends the hex digest in
	 * {@code X-Razorpay-Signature}.
	 */
	@Override
	public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
		if (!config.canVerifyWebhooks()) {
			// Fail closed. An endpoint that cannot verify must reject everything, because the
			// alternative is granting credits to anyone who finds the URL.
			log.error("Rejecting webhook: no webhook secret configured");
			return false;
		}
		if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
			return false;
		}
		return Signatures.matches(
				Signatures.hmacHex(rawBody, config.webhookSecret()), signatureHeader);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The checkout signature is over {@code order_id|payment_id}, signed with the API secret
	 * rather than the webhook secret.
	 */
	@Override
	public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
		if (!config.isConfigured() || orderId == null || paymentId == null) {
			return false;
		}
		return Signatures.matches(
				Signatures.hmacHex(orderId + "|" + paymentId, config.keySecret()), signature);
	}

	private String basicAuth() {
		String credentials = config.keyId() + ":" + config.keySecret();
		return "Basic " + Base64.getEncoder()
				.encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}
}
