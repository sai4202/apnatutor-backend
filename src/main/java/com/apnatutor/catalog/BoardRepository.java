package com.apnatutor.catalog;

import java.util.List;
import java.util.Optional;

import com.apnatutor.catalog.domain.Board;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardRepository extends JpaRepository<Board, Long> {

	List<Board> findByActiveTrueOrderByDisplayOrderAsc();

	Optional<Board> findBySlugAndActiveTrue(String slug);
}
