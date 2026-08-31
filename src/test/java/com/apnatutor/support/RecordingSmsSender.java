package com.apnatutor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apnatutor.notification.SmsSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test SMS sender that captures messages so a test can read the OTP it just triggered.
 *
 * <p>Necessary because the code is never returned by the API and never logged — that is the whole
 * point of the design. Without capturing the outbound message there is no way to test the flow end
 * to end the way a real user experiences it.
 */
public class RecordingSmsSender implements SmsSender {

	private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

	private final List<Sent> sent = new ArrayList<>();

	public record Sent(String phone, String message) {
	}

	@Override
	public synchronized void send(String phone, String message) {
		sent.add(new Sent(phone, message));
	}

	/** The code from the most recent message to this number. */
	public synchronized Optional<String> latestCodeFor(String phone) {
		return sent.reversed().stream()
				.filter(s -> s.phone().equals(phone))
				.findFirst()
				.flatMap(s -> {
					Matcher matcher = SIX_DIGITS.matcher(s.message());
					return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
				});
	}

	public synchronized int countFor(String phone) {
		return (int) sent.stream().filter(s -> s.phone().equals(phone)).count();
	}

	public synchronized void clear() {
		sent.clear();
	}

	/**
	 * {@code @Primary} so it wins over {@link com.apnatutor.notification.ConsoleSmsSender}, which is
	 * also present because its condition defaults to active.
	 */
	@TestConfiguration
	public static class Config {

		@Bean
		@Primary
		public RecordingSmsSender recordingSmsSender() {
			return new RecordingSmsSender();
		}
	}
}
