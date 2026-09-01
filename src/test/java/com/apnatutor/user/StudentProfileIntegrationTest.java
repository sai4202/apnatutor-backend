package com.apnatutor.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.user.domain.UserRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class StudentProfileIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private LocationRepository locations;

	private Long cityId;

	@BeforeEach
	void setUp() {
		sms.clear();
		cityId = locations.findByCityLevelTrueAndActiveTrueOrderByDisplayOrderAsc()
				.getFirst().getId();
	}

	@Test
	@DisplayName("a profile is created empty on first read")
	void createdOnFirstRead() throws Exception {
		String token = signIn("9500000001", UserRole.STUDENT);

		mockMvc.perform(get("/api/v1/student/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").doesNotExist());
	}

	@Test
	@DisplayName("name and location can be set, and the location name comes back resolved")
	void updatesProfile() throws Exception {
		String token = signIn("9500000002", UserRole.STUDENT);

		mockMvc.perform(put("/api/v1/student/profile")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Priya Sharma\",\"locationId\":" + cityId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Priya Sharma"))
				.andExpect(jsonPath("$.locationName").isNotEmpty());
	}

	@Test
	@DisplayName("an unknown location is rejected rather than stored")
	void rejectsUnknownLocation() throws Exception {
		String token = signIn("9500000003", UserRole.STUDENT);

		mockMvc.perform(put("/api/v1/student/profile")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Priya\",\"locationId\":999999}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("a tutor cannot reach student profile endpoints")
	void tutorCannotAccess() throws Exception {
		String tutorToken = signIn("9500000004", UserRole.TUTOR);

		mockMvc.perform(get("/api/v1/student/profile")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("students are isolated from each other")
	void studentsAreIsolated() throws Exception {
		String tokenA = signIn("9500000005", UserRole.STUDENT);
		String tokenB = signIn("9500000006", UserRole.STUDENT);

		mockMvc.perform(put("/api/v1/student/profile")
				.header("Authorization", "Bearer " + tokenA)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Student A\"}")).andExpect(status().isOk());

		// No endpoint takes a profile id, so B has no way to address A's profile at all.
		mockMvc.perform(get("/api/v1/student/profile").header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").doesNotExist());
	}

	private String signIn(String phone, UserRole role) throws Exception {
		mockMvc.perform(post("/api/v1/auth/otp/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		String body = mockMvc.perform(post("/api/v1/auth/otp/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"%s\",\"code\":\"%s\",\"role\":\"%s\"}"
								.formatted(phone, code, role.name())))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return JsonPath.read(body, "$.accessToken");
	}
}
