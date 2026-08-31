package com.apnatutor.common.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Symmetric (HS256) JWT signing and verification.
 *
 * <p>Symmetric rather than a public/private pair because one service both issues and verifies these
 * tokens. Asymmetric keys earn their extra complexity when a separate party must verify without
 * being able to mint — not the case here. Revisit if the API is ever split across services.
 *
 * <p>The secret is validated at startup by {@link AppProperties.Jwt}: under 32 bytes and the
 * application refuses to boot, because a short HMAC key is brute-forceable and the failure is
 * otherwise silent.
 */
@Configuration
public class JwtConfig {

	private static final String ROLE_CLAIM = "role";

	private final SecretKey secretKey;

	public JwtConfig(AppProperties properties) {
		this.secretKey = new SecretKeySpec(
				properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
	}

	/**
	 * Pinned to HS256. Without pinning, a decoder can be talked into accepting an algorithm the
	 * issuer never intended — the classic JWT confusion attack.
	 */
	@Bean
	JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(secretKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
	}

	/**
	 * Maps our single {@code role} claim onto a Spring Security authority.
	 *
	 * <p>The default converter reads {@code scope}/{@code scp}, which we do not issue, so without
	 * this every authenticated request would arrive with no authorities and every {@code
	 * hasRole(...)} check would fail.
	 */
	@Bean
	Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthorityPrefix("ROLE_");
		authorities.setAuthoritiesClaimName(ROLE_CLAIM);

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			String role = jwt.getClaimAsString(ROLE_CLAIM);
			return role == null
					? java.util.List.of()
					: java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
		});
		return converter;
	}
}
