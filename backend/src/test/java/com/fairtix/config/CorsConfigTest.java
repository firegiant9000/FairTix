package com.fairtix.config;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * CORS preflights against the real security chain (exposure gate (a), RM:327). The
 * test context uses {@code AllowedOrigins}' default list, which includes the CRA dev
 * origin {@code http://localhost:3000}.
 *
 * <p>Intended-red commit: {@code TBD}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CorsConfigTest {

  private static final String SPA = "http://localhost:3000";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** The headers the SPA actually sends: JSON bodies and the organizer org scope. */
  @Test
  void preflight_fromAllowedOrigin_allowsCredentialsAndTheSpaHeaders() throws Exception {
    mockMvc.perform(options("/api/organizer/comps")
            .header("Origin", SPA)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-organization-id"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", SPA))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("x-organization-id")));
  }

  /** API clients send a Bearer token instead of the cookie. */
  @Test
  void preflight_withAuthorizationHeader_isAllowed() throws Exception {
    mockMvc.perform(options("/api/orders")
            .header("Origin", SPA)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", SPA));
  }

  @Test
  void preflight_fromOtherOrigin_isRejectedWithoutCorsHeaders() throws Exception {
    mockMvc.perform(options("/auth/refresh")
            .header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
  }

  /** {@code allowedHeaders("*")} with credentials echoed any requested header back. */
  @Test
  void preflight_withUnlistedHeader_isRejected() throws Exception {
    mockMvc.perform(options("/api/orders")
            .header("Origin", SPA)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-anything"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
