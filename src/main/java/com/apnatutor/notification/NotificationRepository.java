package com.apnatutor.notification;

import java.util.List;

import com.apnatutor.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

	long countByUserIdAndReadAtIsNull(Long userId);
}
