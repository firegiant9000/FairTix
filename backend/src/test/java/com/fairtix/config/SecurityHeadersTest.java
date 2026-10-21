package com.fairtix.config;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Browser security headers (exposure gate (a), RM:327). The CSP is limited to the JSON
 * API, because swagger-ui needs scripts.
 *
 * <p>Intended-red commit: {@code TBD}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SecurityHeadersTest {

  private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void apiResponse_hasReferrerPolicyAndCsp() throws Exception {
    mockMvc.perform(get("/api/events"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(header().string("Content-Security-Policy", API_CSP));
  }

  @Test
  void authResponse_hasCsp() throws Exception {
    mockMvc.perform(post("/auth/logout"))
        .andExpect(header().string("Content-Security-Policy", API_CSP));
  }

  @Test
  void apiDocs_haveNoCsp_butKeepReferrerPolicy() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(header().doesNotExist("Content-Security-Policy"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
  }

  /** Lookalike prefixes are not the API. */
  @Test
  void apiLookalikePath_hasNoCsp() throws Exception {
    mockMvc.perform(get("/apix"))
        .andExpect(header().doesNotExist("Content-Security-Policy"));
  }

  /** Behind the ALB, isSecure() comes from X-Forwarded-Proto (#263's RemoteIpValve). */
  @Test
  void secureRequest_hasHstsForAYearWithSubdomains() throws Exception {
    mockMvc.perform(get("/api/events").secure(true))
        .andExpect(header().string("Strict-Transport-Security",
            allOf(containsString("max-age=31536000"), containsString("includeSubDomains"))));
  }

  @Test
  void plainHttpRequest_hasNoHsts() throws Exception {
    mockMvc.perform(get("/api/events"))
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
  }
}
