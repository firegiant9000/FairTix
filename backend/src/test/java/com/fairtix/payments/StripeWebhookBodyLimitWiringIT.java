package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.payments.api.StripeWebhookBodyLimitFilter;
import com.fairtix.support.PostgresIT;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The webhook body cap on a real Tomcat. The ledger ITs add the filter to MockMvc by hand, so
 * they would still pass if it stopped being registered; this proves it sits in front of both
 * controllers in the running server.
 *
 * <p>No property overrides on purpose: the context is then the same cached one as
 * ForwardedHeadersIT's. A context of its own adds a connection pool against the shared
 * container and runs it out of connections. So this uses the default cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StripeWebhookBodyLimitWiringIT extends PostgresIT {

  /** Pinned to main application.properties by StripeWebhookBodyLimitFilterTest. */
  static final int CAP = StripeWebhookBodyLimitFilter.DEFAULT_MAX_BODY_BYTES;

  @LocalServerPort private int port;

  // HTTP/1.1: no h2c upgrade attempt on a POST whose body Tomcat may not read.
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @ParameterizedTest
  @ValueSource(strings = {"/api/webhooks/stripe", "/api/webhooks/stripe-connect"})
  void bodyOverCap_isRejectedWith413(String path) throws Exception {
    assertThat(post(path, CAP + 1).statusCode()).isEqualTo(413);
  }

  /** Stripe is off in this context, so a body under the cap reaches the controller's 200. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/webhooks/stripe", "/api/webhooks/stripe-connect"})
  void bodyAtCap_reachesTheController(String path) throws Exception {
    assertThat(post(path, CAP).statusCode()).isEqualTo(200);
  }

  /** A chunked body (no Content-Length) is refused before any of it is read. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/webhooks/stripe", "/api/webhooks/stripe-connect"})
  void chunkedBody_isRejectedWith411(String path) throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", "t=0,v1=unused")
            .POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream("{}".getBytes())))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(411);
  }

  /**
   * After a reject, Tomcat would otherwise keep reading the unsent body (up to its 2 MB
   * maxSwallowSize, with no overall deadline) on the worker thread. The headers here promise a
   * body that never comes, so the connection closes promptly only if that read is switched off.
   * readAllBytes returns on close; a held thread shows up as the socket timeout.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "Content-Length: " + (CAP + 1) + "|413",
      "Transfer-Encoding: chunked|411"})
  void rejectedRequest_closesWithoutReadingTheRestOfTheBody(String headerAndStatus)
      throws Exception {
    String[] parts = headerAndStatus.split("\\|");
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5_000);
      OutputStream out = socket.getOutputStream();
      out.write(("POST /api/webhooks/stripe HTTP/1.1\r\n"
          + "Host: localhost\r\n"
          + "Content-Type: application/json\r\n"
          + parts[0] + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();

      String response = new String(socket.getInputStream().readAllBytes(),
          StandardCharsets.US_ASCII);

      assertThat(response).startsWith("HTTP/1.1 " + parts[1]);
    }
  }

  private HttpResponse<String> post(String path, int bytes) throws Exception {
    byte[] body = " ".repeat(bytes).getBytes();
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", "t=0,v1=unused")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
