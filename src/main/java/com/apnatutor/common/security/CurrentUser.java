package com.apnatutor.common.security;

import com.apnatutor.user.domain.UserRole;

/**
 * The authenticated caller, resolved from the access token.
 *
 * <p>Injected straight into controller methods by {@link CurrentUserArgumentResolver}, so no
 * controller has to reach into the {@code SecurityContextHolder} or parse a JWT itself. That keeps
 * "who is calling" in one place instead of scattered across every handler.
 *
 * <p>Holds only what the token proves. Anything else — profile, wallet balance, verification level —
 * is looked up from the database, because a token minted 15 minutes ago is not evidence of current
 * state.
 */
public record CurrentUser(Long userId, UserRole role) {

	public boolean isTutor() {
		return role == UserRole.TUTOR;
	}

	public boolean isStudent() {
		return role == UserRole.STUDENT;
	}

	public boolean isAdmin() {
		return role == UserRole.ADMIN;
	}
}
