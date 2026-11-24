package com.fairtix.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StripeWebhookFailureFilterTest {

  private SimpleMeterRegistry registry;
  private StripeWebhookFailureFilter filter;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    filter = new StripeWebhookFailureFilter(registry);
  }

  private double count(String endpoint) {
    return registry.find("stripe.webhook.failures").tag("endpoint", endpoint).counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
  }

  @Test
  void status409IsCountedWithStatusTag() throws Exception {
    run("/api/webhooks/stripe", status(409));
    assertThat(registry.find("stripe.webhook.failures")
        .tag("endpoint", "platform").tag("status", "409").counter().count()).isEqualTo(1);
  }

  @Test
  void getRequestsAreNotFiltered() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/webhooks/stripe");
    request.setRequestURI("/api/webhooks/stripe");
    filter.doFilter(request, new MockHttpServletResponse(), status(405));
    assertThat(count("platform")).isZero();
  }

  private MockHttpServletResponse run(String path, FilterChain chain) throws Exception {
    var request = new MockHttpServletRequest("POST", path);
    request.setRequestURI(path);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private FilterChain status(int s) {
    return (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(s);
  }

  @Test
  void status500OnPlatformEndpointIsCounted() throws Exception {
    run("/api/webhooks/stripe", status(500));
    assertThat(count("platform")).isEqualTo(1);
    assertThat(count("connect")).isZero();
  }

  @Test
  void status500OnConnectEndpointIsTaggedConnect() throws Exception {
    run("/api/webhooks/stripe-connect", status(503));
    assertThat(count("connect")).isEqualTo(1);
    assertThat(count("platform")).isZero();
  }

  @Test
  void thrownExceptionIsCountedAndRethrown() {
    FilterChain boom = (req, res) -> {
      throw new IllegalStateException("secret detail");
    };
    assertThatThrownBy(() -> run("/api/webhooks/stripe", boom))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("platform")).isEqualTo(1);
  }

  @Test
  void successAndClientErrorsAreNotCounted() throws Exception {
    run("/api/webhooks/stripe", status(200));
    run("/api/webhooks/stripe", status(400));
    run("/api/webhooks/stripe-connect", status(400));
    assertThat(count("platform")).isZero();
    assertThat(count("connect")).isZero();
  }

  @Test
  void otherPathsAreNotFiltered() throws Exception {
    run("/api/events", status(500));
    assertThat(count("platform")).isZero();
    assertThat(count("connect")).isZero();
  }
}
