package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.auth.application.JwtService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * CSRF defence for cookie-authenticated requests (exposure gate (a), RM:317,
 * decision D3). The auth cookies are {@code SameSite=None} in every TLS profile, so a
 * cross-site page can make the browser send them. Unsafe methods that carry one must
 * come from an allowed origin (or the API's own), per the {@code Origin} header or,
 * failing that, the {@code Referer}.
 *
 * <p>Spring's CORS processor already rejected a request whose {@code Origin} is
 * foreign, with a plain-text 403. What it let through is a cookie request with no
 * {@code Origin}: no headers at all, or a foreign {@code Referer} only.
 *
 * <p>The test context uses {@code AllowedOrigins}' default list, which includes the
 * CRA dev origin {@code http://localhost:3000}. MockMvc's own origin is
 * {@code http://localhost}.
 *
 * <p>Intended-red commit: {@code e0ff17a}. There, the three foreign- and null-Origin
 * tests also asserted the filter's JSON code; the CORS filter answers those first, so
 * they now assert the 403 and that no cookie is set or cleared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class OriginCheckFilterTest {

  private static final String SPA = "http://localhost:3000";
  private static final String EVIL = "https://evil.example";

  @Autowired private WebApplicationContext context;
  @Autowired private JwtService jwtService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  // ---- Rejected ----

  /**
   * A foreign {@code Origin} is rejected by the CORS filter, which runs first, with
   * its own plain-text 403; the origin check is the second line for this case.
   */
  @Test
  void refresh_fromForeignOrigin_withCookie_isRejected() throws Exception {
    mockMvc.perform(post("/auth/refresh").cookie(refreshCookie("any")).header("Origin", EVIL))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void refresh_withCookie_andNoOriginOrReferer_isRejected() throws Exception {
    mockMvc.perform(post("/auth/refresh").cookie(refreshCookie("any")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_ORIGIN_REJECTED"));
  }

  @Test
  void refresh_withCookie_andForeignRefererOnly_isRejected() throws Exception {
    mockMvc.perform(post("/auth/refresh").cookie(refreshCookie("any"))
            .header("Referer", EVIL + "/page"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_ORIGIN_REJECTED"));
  }

  /** Sandboxed iframes and some redirects send the literal origin {@code null}. */
  @Test
  void refresh_withCookie_andNullOrigin_isRejected() throws Exception {
    mockMvc.perform(post("/auth/refresh").cookie(refreshCookie("any")).header("Origin", "null"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  /** A forged logout must not clear the victim's cookies. */
  @Test
  void logout_fromForeignOrigin_withAccessCookie_isRejected() throws Exception {
    mockMvc.perform(post("/auth/logout").cookie(new Cookie("fairtix_token", "any"))
            .header("Origin", EVIL))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void delete_withAccessCookie_andNoOrigin_isRejected() throws Exception {
    mockMvc.perform(delete("/api/holds/" + UUID.randomUUID())
            .cookie(new Cookie("fairtix_token", "any")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_ORIGIN_REJECTED"));
  }

  @Test
  void malformedReferer_isRejected() throws Exception {
    mockMvc.perform(post("/auth/logout").cookie(new Cookie("fairtix_token", "any"))
            .header("Referer", "not a url"))
        .andExpect(status().isForbidden());
  }

  // ---- Allowed ----

  /** The SPA treats any failed refresh as a sign-out, so its real origin must pass. */
  @Test
  void refresh_fromSpaOrigin_withRealCookie_succeeds() throws Exception {
    Cookie refresh = registerAndGetRefreshCookie("origin-ok@test.com");

    mockMvc.perform(post("/auth/refresh").cookie(refresh).header("Origin", SPA))
        .andExpect(status().isOk());
  }

  @Test
  void refresh_withSpaRefererOnly_succeeds() throws Exception {
    Cookie refresh = registerAndGetRefreshCookie("referer-ok@test.com");

    mockMvc.perform(post("/auth/refresh").cookie(refresh).header("Referer", SPA + "/events/1?x=y"))
        .andExpect(status().isOk());
  }

  /** Same-origin requests aren't cross-site, whatever the allow-list says. */
  @Test
  void logout_fromApiOwnOrigin_succeeds() throws Exception {
    mockMvc.perform(post("/auth/logout").cookie(new Cookie("fairtix_token", "any"))
            .header("Origin", "http://localhost"))
        .andExpect(status().isNoContent());
  }

  /** Review Focus 2: a Bearer header isn't sent by the browser on its own, so it isn't CSRF-able. */
  @Test
  void bearerOnlyPost_withoutOrigin_passes() throws Exception {
    String jwt = jwtService.generateToken(UUID.randomUUID(), "bearer@test.com", "USER");

    mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isNoContent());
  }

  /** Review Focus 3: Stripe calls server-to-server, with no cookie and no Origin. */
  @Test
  void stripeWebhook_withoutCookieOrOrigin_isNotRejectedByTheOriginCheck() throws Exception {
    int status = mockMvc.perform(post("/api/webhooks/stripe")
            .header("Stripe-Signature", "t=1,v1=bad")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andReturn().getResponse().getStatus();

    assertThat(status).isNotEqualTo(403);
  }

  @Test
  void unrelatedCookie_withoutOrigin_passes() throws Exception {
    mockMvc.perform(post("/auth/logout").cookie(new Cookie("_ga", "x")))
        .andExpect(status().isNoContent());
  }

  @Test
  void safeMethod_withCookie_andNoOrigin_isNotChecked() throws Exception {
    mockMvc.perform(get("/api/events").cookie(new Cookie("fairtix_token", "any")))
        .andExpect(status().isOk());
  }

  @Test
  void normalize_keepsSchemeHostAndNonDefaultPortOnly() {
    assertThat(OriginCheckFilter.normalize("https://App.example:443/path?q=1")).isEqualTo("https://App.example");
    assertThat(OriginCheckFilter.normalize("http://localhost:3000/events")).isEqualTo("http://localhost:3000");
    assertThat(OriginCheckFilter.normalize("HTTP://localhost:80")).isEqualTo("http://localhost");
    assertThat(OriginCheckFilter.normalize("null")).isNull();
    assertThat(OriginCheckFilter.normalize("not a url")).isNull();
    assertThat(OriginCheckFilter.normalize("")).isNull();
  }

  private Cookie registerAndGetRefreshCookie(String email) throws Exception {
    Cookie refresh = mockMvc.perform(post("/auth/register")
            .header("Origin", SPA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getCookie("fairtix_refresh");
    assertThat(refresh).isNotNull();
    return refreshCookie(refresh.getValue());
  }

  private static Cookie refreshCookie(String value) {
    return new Cookie("fairtix_refresh", value);
  }
}
