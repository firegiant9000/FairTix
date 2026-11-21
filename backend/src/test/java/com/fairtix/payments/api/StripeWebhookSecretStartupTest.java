package com.fairtix.payments.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripeConnectService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.payments.infrastructure.StripeWebhookEventRepository;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.reports.application.PayoutReportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * With Stripe on, a blank webhook signing secret fails startup. Before, each endpoint
 * logged a warning and then answered 200 to every delivery without processing it, so
 * Stripe never retried and the events were lost.
 *
 * <p>Red before the change: the check only logged.
 */
class StripeWebhookSecretStartupTest {

  @Test
  void platformWebhook_stripeOnWithoutSecret_failsStartup() {
    StripeWebhookController controller = platform(true, "");

    assertThatThrownBy(controller::requireSecretWhenEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STRIPE_WEBHOOK_SECRET");
  }

  @Test
  void platformWebhook_stripeOnWithSecret_starts() {
    assertThatCode(platform(true, "whsec_x")::requireSecretWhenEnabled).doesNotThrowAnyException();
  }

  @Test
  void platformWebhook_stripeOffWithoutSecret_starts() {
    assertThatCode(platform(false, "")::requireSecretWhenEnabled).doesNotThrowAnyException();
  }

  @Test
  void connectWebhook_stripeOnWithoutSecret_failsStartup() {
    StripeConnectWebhookController controller = connect(true, "  ");

    assertThatThrownBy(controller::requireSecretWhenEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STRIPE_CONNECT_WEBHOOK_SECRET");
  }

  @Test
  void connectWebhook_stripeOffWithoutSecret_starts() {
    assertThatCode(connect(false, "")::requireSecretWhenEnabled).doesNotThrowAnyException();
  }

  private StripeWebhookController platform(boolean enabled, String secret) {
    StripeWebhookController controller = new StripeWebhookController(
        mock(PaymentRecordRepository.class), mock(RefundRepository.class),
        mock(AuditService.class), mock(StripeWebhookEventRepository.class));
    ReflectionTestUtils.setField(controller, "stripeEnabled", enabled);
    ReflectionTestUtils.setField(controller, "webhookSecret", secret);
    return controller;
  }

  private StripeConnectWebhookController connect(boolean enabled, String secret) {
    StripeConnectWebhookController controller = new StripeConnectWebhookController(
        mock(OrganizationRepository.class), mock(StripeConnectService.class),
        mock(AuditService.class), mock(PayoutReportService.class),
        mock(StripeWebhookEventRepository.class));
    ReflectionTestUtils.setField(controller, "stripeEnabled", enabled);
    ReflectionTestUtils.setField(controller, "webhookSecret", secret);
    return controller;
  }
}
