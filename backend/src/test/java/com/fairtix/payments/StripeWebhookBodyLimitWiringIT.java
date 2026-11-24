package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.support.PostgresIT;
import java.net.URI;
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

  /** The default of stripe.webhook-max-body-bytes. */
  static final int CAP = 1_048_576;

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
