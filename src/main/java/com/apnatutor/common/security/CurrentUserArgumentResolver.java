package com.apnatutor.common.security;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.domain.UserRole;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code CurrentUser currentUser} and receive the authenticated caller.
 *
 * <p>Throws rather than injecting null when there is no authentication. A null principal reaching
 * controller code is how authorization checks get accidentally skipped — failing loudly means a
 * missing security rule shows up as a 401 in testing, not as an authorization bypass in production.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

	private static final String ROLE_CLAIM = "role";

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return CurrentUser.class.equals(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
		}

		try {
			return new CurrentUser(
					Long.valueOf(jwt.getSubject()),
					UserRole.valueOf(jwt.getClaimAsString(ROLE_CLAIM)));
		} catch (IllegalArgumentException | NullPointerException e) {
			// A structurally valid, correctly signed token whose claims we cannot read means the
			// token format changed under us. Treat it as unauthenticated rather than guessing.
			throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid token claims");
		}
	}
}
