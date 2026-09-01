package com.apnatutor.user;

import java.util.Optional;

import com.apnatutor.user.domain.StudentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

	Optional<StudentProfile> findByUserId(Long userId);
}
