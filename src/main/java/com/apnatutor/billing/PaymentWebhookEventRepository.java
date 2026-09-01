package com.apnatutor.billing;

import java.util.Optional;

import com.apnatutor.billing.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

	Optional<PaymentWebhookEvent> findByProviderAndEventId(String provider, String eventId);

	boolean existsByProviderAndEventId(String provider, String eventId);
}
