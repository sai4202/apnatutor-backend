package com.apnatutor.billing.gateway;

import com.apnatutor.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks the payment gateway.
 *
 * <h2>Why this is a plain {@code if} rather than {@code @ConditionalOnProperty}</h2>
 *
 * <p>{@code apnatutor.razorpay.key-id} defaults to {@code ${RAZORPAY_KEY_ID:}} — an <em>empty</em>
 * value, not an absent one. {@code @ConditionalOnProperty} treats a property that exists but is
 * blank as present and would happily wire the real gateway with no credentials, which fails later
 * and confusingly at the first API call. An explicit check reads the way the decision actually
 * works.
 */
@Configuration
public class PaymentGatewayConfig {

	@Bean
	public PaymentGateway paymentGateway(AppProperties properties, ObjectMapper json) {
		return properties.razorpay().isConfigured()
				? new RazorpayGateway(properties, json)
				: new StubPaymentGateway();
	}
}
