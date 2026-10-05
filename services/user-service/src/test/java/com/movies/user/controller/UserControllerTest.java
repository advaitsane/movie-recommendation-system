package com.movies.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.user.dto.LoginRequest;
import com.movies.user.dto.RecordActivityRequest;
import com.movies.user.dto.RegisterRequest;
import com.movies.user.event.UserActivityType;
import com.movies.user.exception.InvalidCredentialsException;
import com.movies.user.exception.ResourceNotFoundException;
import com.movies.user.exception.UserAlreadyExistsException;
import com.movies.user.model.User;
import com.movies.user.service.IUserService;
import com.movies.user.service.LoginResult;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@DisplayName("UserController Unit Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IUserService userService;

    private User sampleUser() {
        return User.builder()
                .id(1L).email("a@b.com").passwordHash("hashed").displayName("A")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("POST /api/users/register returns 201 with the created user, no password hash")
    void register_returnsCreated() throws Exception {
        when(userService.register(any())).thenReturn(sampleUser());

        RegisterRequest request = RegisterRequest.builder()
                .email("a@b.com").password("password1").displayName("A").build();

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/users/register returns 400 when password is too short")
    void register_shortPassword_returnsBadRequest() throws Exception {
        RegisterRequest request = RegisterRequest.builder().email("a@b.com").password("short").build();

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users/register returns 409 when the service reports a duplicate email")
    void register_duplicate_returnsConflict() throws Exception {
        when(userService.register(any()))
                .thenThrow(new UserAlreadyExistsException("An account with email 'a@b.com' already exists"));

        RegisterRequest request = RegisterRequest.builder().email("a@b.com").password("password1").build();

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/users/login returns 200 with a token on success")
    void login_success_returnsToken() throws Exception {
        when(userService.login(any())).thenReturn(new LoginResult(sampleUser(), "a.jwt.token", Instant.now()));

        LoginRequest request = LoginRequest.builder().email("a@b.com").password("password1").build();

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("a.jwt.token"))
                .andExpect(jsonPath("$.userId").value(1));
    }

    @Test
    @DisplayName("POST /api/users/login returns 401 on invalid credentials")
    void login_invalidCredentials_returnsUnauthorized() throws Exception {
        when(userService.login(any())).thenThrow(new InvalidCredentialsException("Invalid email or password"));

        LoginRequest request = LoginRequest.builder().email("a@b.com").password("wrong").build();

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users/{id} returns 404 when missing")
    void getUser_notFound_returns404() throws Exception {
        when(userService.getUserById(99L)).thenThrow(new ResourceNotFoundException("User not found: 99"));

        mockMvc.perform(get("/api/users/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/users/{id}/activity returns 202 and calls through to the service")
    void recordActivity_returnsAccepted() throws Exception {
        RecordActivityRequest request = RecordActivityRequest.builder()
                .type(UserActivityType.VIEW).movieId("movie-1").build();

        mockMvc.perform(post("/api/users/{id}/activity", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(userService).recordActivity(eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/users/{id}/activity returns 400 when type is missing")
    void recordActivity_missingType_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users/{id}/activity", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":\"movie-1\"}"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never()).recordActivity(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/users/{id}/activity returns 202 when the X-User-Id header matches the path id")
    void recordActivity_matchingUserIdHeader_returnsAccepted() throws Exception {
        RecordActivityRequest request = RecordActivityRequest.builder()
                .type(UserActivityType.VIEW).movieId("movie-1").build();

        mockMvc.perform(post("/api/users/{id}/activity", 1L)
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(userService).recordActivity(eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/users/{id}/activity returns 403 when the X-User-Id header doesn't match the path id")
    void recordActivity_mismatchedUserIdHeader_returnsForbidden() throws Exception {
        RecordActivityRequest request = RecordActivityRequest.builder()
                .type(UserActivityType.VIEW).movieId("movie-1").build();

        mockMvc.perform(post("/api/users/{id}/activity", 1L)
                        .header("X-User-Id", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never()).recordActivity(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/users/login returns 400, not 500, for malformed JSON")
    void login_malformedJson_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/users/{id} returns 400, not 500, for a non-numeric id")
    void getUser_nonNumericId_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/users/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Invalid value for parameter 'id'"));
    }

    @Test
    @DisplayName("an unsupported HTTP method returns 405, not 500")
    void unsupportedMethod_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", 1L))
                .andExpect(status().isMethodNotAllowed());
    }
}
