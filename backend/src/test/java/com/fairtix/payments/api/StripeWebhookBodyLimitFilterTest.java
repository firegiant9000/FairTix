package com.fairtix.payments.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The webhook controllers bind the whole body as a String before the signature check, so
 * without a cap an unsigned request of any size is read into the heap.
 */
class StripeWebhookBodyLimitFilterTest {

  private static final int CAP = 64;

  private final StripeWebhookBodyLimitFilter filter = new StripeWebhookBodyLimitFilter(CAP);

  @ParameterizedTest
  @ValueSource(strings = {"/api/webhooks/stripe", "/api/webhooks/stripe-connect"})
  void declaredLengthOverCap_isRejectedWithoutCallingTheController(String path) throws Exception {
    MockHttpServletRequest request = post(path, new byte[CAP + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(413);
    verify(chain, never()).doFilter(any(), any());
  }

  /** Chunked, or a Content-Length that understates the body: the bytes read decide. */
  @Test
  void undeclaredBodyOverCap_isRejectedWithoutCallingTheController() throws Exception {
    MockHttpServletRequest request = withoutContentLength("/api/webhooks/stripe", new byte[CAP + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(413);
    verify(chain, never()).doFilter(any(), any());
  }

  /** The signature covers the exact bytes, so the controller must see them unchanged. */
  @Test
  void bodyAtCap_reachesTheControllerByteForByte() throws Exception {
    byte[] body = "{\"note\":\"café ✓\"}".getBytes(StandardCharsets.UTF_8);
    byte[] padded = Arrays.copyOf(body, CAP);
    Arrays.fill(padded, body.length, CAP, (byte) ' ');
    MockHttpServletRequest request = withoutContentLength("/api/webhooks/stripe", padded);
    AtomicReference<ServletRequest> seen = new AtomicReference<>();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

    assertThat(seen.get().getInputStream().readAllBytes()).isEqualTo(padded);
    assertThat(seen.get().getContentLengthLong()).isEqualTo(CAP);
  }

  @Test
  void reader_decodesTheCachedBodyAsUtf8() throws Exception {
    String json = "{\"note\":\"café ✓\"}";
    MockHttpServletRequest request = post("/api/webhooks/stripe-connect",
        json.getBytes(StandardCharsets.UTF_8));
    AtomicReference<String> read = new AtomicReference<>();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
      try (BufferedReader reader = req.getReader()) {
        read.set(reader.lines().collect(Collectors.joining("\n")));
      }
    });

    assertThat(read.get()).isEqualTo(json);
  }

  /** Other routes, and GETs on the webhook paths, are not read or capped here. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/orders", "/api/webhooks/stripe-other", "/api/webhooks/stripe/x"})
  void otherPaths_passThroughUntouched(String path) throws Exception {
    MockHttpServletRequest request = post(path, new byte[CAP * 4]);
    AtomicReference<ServletRequest> seen = new AtomicReference<>();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

    assertThat(seen.get()).isSameAs(request);
  }

  @Test
  void getOnWebhookPath_passesThroughUntouched() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/webhooks/stripe");
    request.setContent(new byte[CAP * 4]);
    AtomicReference<ServletRequest> seen = new AtomicReference<>();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

    assertThat(seen.get()).isSameAs(request);
  }

  /** cap + 1 bytes are read, so the cap itself must leave room for the extra byte. */
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
  void invalidCap_failsStartup(int cap) {
    assertThatThrownBy(() -> new StripeWebhookBodyLimitFilter(cap))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MockHttpServletRequest post(String path, byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setContentType("application/json");
    request.setCharacterEncoding("UTF-8");
    request.setContent(body);
    return request;
  }

  private static MockHttpServletRequest withoutContentLength(String path, byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    request.setContentType("application/json");
    request.setContent(body);
    return request;
  }
}
