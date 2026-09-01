package com.apnatutor.catalog;

import java.util.List;
import java.util.Optional;

import com.apnatutor.catalog.domain.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note these repositories are separate top-level interfaces rather than grouped as nested types in
 * one file: Spring Data's scanning does not detect repository interfaces nested inside a class, and
 * the failure is an unhelpful "no qualifying bean" at startup rather than anything pointing at the
 * cause.
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

	List<Subject> findByActiveTrueOrderByDisplayOrderAsc();

	Optional<Subject> findBySlugAndActiveTrue(String slug);

	List<Subject> findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc();
}
