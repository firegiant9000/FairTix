package com.fairtix.config;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Browser security headers (exposure gate (a), RM:327). The CSP is limited to the JSON
 * API, because swagger-ui needs scripts.
 *
 * <p>Intended-red commit: {@code d6898fe}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SecurityHeadersTest {

  private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
  private static final String ORG = "00000000-0000-0000-0000-000000000001";
  private static final String EVENT = "00000000-0000-0000-0000-000000000002";

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

  /**
   * Review round 1: the print views are HTML opened in a tab, laid out by an inline
   * {@code <style>}; {@code default-src 'none'} stripped it (and the settlement's
   * signature lines). They keep everything else locked down. The header is written
   * on the 401 too, so no org fixture is needed.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "/api/organizations/" + ORG + "/reports/events/" + EVENT + "/settlement.html",
      "/api/organizations/" + ORG + "/reports/events/" + EVENT + "/dos.html",
      "/api/organizer/will-call/print"})
  void printViews_allowInlineStyleOnly(String path) throws Exception {
    mockMvc.perform(get(path))
        .andExpect(header().string("Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"));
  }

  @Test
  void reportJson_keepsTheStrictCsp() throws Exception {
    mockMvc.perform(get("/api/organizations/" + ORG + "/reports/events/" + EVENT + "/settlement"))
        .andExpect(header().string("Content-Security-Policy", API_CSP));
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
