package com.apnatutor.user;

import java.util.Optional;

import com.apnatutor.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByPhone(String phone);

	boolean existsByPhone(String phone);

	// --- Admin user management (M5-05.3) --------------------------------------------------------

	/**
	 * The admin user list: optional role, status and phone filters, newest first.
	 *
	 * <p>Native SQL with explicit {@code CAST}s around every parameter. Postgres cannot infer the
	 * type of a parameter that appears only as {@code :p IS NULL}, and fails the whole statement
	 * with "could not determine data type" — the cast is what makes an omitted filter legal rather
	 * than a 500. The alternative, a {@code Specification}, buys type-safety this query does not
	 * need at the cost of the SQL no longer being readable in one place.
	 *
	 * <p>{@code phone} is matched with {@code LIKE}, so support can find an account from the last
	 * few digits a caller reads out — nobody recites {@code +91} on the phone.
	 *
	 * <p>Backed by {@code users_role_status_created_idx} (V17) for the common role+status browse.
	 */
	@Query(value = """
			SELECT u.* FROM users u
			WHERE (CAST(:role AS varchar) IS NULL OR u.role = CAST(:role AS varchar))
			  AND (CAST(:status AS varchar) IS NULL OR u.status = CAST(:status AS varchar))
			  AND (CAST(:phone AS varchar) IS NULL OR u.phone LIKE CAST(:phone AS varchar))
			ORDER BY u.created_at DESC
			""",
			countQuery = """
			SELECT COUNT(*) FROM users u
			WHERE (CAST(:role AS varchar) IS NULL OR u.role = CAST(:role AS varchar))
			  AND (CAST(:status AS varchar) IS NULL OR u.status = CAST(:status AS varchar))
			  AND (CAST(:phone AS varchar) IS NULL OR u.phone LIKE CAST(:phone AS varchar))
			""",
			nativeQuery = true)
	Page<User> search(
			@Param("role") String role,
			@Param("status") String status,
			@Param("phone") String phone,
			Pageable pageable);
}
