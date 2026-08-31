package com.apnatutor.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata, served at {@code /v3/api-docs} and browsable at {@code /swagger-ui.html}.
 *
 * <p>Declares the bearer scheme so the Swagger UI "Authorize" button works once M1-04 issues tokens
 * — without it every protected endpoint is untestable from the browser.
 *
 * <p>Note this is springdoc <strong>3.x</strong>. The 2.x line targets Spring Boot 3 / Spring
 * Framework 6 and is incompatible with this application.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI apnaTutorOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("ApnaTutor API")
						.version("v1")
						.description("""
								India-first tutor marketplace. Students post tuition requirements \
								for free; tutors spend credits to unlock contact details.

								Errors share one shape: a stable machine `code`, a human `message`, \
								and `fieldErrors` on validation failures. Clients must branch on \
								`code` — messages get reworded. See docs/SOURCE_OF_TRUTH.md.""")
						.contact(new Contact().name("ApnaTutor")))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Access token from /api/v1/auth. Expires in 15 minutes.")));
	}
}
