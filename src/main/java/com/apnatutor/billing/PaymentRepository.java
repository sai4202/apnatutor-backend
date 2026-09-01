package com.apnatutor.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.apnatutor.billing.domain.Payment;
import com.apnatutor.billing.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByProviderOrderId(String providerOrderId);

	Optional<Payment> findByProviderPaymentId(String providerPaymentId);

	List<Payment> findByTutorIdOrderByCreatedAtDesc(Long tutorId);

	/**
	 * Loads a payment with a {@code SELECT … FOR UPDATE} row lock.
	 *
	 * <p>This is what makes crediting exactly-once under concurrent webhook deliveries. Razorpay may
	 * deliver the same event twice within milliseconds, and without the lock both would read
	 * {@code credited_at IS NULL}, both would conclude there is work to do, and the tutor would get
	 * two packages for one payment. The lock serialises them so the second sees the first's stamp.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.providerOrderId = :orderId")
	Optional<Payment> findByProviderOrderIdForUpdate(@Param("orderId") String orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.id = :id")
	Optional<Payment> findByIdForUpdate(@Param("id") Long id);

	/**
	 * Orders that were created and never resolved.
	 *
	 * <p>Feeds the reconciliation job. A webhook that never arrives is not hypothetical — a provider
	 * outage, an expired endpoint certificate or a deploy at the wrong moment all produce one, and a
	 * tutor whose money left their account is not going to wait patiently.
	 */
	List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);
}
