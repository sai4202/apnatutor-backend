package com.apnatutor.catalog.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response shapes for the public catalog endpoints. */
public final class CatalogDtos {

	private CatalogDtos() {
	}

	@Schema(description = "A subject category or a selectable subject within one")
	public record SubjectNode(
			Long id,
			String name,
			@Schema(description = "URL segment; stable and safe to link to") String slug,
			@Schema(description = "True if a tutor can select this directly") boolean leaf,
			List<SubjectNode> children) {
	}

	@Schema(description = "An education board")
	public record BoardDto(Long id, String name, String slug) {
	}

	@Schema(description = "A class or stage")
	public record GradeLevelDto(Long id, String name, String slug) {
	}

	@Schema(description = "A city")
	public record CityDto(Long id, String name, String state, String slug) {
	}

	@Schema(description = "A locality within a city")
	public record LocationDto(
			Long id,
			String locality,
			String city,
			String slug,
			@Schema(example = "Gachibowli, Hyderabad") String displayName) {
	}
}
