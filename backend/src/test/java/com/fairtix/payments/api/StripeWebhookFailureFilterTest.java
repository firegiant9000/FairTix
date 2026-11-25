package com.fairtix.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StripeWebhookFailureFilterTest {

  private static final String PLATFORM = "/api/webhooks/stripe";
  private static final String CONNECT = "/api/webhooks/stripe-connect";

  private SimpleMeterRegistry registry;
  private StripeWebhookFailureFilter filter;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    filter = new StripeWebhookFailureFilter(registry);
  }

  private double total() {
    return registry.find("stripe.webhook.failures").counters().stream()
        .mapToDouble(Counter::count).sum();
  }

  private double count(String endpoint, int status) {
    Counter c = registry.find("stripe.webhook.failures")
        .tag("endpoint", endpoint).tag("status", String.valueOf(status)).counter();
    return c == null ? 0 : c.count();
  }

  /** Runs the filter; the chain optionally marks the request verified, then sets a status. */
  private void run(String method, String path, String verifiedAs, FilterChain after)
      throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    FilterChain chain = (req, res) -> {
      if (verifiedAs != null) {
        req.setAttribute(StripeWebhookFailureFilter.VERIFIED_ENDPOINT_ATTR, verifiedAs);
      }
      after.doFilter(req, res);
    };
    filter.doFilter(request, new MockHttpServletResponse(), chain);
  }

  private static FilterChain status(int s) {
    return (req, res) -> ((HttpServletResponse) res).setStatus(s);
  }

  private static FilterChain boom() {
    return (req, res) -> {
      throw new IllegalStateException("secret detail");
    };
  }

  @Test
  void unverifiedRequestsAreNeverCounted() throws Exception {
    run("POST", PLATFORM, null, status(500));
    run("POST", PLATFORM, null, status(409));
    run("POST", CONNECT, null, status(415));
    assertThatThrownBy(() -> run("POST", PLATFORM, null, boom()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(total()).isZero();
  }

  @Test
  void verifiedFailuresAreCountedWithEndpointFromAttribute() throws Exception {
    run("POST", PLATFORM, "platform", status(409));
    run("POST", PLATFORM, "platform", status(400));
    run("POST", PLATFORM, "platform", status(500));
    run("POST", CONNECT, "connect", status(503));
    assertThat(count("platform", 409)).isEqualTo(1);
    assertThat(count("platform", 400)).isEqualTo(1);
    assertThat(count("platform", 500)).isEqualTo(1);
    assertThat(count("connect", 503)).isEqualTo(1);
  }

  @Test
  void verifiedThrownExceptionIsCountedAndRethrown() {
    assertThatThrownBy(() -> run("POST", CONNECT, "connect", boom()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("connect", 500)).isEqualTo(1);
  }

  @Test
  void verifiedSuccessIsNotCounted() throws Exception {
    run("POST", PLATFORM, "platform", status(200));
    run("POST", PLATFORM, "platform", status(204));
    assertThat(total()).isZero();
  }

  @Test
  void otherPathsAndMethodsAreNotFiltered() throws Exception {
    run("POST", "/api/events", "platform", status(500));
    run("GET", PLATFORM, "platform", status(405));
    assertThat(total()).isZero();
  }
}
