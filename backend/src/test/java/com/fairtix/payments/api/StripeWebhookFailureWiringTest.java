package com.fairtix.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.payments.infrastructure.StripeWebhookEventRepository;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.stripe.Stripe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Runs the real filter bean in front of the real controller and exception handler. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.webhook-secret=whsec_test123",
    "stripe.connect-webhook-secret=whsec_connect123"
})
class StripeWebhookFailureWiringTest {

  private static final String SECRET = "whsec_test123";
  private static final String CONNECT_SECRET = "whsec_connect123";

  @Autowired private WebApplicationContext context;
  @Autowired private StripeWebhookFailureFilter failureFilter;
  @Autowired private MeterRegistry registry;
  @MockitoBean private PaymentRecordRepository paymentRecordRepository;
  @MockitoBean private RefundRepository refundRepository;
  @MockitoBean private AuditService auditService;
  @MockitoBean private StripeWebhookEventRepository webhookEventRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters(failureFilter)
        .apply(springSecurity())
        .build();
    when(webhookEventRepository.insertIfAbsent(anyString(), anyString())).thenReturn(1);
  }

  private double failures() {
    return registry.find("stripe.webhook.failures").counters().stream()
        .mapToDouble(Counter::count).sum();
  }

  @Test
  void missingSignatureHeader_is400_andNotCounted() throws Exception {
    double before = failures();
    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(failures()).isEqualTo(before);
  }

  @Test
  void unsupportedContentType_isNotCounted() throws Exception {
    double before = failures();
    mockMvc.perform(post("/api/webhooks/stripe")
            .header("Content-Type", "x").content("{}"))
        .andExpect(status().is4xxClientError());
    assertThat(failures()).isEqualTo(before);
  }

  @Test
  void badSignature_is400_andNotCounted() throws Exception {
    double before = failures();
    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", "t=1,v1=bad").content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(failures()).isEqualTo(before);
  }

  @Test
  void handlerFailureAfterVerification_isCountedWithStatus() throws Exception {
    when(paymentRecordRepository.findByTransactionId("pi_boom"))
        .thenThrow(new IllegalStateException("boom"));
    String payload = """
        { "id": "evt_boom", "object": "event", "type": "payment_intent.succeeded",
          "api_version": "%s",
          "data": { "object": { "id": "pi_boom", "object": "payment_intent",
                    "amount": 5000, "currency": "usd", "status": "succeeded" } } }
        """.formatted(Stripe.API_VERSION);

    var result = mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", signature(payload))
            .content(payload))
        .andReturn();

    int httpStatus = result.getResponse().getStatus();
    assertThat(httpStatus).isNotEqualTo(200).isNotEqualTo(400);
    Counter counter = registry.find("stripe.webhook.failures")
        .tag("endpoint", "platform").tag("status", String.valueOf(httpStatus)).counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void connectHandlerFailureAfterVerification_isCountedAsConnect() throws Exception {
    when(webhookEventRepository.insertIfAbsent(eq("evt_connect_boom"), anyString()))
        .thenThrow(new IllegalStateException("boom"));
    String payload = """
        { "id": "evt_connect_boom", "object": "event",
          "type": "account.application.deauthorized", "account": "acct_boom",
          "api_version": "%s",
          "data": { "object": { "id": "ca_boom", "object": "application" } } }
        """.formatted(Stripe.API_VERSION);
    double before = connectFailures();

    var result = mockMvc.perform(post("/api/webhooks/stripe-connect")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", signature(payload, CONNECT_SECRET))
            .content(payload))
        .andReturn();

    assertThat(result.getResponse().getStatus()).isNotEqualTo(200).isNotEqualTo(400);
    assertThat(connectFailures()).isEqualTo(before + 1);
  }

  @Test
  void connectBadSignature_is400_andNotCounted() throws Exception {
    double before = failures();
    mockMvc.perform(post("/api/webhooks/stripe-connect")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", "t=1,v1=bad").content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(failures()).isEqualTo(before);
  }

  private double connectFailures() {
    return registry.find("stripe.webhook.failures").tag("endpoint", "connect").counters()
        .stream().mapToDouble(Counter::count).sum();
  }

  private static String signature(String payload) throws Exception {
    return signature(payload, SECRET);
  }

  private static String signature(String payload, String secret) throws Exception {
    long ts = Instant.now().getEpochSecond();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String sig = HexFormat.of().formatHex(
        mac.doFinal((ts + "." + payload).getBytes(StandardCharsets.UTF_8)));
    return "t=" + ts + ",v1=" + sig;
  }
}
