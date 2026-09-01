package com.apnatutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// Enables the requirement expiry job and, later, credit expiry. Without this the
// @Scheduled annotations are silently ignored — nothing fails, the work just
// never happens.
@EnableScheduling
public class ApnatutorApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApnatutorApplication.class, args);
	}

}
