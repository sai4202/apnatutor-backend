package com.apnatutor.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * The pagination envelope every list endpoint returns (SOURCE_OF_TRUTH.md section 6).
 *
 * <p>Spring's own {@code Page} is deliberately not serialised directly: its JSON shape is an
 * implementation detail that has changed between Spring versions, and it drags entity internals
 * along with it. This is a fixed, documented contract that we control.
 *
 * @param content the items on this page
 * @param page zero-based page index
 * @param size requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages total number of pages
 */
public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(
				page.getContent(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}

	/**
	 * Maps a page of entities to a page of DTOs.
	 *
	 * <p>The overwhelmingly common case: a repository returns entities, and the controller must
	 * return DTOs because entities never leave the service layer. Having this here stops every
	 * call site reimplementing the same map-and-rewrap.
	 */
	public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
		return new PageResponse<>(
				page.getContent().stream().map(mapper).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
