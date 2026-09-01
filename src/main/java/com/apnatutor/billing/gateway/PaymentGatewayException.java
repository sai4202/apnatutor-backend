package com.apnatutor.billing.gateway;

/**
 * The payment provider could not be reached, or refused the request.
 *
 * <p>Distinct from a declined card. This means we never got as far as asking — a timeout, a network
 * failure, a 500 from the provider — so nothing was charged and the tutor should simply try again.
 */
public class PaymentGatewayException extends RuntimeException {

	public PaymentGatewayException(String message) {
		super(message);
	}

	public PaymentGatewayException(String message, Throwable cause) {
		super(message, cause);
	}
}
