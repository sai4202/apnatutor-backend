package com.apnatutor.catalog;

import java.util.List;
import java.util.Optional;

import com.apnatutor.catalog.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, Long> {

	List<Location> findByActiveTrueOrderByDisplayOrderAsc();

	/** Cities only — the top level of the location picker. */
	List<Location> findByCityLevelTrueAndActiveTrueOrderByDisplayOrderAsc();

	/** Localities within one city, found by the city's own name. */
	List<Location> findByCityAndCityLevelFalseAndActiveTrueOrderByDisplayOrderAsc(String city);

	Optional<Location> findBySlugAndActiveTrue(String slug);
}
