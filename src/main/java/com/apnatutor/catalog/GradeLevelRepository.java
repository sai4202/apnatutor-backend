package com.apnatutor.catalog;

import java.util.List;
import java.util.Optional;

import com.apnatutor.catalog.domain.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeLevelRepository extends JpaRepository<GradeLevel, Long> {

	List<GradeLevel> findByActiveTrueOrderByDisplayOrderAsc();

	Optional<GradeLevel> findBySlugAndActiveTrue(String slug);
}
