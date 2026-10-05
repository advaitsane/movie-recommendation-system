package com.movies.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.user.UserServiceApplication;
import com.movies.user.dto.LoginRequest;
import com.movies.user.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the full Spring context wires up correctly against a real Postgres
 * instance (an isolated Testcontainers-provisioned container, not whatever POSTGRES_URL points at
 * locally) — exercising the Flyway migration, JPA entity mapping, BCrypt hashing, JWT issuance,
 * and a real register-then-login round trip through the REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = UserServiceApplication.class)
@Import(UserServicePostgresTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("user-service Integration Test")
class UserServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Registers a user via REST, then logs in with the same credentials and gets a token")
    void registerThenLogin_roundTrips() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("integration-user@example.com")
                .password("a-real-password")
                .displayName("Integration User")
                .build();

        String registerResponse = mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("integration-user@example.com"))
                .andReturn().getResponse().getContentAsString();

        Long userId = objectMapper.readTree(registerResponse).at("/id").asLong();
        assertThat(userId).isPositive();

        LoginRequest loginRequest = LoginRequest.builder()
                .email("integration-user@example.com")
                .password("a-real-password")
                .build();

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.token").isNotEmpty());

        mockMvc.perform(get("/api/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Integration User"));
    }

    @Test
    @DisplayName("A second registration for the same email is rejected with 409")
    void duplicateRegistration_returnsConflict() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("dupe-user@example.com")
                .password("a-real-password")
                .build();

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Login with a wrong password against a real registered user returns 401")
    void loginWrongPassword_returnsUnauthorized() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("wrong-pw-user@example.com")
                .password("a-real-password")
                .build();

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = LoginRequest.builder()
                .email("wrong-pw-user@example.com")
                .password("not-the-right-password")
                .build();

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Runs here rather than in UserControllerTest because it depends on the running service's
     * real JSON mapper, which rejects unknown fields; the @WebMvcTest slice wires a different
     * mapper that silently ignores them.
     */
    @Test
    @DisplayName("An unknown JSON field (a typo like 'name' for 'displayName') is a 400, not a 500")
    void register_unknownField_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"typo@example.com\",\"password\":\"a-real-password\",\"name\":\"T\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value(
                        "Malformed request body: check the JSON syntax, field names and value types"));
    }
}
