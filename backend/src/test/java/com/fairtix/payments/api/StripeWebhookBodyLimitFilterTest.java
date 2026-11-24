package com.fairtix.payments.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
  private static final long TIMEOUT_MS = 10_000;

  /** The filter's nanoTime source; a slow body advances it as it is read. */
  private final AtomicLong nanos = new AtomicLong();

  private final StripeWebhookBodyLimitFilter filter =
      new StripeWebhookBodyLimitFilter(CAP, TIMEOUT_MS, nanos::get);

  /**
   * A sender trickling the body would otherwise hold a worker thread for as long as each read
   * stays inside the socket timeout. Past the deadline the request gets 408 and the controller
   * is never called.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void bodyStillArrivingAfterTheDeadline_isRejectedWith408(CapturedOutput output) throws Exception {
    MockHttpServletRequest request = trickling("/api/webhooks/stripe", CAP, 2_000);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(408);
    verify(chain, never()).doFilter(any(), any());
    assertThat(output.getOut()).contains("Stripe webhook body not received within 10000 ms");
  }

  /** The same trickle, finished inside the deadline, still reaches the controller intact. */
  @Test
  void bodyFinishedBeforeTheDeadline_reachesTheController() throws Exception {
    MockHttpServletRequest request = trickling("/api/webhooks/stripe-connect", 8, 1_000);
    AtomicReference<ServletRequest> seen = new AtomicReference<>();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

    assertThat(seen.get().getInputStream().readAllBytes()).hasSize(8);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void invalidTimeout_failsStartup(long timeoutMs) {
    assertThatThrownBy(() -> new StripeWebhookBodyLimitFilter(CAP, timeoutMs, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The main application.properties isn't loaded by the Spring tests (the test one shadows it),
   * so its values are pinned here to the code defaults the wiring IT relies on.
   */
  @Test
  void mainProperties_matchTheCodeDefaults() throws Exception {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
      props.load(in);
    }

    assertThat(props.getProperty("stripe.webhook-max-body-bytes"))
        .isEqualTo(String.valueOf(StripeWebhookBodyLimitFilter.DEFAULT_MAX_BODY_BYTES));
    assertThat(props.getProperty("stripe.webhook-body-read-timeout-ms"))
        .isEqualTo(String.valueOf(StripeWebhookBodyLimitFilter.DEFAULT_READ_TIMEOUT_MS));
  }

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

  /**
   * A signed delivery over the cap may be a real Stripe event that will keep failing: the WARN
   * says so, with the declared length, and never logs the signature value.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void rejection_logsDeclaredLengthAndWhetherSigned(CapturedOutput output) throws Exception {
    MockHttpServletRequest request = post("/api/webhooks/stripe", new byte[CAP + 1]);
    request.addHeader("Stripe-Signature", "t=1,v1=secretlooking");

    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    assertThat(output.getOut())
        .contains("path=/api/webhooks/stripe declaredLength=" + (CAP + 1) + " signed=true")
        .doesNotContain("secretlooking");
  }

  /** cap + 1 bytes are read, so the cap itself must leave room for the extra byte. */
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
  void invalidCap_failsStartup(int cap) {
    assertThatThrownBy(() -> new StripeWebhookBodyLimitFilter(cap, TIMEOUT_MS, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MockHttpServletRequest post(String path, byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setContentType("application/json");
    request.setCharacterEncoding("UTF-8");
    request.setContent(body);
    return request;
  }

  /** A body that arrives one byte per read, each read taking {@code millisPerByte}. */
  private MockHttpServletRequest trickling(String path, int bytes, long millisPerByte) {
    ByteArrayInputStream source = new ByteArrayInputStream(new byte[bytes]);
    ServletInputStream slow = new ServletInputStream() {
      @Override
      public int read() {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millisPerByte));
        return source.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        if (len == 0) {
          return 0;
        }
        int next = read();
        if (next < 0) {
          return -1;
        }
        b[off] = (byte) next;
        return 1;
      }

      @Override
      public boolean isFinished() {
        return source.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
      }
    };
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
      @Override
      public ServletInputStream getInputStream() {
        return slow;
      }
    };
    request.setContentType("application/json");
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
