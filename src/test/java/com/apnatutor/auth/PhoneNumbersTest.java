package com.apnatutor.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit tests — no Spring context, so they run in milliseconds.
 *
 * <p>Normalisation matters more than it looks: if the same human's number can be stored two ways,
 * they can hold two accounts, split their reviews and credits across both, and sidestep the OTP
 * send-rate limit just by typing a space differently.
 */
class PhoneNumbersTest {

	@ParameterizedTest(name = "\"{0}\" normalises to {1}")
	@DisplayName("every common way of writing one number yields the same canonical form")
	@CsvSource({
			"9876543210,        +919876543210",
			"+919876543210,     +919876543210",
			"919876543210,      +919876543210",
			"09876543210,       +919876543210",
			"98765 43210,       +919876543210",
			"98765-43210,       +919876543210",
			"+91 98765 43210,   +919876543210",
			"(+91) 98765-43210, +919876543210",
			"6000000000,        +916000000000",
	})
	void normalisesToCanonicalForm(String input, String expected) {
		assertThat(PhoneNumbers.normalise(input)).contains(expected);
	}

	@ParameterizedTest(name = "\"{0}\" is rejected")
	@DisplayName("rejects anything that is not an Indian mobile number")
	@ValueSource(strings = {
			"1234567890",      // Indian mobiles start 6-9
			"5876543210",
			"987654321",       // too short
			"98765432101",     // too long
			"+1 555 123 4567", // not India
			"abcdefghij",
			"+91",
			"0000000000",
	})
	void rejectsInvalidNumbers(String input) {
		assertThat(PhoneNumbers.normalise(input)).isEmpty();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void rejectsNullAndBlank(String input) {
		assertThat(PhoneNumbers.normalise(input)).isEmpty();
	}

	@Test
	@DisplayName("masking keeps only the last four digits")
	void masksForLogging() {
		assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91XXXXXX3210");
	}

	@Test
	@DisplayName("masking never throws on unexpected input")
	void maskingIsDefensive() {
		// This runs inside log statements. A NullPointerException here would turn a routine log
		// line into a request failure, which is strictly worse than a vague mask.
		assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
		assertThat(PhoneNumbers.mask("12")).isEqualTo("***");
	}
}
