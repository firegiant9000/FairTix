package com.fairtix.payments.api;

import com.fairtix.config.SkipBodyDrainConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Caps the body of the two Stripe webhook endpoints. Their controllers bind the whole body as
 * a String before checking the signature, and the routes are public, so without a cap anyone
 * could make the server buffer an arbitrarily large body.
 *
 * <p>A POST with no Content-Length (chunked) is answered 411 without reading the body: inside
 * one {@code read()}, Tomcat's chunked decoder can block for a trickled chunk header or trailer
 * for days, where no deadline can be checked. Stripe sends a Content-Length. A POST whose
 * Content-Length exceeds the cap is answered 413 without reading the body. Otherwise at most
 * cap + 1 bytes are read (Tomcat stops at the declared length anyway); more than the cap is
 * answered 413, and anything else reaches the controller as an in-memory copy of the exact
 * bytes, which the signature covers.
 *
 * <p>A body not complete after {@code stripe.webhook-body-read-timeout-ms} (default 10 s) is
 * answered 408. The check runs after each read returns, so a sender trickling bytes holds a
 * worker thread for at most the timeout plus one socket read timeout (Tomcat's connection
 * timeout: 60 s unless {@code server.tomcat.connection-timeout} is set), not indefinitely.
 * Every rejected request, and one whose body read fails (a socket timeout or client abort), is
 * passed to {@link SkipBodyDrainConfig#mark}, so Tomcat doesn't read the rest of the body after
 * the response and the connection closes. Stripe sends the whole body at once. A reverse proxy
 * that buffers request bodies (DEPLOY.md) keeps the trickle off the worker threads entirely.
 *
 * <p>Runs after {@code RateLimitFilter}, so a throttled caller is turned away before this
 * filter reads any body (that filter marks its own rejections too). A legitimate Stripe event over the cap would be retried by Stripe and keep failing;
 * the WARN line (path, cap, declared length and whether a signature header was sent; no client
 * IP, no header value) is the trace of that.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class StripeWebhookBodyLimitFilter extends OncePerRequestFilter {

  private static final String PLATFORM_PATH = "/api/webhooks/stripe";
  private static final String CONNECT_PATH = "/api/webhooks/stripe-connect";
  private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookBodyLimitFilter.class);

  /** Default of {@code stripe.webhook-max-body-bytes}. */
  public static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;
  /** Default of {@code stripe.webhook-body-read-timeout-ms}. */
  public static final long DEFAULT_READ_TIMEOUT_MS = 10_000;
  private static final long MAX_READ_TIMEOUT_MS = 300_000;

  private final int maxBodyBytes;
  private final long readTimeoutMs;
  private final long readTimeoutNanos;
  private final LongSupplier nanoTime;

  @Autowired
  public StripeWebhookBodyLimitFilter(
      @Value("${stripe.webhook-max-body-bytes:" + DEFAULT_MAX_BODY_BYTES + "}") int maxBodyBytes,
      @Value("${stripe.webhook-body-read-timeout-ms:" + DEFAULT_READ_TIMEOUT_MS + "}")
      long readTimeoutMs) {
    this(maxBodyBytes, readTimeoutMs, System::nanoTime);
  }

  StripeWebhookBodyLimitFilter(int maxBodyBytes, long readTimeoutMs, LongSupplier nanoTime) {
    if (maxBodyBytes <= 0 || maxBodyBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "stripe.webhook-max-body-bytes must be between 1 and " + (Integer.MAX_VALUE - 1));
    }
    // Bounded above so toNanos can't saturate and silently switch the deadline off.
    if (readTimeoutMs <= 0 || readTimeoutMs > MAX_READ_TIMEOUT_MS) {
      throw new IllegalArgumentException(
          "stripe.webhook-body-read-timeout-ms must be between 1 and " + MAX_READ_TIMEOUT_MS);
    }
    this.maxBodyBytes = maxBodyBytes;
    this.readTimeoutMs = readTimeoutMs;
    this.readTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
    this.nanoTime = nanoTime;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = PATH_HELPER.getPathWithinApplication(request);
    return !PLATFORM_PATH.equals(path) && !CONNECT_PATH.equals(path);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (request.getContentLengthLong() < 0) {
      log.warn("Stripe webhook without Content-Length rejected path={} signed={}",
          PATH_HELPER.getPathWithinApplication(request),
          request.getHeader("Stripe-Signature") != null);
      refuse(request, response, HttpStatus.LENGTH_REQUIRED);
      return;
    }
    if (request.getContentLengthLong() > maxBodyBytes) {
      reject(request, response);
      return;
    }
    byte[] body;
    try {
      body = readWithinDeadline(request.getInputStream());
    } catch (IOException e) {
      // A socket read timeout or client abort: Tomcat has already set a 4xx, but would still
      // drain the rest of the body unless told not to.
      SkipBodyDrainConfig.mark(request);
      throw e;
    }
    if (body == null) {
      log.warn("Stripe webhook body not received within {} ms path={} declaredLength={}",
          readTimeoutMs, PATH_HELPER.getPathWithinApplication(request),
          request.getContentLengthLong());
      refuse(request, response, HttpStatus.REQUEST_TIMEOUT);
      return;
    }
    if (body.length > maxBodyBytes) {
      reject(request, response);
      return;
    }
    filterChain.doFilter(new CachedBodyRequest(request, body), response);
  }

  /**
   * Reads at most cap + 1 bytes, or returns null once the body has taken longer than the read
   * timeout (checked after every read, end of stream included).
   */
  private byte[] readWithinDeadline(InputStream in) throws IOException {
    long start = nanoTime.getAsLong();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int limit = maxBodyBytes + 1;
    while (out.size() < limit) {
      int n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
      if (nanoTime.getAsLong() - start > readTimeoutNanos) {
        return null;
      }
      if (n < 0) {
        break;
      }
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  // signed=true means only that a Stripe-Signature header was sent (its value is never logged).
  // It is unverified, since the body can't be checked without reading it, and any client can
  // send one: a hint for triage, not evidence that Stripe sent it.
  private void reject(HttpServletRequest request, HttpServletResponse response) {
    log.warn("Stripe webhook body over {} bytes rejected path={} declaredLength={} signed={}",
        maxBodyBytes, PATH_HELPER.getPathWithinApplication(request),
        request.getContentLengthLong(), request.getHeader("Stripe-Signature") != null);
    refuse(request, response, HttpStatus.CONTENT_TOO_LARGE);
  }

  private static void refuse(HttpServletRequest request, HttpServletResponse response,
      HttpStatus status) {
    SkipBodyDrainConfig.mark(request);
    response.setStatus(status.value());
  }

  /** Serves the already-read body to the controller. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("synchronous body only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
