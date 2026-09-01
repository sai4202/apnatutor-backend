package com.apnatutor.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Masking is what makes a lead worth paying for. If a tutor can read a phone number without
 * unlocking, they never unlock.
 */
class ContactMaskingTest {

	@Test
	@DisplayName("a phone keeps only its last four digits")
	void masksPhone() {
		assertThat(ContactMasking.maskPhone("+919876543210")).isEqualTo("+91XXXXXX3210");
		assertThat(ContactMasking.maskPhone("+916000000001")).isEqualTo("+91XXXXXX0001");
	}

	@Test
	@DisplayName("the masked form never contains the hidden digits")
	void maskedPhoneHidesTheMiddle() {
		String masked = ContactMasking.maskPhone("+919876543210");

		// The point of the exercise: the six digits that identify the subscriber are gone.
		assertThat(masked).doesNotContain("987654");
		assertThat(masked).isNotEqualTo("+919876543210");
	}

	@Test
	@DisplayName("a name is reduced to a first name and an initial")
	void masksName() {
		assertThat(ContactMasking.maskName("Priya Sharma")).isEqualTo("Priya S.");
		assertThat(ContactMasking.maskName("Rahul Kumar Singh")).isEqualTo("Rahul K.");
		// A single name has nothing to reduce; showing it is no more identifying than not.
		assertThat(ContactMasking.maskName("Priya")).isEqualTo("Priya");
	}

	@Test
	@DisplayName("an email keeps its domain but not its local part")
	void masksEmail() {
		assertThat(ContactMasking.maskEmail("priya@example.com")).isEqualTo("p***a@example.com");
		assertThat(ContactMasking.maskEmail("ab@example.com")).isEqualTo("**@example.com");
	}

	@Test
	@DisplayName("degenerate input never throws")
	void handlesDegenerateInput() {
		// These run inside response mapping. An exception here would turn a masking helper into a
		// 500 on a page that was working.
		assertThat(ContactMasking.maskPhone(null)).isEqualTo("***");
		assertThat(ContactMasking.maskPhone("12")).isEqualTo("***");
		assertThat(ContactMasking.maskName(null)).isEqualTo("Student");
		assertThat(ContactMasking.maskName("   ")).isEqualTo("Student");
		assertThat(ContactMasking.maskEmail(null)).isEqualTo("***");
		assertThat(ContactMasking.maskEmail("not-an-email")).isEqualTo("***");
	}
}
