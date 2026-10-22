package com.fairtix.auth.api;

import com.fairtix.auth.application.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;

/**
 * Integration tests for {@link AuthController}.
 *
 * <p>All /auth/** endpoints are permitAll so no mock user is needed.
 * Tests verify registration, login, duplicate-email handling, HTTP-only cookies,
 * /auth/me, and /auth/logout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AuthControllerTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private JwtService jwtService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  // -------------------------------------------------------------------------
  // POST /auth/register
  // -------------------------------------------------------------------------

  @Test
  void register_success_returnsUserInfoAndCookie() throws Exception {
    String body = """
        {
          "email":    "newuser@test.com",
          "password": "Password123!"
        }
        """;

    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(notNullValue()))
        .andExpect(jsonPath("$.email").value("newuser@test.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void register_duplicateEmail_returns409() throws Exception {
    String body = """
        {
          "email":    "duplicate@test.com",
          "password": "Password123!"
        }
        """;

    // First registration succeeds
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    // Second registration with same email fails
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void register_cookieContainsValidJwt() throws Exception {
    String body = """
        {
          "email":    "jwtcheck@test.com",
          "password": "Password123!"
        }
        """;

    MvcResult result = mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("fairtix_token="));
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("SameSite=Lax"));

    // Extract token from Set-Cookie header
    String token = setCookie.split("fairtix_token=")[1].split(";")[0];
    assertDoesNotThrow(() -> jwtService.extractAllClaims(token));
    assertEquals("jwtcheck@test.com", jwtService.extractEmail(token));
  }

  // -------------------------------------------------------------------------
  // POST /auth/login
  // -------------------------------------------------------------------------

  @Test
  void login_success_returnsUserInfoAndCookie() throws Exception {
    // Register first
    String registerBody = """
        {
          "email":    "loginuser@test.com",
          "password": "Password123!"
        }
        """;
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody))
        .andExpect(status().isOk());

    // Login
    String loginBody = """
        {
          "email":    "loginuser@test.com",
          "password": "Password123!"
        }
        """;
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(notNullValue()))
        .andExpect(jsonPath("$.email").value("loginuser@test.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void login_badPassword_returns401() throws Exception {
    // Register first
    String registerBody = """
        {
          "email":    "badpw@test.com",
          "password": "Password123!"
        }
        """;
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody))
        .andExpect(status().isOk());

    // Login with wrong password
    String loginBody = """
        {
          "email":    "badpw@test.com",
          "password": "wrongpassword"
        }
        """;
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void login_nonExistentUser_returns401() throws Exception {
    String body = """
        {
          "email":    "nobody@test.com",
          "password": "Password123!"
        }
        """;

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
  }

  // -------------------------------------------------------------------------
  // GET /auth/me
  // -------------------------------------------------------------------------

  @Test
  void me_withValidCookie_returnsUserInfo() throws Exception {
    // Register to get a cookie
    String body = """
        {
          "email":    "mecheck@test.com",
          "password": "Password123!"
        }
        """;

    MvcResult registerResult = mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn();

    String setCookie = registerResult.getResponse().getHeader("Set-Cookie");
    String token = setCookie.split("fairtix_token=")[1].split(";")[0];

    // Use cookie to call /auth/me
    mockMvc.perform(get("/auth/me")
            .cookie(new Cookie("fairtix_token", token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("mecheck@test.com"))
        .andExpect(jsonPath("$.role").value("USER"));
  }

  /**
   * An anonymous caller is unauthenticated, not forbidden. Red on commit
   * {@code 415adf4}: {@code @PreAuthorize} denied the anonymous principal and
   * the advice mapped every {@code AccessDeniedException} to 403.
   */
  @Test
  void me_withoutCookie_returns401() throws Exception {
    mockMvc.perform(get("/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void resendVerification_withoutCookie_returns401() throws Exception {
    mockMvc.perform(post("/auth/resend-verification"))
        .andExpect(status().isUnauthorized());
  }

  // -------------------------------------------------------------------------
  // POST /auth/logout
  // -------------------------------------------------------------------------

  @Test
  void logout_clearsCookie() throws Exception {
    MvcResult result = mockMvc.perform(post("/auth/logout"))
        .andExpect(status().isNoContent())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("fairtix_token="));
    assertTrue(setCookie.contains("Max-Age=0"));
  }
}
