package com.apnatutor.lead;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lead pricing, tested exhaustively at every band boundary.
 *
 * <p>This decides what people are charged. An off-by-one at a boundary is not a rounding error —
 * it is a tutor paying 8 credits for a lead that should have cost 5, repeatedly, until someone
 * notices.
 */
class LeadPricingTest {

	@ParameterizedTest(name = "₹{0} in paise → {1} credits")
	@DisplayName("budget bands from SOURCE_OF_TRUTH §3.1")
	@CsvSource({
			// Band 1: under ₹2,000
			"0,        3",
			"100000,   3",
			"199999,   3",
			// Band 2: ₹2,000 – ₹4,999
			"200000,   5",
			"350000,   5",
			"499999,   5",
			// Band 3: ₹5,000 – ₹9,999
			"500000,   8",
			"750000,   8",
			"999999,   8",
			// Band 4: ₹10,000 and above
			"1000000, 12",
			"5000000, 12",
	})
	void pricesByBudgetBand(long budgetPaise, int expectedCredits) {
		assertThat(LeadPricing.creditsFor(budgetPaise, "STUDENT_HOME"))
				.isEqualTo(expectedCredits);
	}

	@ParameterizedTest(name = "boundary at {0} paise")
	@DisplayName("each boundary is exclusive at the bottom of the next band")
	@ValueSource(longs = { 200_000L, 500_000L, 1_000_000L })
	void boundariesAreExact(long boundary) {
		// The value one paise below a boundary must price lower than the boundary itself. This is
		// the specific mistake a >= / > slip produces, and it is invisible without a test.
		assertThat(LeadPricing.creditsFor(boundary - 1, "STUDENT_HOME"))
				.isLessThan(LeadPricing.creditsFor(boundary, "STUDENT_HOME"));
	}

	@ParameterizedTest(name = "₹{0} online → {1} credits")
	@DisplayName("online enquiries are discounted 20%, rounded up")
	@CsvSource({
			"100000,   3",   // 3 × 0.8 = 2.4 → 3
			"350000,   4",   // 5 × 0.8 = 4.0 → 4
			"750000,   7",   // 8 × 0.8 = 6.4 → 7
			"1500000, 10",   // 12 × 0.8 = 9.6 → 10
	})
	void discountsOnlineLeads(long budgetPaise, int expectedCredits) {
		assertThat(LeadPricing.creditsFor(budgetPaise, "ONLINE")).isEqualTo(expectedCredits);
	}

	@Test
	@DisplayName("rounding is up, never down")
	void roundsUp() {
		// Rounding down would let the cheapest band reach 2 and, with any future multiplier, zero.
		// A free lead is the business model given away.
		assertThat(LeadPricing.creditsFor(100_000L, "ONLINE")).isEqualTo(3);
	}

	@Test
	@DisplayName("a lead always costs at least one credit")
	void neverFree() {
		assertThat(LeadPricing.creditsFor(0L, "ONLINE")).isGreaterThanOrEqualTo(1);
		assertThat(LeadPricing.creditsFor(null, "ONLINE")).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("an unstated budget is priced at the lowest band, not the highest")
	void unstatedBudgetIsCheap() {
		// A parent who skipped the field is not signalling wealth. Charging the top rate for an
		// unknown makes tutors avoid exactly the enquiries most in need of a reply.
		assertThat(LeadPricing.creditsFor(null, "STUDENT_HOME")).isEqualTo(3);
	}

	@Test
	@DisplayName("in-person modes are not discounted")
	void inPersonIsFullPrice() {
		assertThat(LeadPricing.creditsFor(350_000L, "STUDENT_HOME")).isEqualTo(5);
		assertThat(LeadPricing.creditsFor(350_000L, "TUTOR_PLACE")).isEqualTo(5);
	}
}
