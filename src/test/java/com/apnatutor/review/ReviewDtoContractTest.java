package com.apnatutor.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import com.apnatutor.review.dto.ReviewDtos.PublicView;
import com.apnatutor.review.dto.ReviewDtos.RejectRequest;
import com.apnatutor.review.dto.ReviewDtos.ReplyRequest;
import com.apnatutor.review.dto.ReviewDtos.SubmitRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code M5-04.2} — an aggregate is never accepted from a client.
 *
 * <p>Stated as a property over every request record rather than as one payload against one
 * endpoint. A test that posts {@code avgRating: 5} to the submit route proves that route ignores
 * it today; this proves no request shape has a field for it to bind to in the first place, and it
 * fails the day somebody adds one.
 *
 * <p>A forged rating is the cheapest possible attack on a marketplace whose ranking is the
 * product, so the defence should be structural, not a filter somebody has to remember to apply.
 */
class ReviewDtoContractTest {

	/** Anything a client must never set. Matched case-insensitively as a substring. */
	private static final List<String> FORBIDDEN =
			List.of("rating", "reviewcount", "status", "moderated");

	@Test
	@DisplayName("no request record carries a derived or moderator-owned field")
	void requestsCannotCarryAggregates() {
		for (Class<?> request : List.of(SubmitRequest.class, ReplyRequest.class,
				RejectRequest.class)) {

			List<String> offending = Arrays.stream(request.getRecordComponents())
					.map(RecordComponent::getName)
					// "rating" itself is the one thing a review legitimately submits; it is the
					// input to the aggregate, not the aggregate.
					.filter(name -> !name.equals("rating"))
					.filter(name -> FORBIDDEN.stream()
							.anyMatch(banned -> name.toLowerCase().contains(banned)))
					.toList();

			assertThat(offending)
					.as("%s must not let a client set a derived or moderator-owned field",
							request.getSimpleName())
					.isEmpty();
		}
	}

	@Test
	@DisplayName("the public view cannot represent an unapproved review")
	void publicViewHasNoModerationFields() {
		List<String> names = Arrays.stream(PublicView.class.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();

		// No status and no studentId: a pending review is not something this record can express,
		// and a reviewer cannot be identified from it.
		assertThat(names).doesNotContain("status", "studentId", "rejectionReason", "moderatedBy");
		assertThat(names).contains("reviewerName");
	}
}
