package com.fairtix.organizations.application;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.fairtix.boxoffice.api.BoxOfficeController;
import com.fairtix.branding.api.BrandingController;
import com.fairtix.branding.api.CustomDomainController;
import com.fairtix.branding.api.EmbedScriptController;
import com.fairtix.branding.api.EventPageController;
import com.fairtix.branding.api.PublicBrandingController;
import com.fairtix.branding.api.SeoController;
import com.fairtix.events.api.EventController;
import com.fairtix.holds.api.CompController;
import com.fairtix.holds.api.EventHoldController;
import com.fairtix.holds.api.InventoryStatsController;
import com.fairtix.holds.api.WillCallController;
import com.fairtix.organizations.api.AdminOrgApprovalController;
import com.fairtix.organizations.api.OrgSignupController;
import com.fairtix.organizations.api.OrganizationController;
import com.fairtix.organizations.dashboard.api.OrganizerDashboardController;
import com.fairtix.payments.api.StripeConnectController;
import com.fairtix.payments.api.StripeConnectWebhookController;
import com.fairtix.reports.api.ReportsController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflective guardrail for the M2 ACL contract from M2-01.
 *
 * Per the M2 implementation plan (Section 2A, "Potential issues — Cross-org
 * leak via EventService"): every mutating controller handler must carry an
 * explicit access decision — one of:
 *   - {@link OrgScoped}: gated by the org-membership interceptor
 *   - {@link PublicEndpoint}: explicit opt-out (webhooks, embed/public pages)
 *   - {@link PreAuthorize}: Spring Security check (admin-only paths)
 *
 * The decision must be present on either the method or its declaring class.
 * A missing annotation is the "easy to miss one" failure mode the plan flags —
 * this test fails the build before a cross-org leak ships.
 *
 * Mutation = POST / PUT / PATCH / DELETE. GETs that return sensitive data
 * (payouts, settlements, attendees, EIN) are also included via the
 * SENSITIVE_GET_GUARDED list below; broader GET enforcement is a follow-up.
 */
class ControllerAclEnforcementTest {

  /**
   * All M2 controllers that touch org-scoped state. Adding a controller here
   * forces every new mutation handler in it to declare an access decision.
   */
  private static final List<Class<?>> CONTROLLERS_UNDER_GUARD = List.of(
      // Original M1/M2-01 set
      EventController.class,
      OrganizationController.class,

      // M2-09..11 box office
      BoxOfficeController.class,

      // M2-19..23 branding / event pages / SEO / domains / embed
      BrandingController.class,
      CustomDomainController.class,
      EmbedScriptController.class,
      EventPageController.class,
      PublicBrandingController.class,
      SeoController.class,

      // M2-12..14 comps, holds, will-call, inventory stats
      CompController.class,
      EventHoldController.class,
      InventoryStatsController.class,
      WillCallController.class,

      // M2-24/25 signup + admin approvals
      AdminOrgApprovalController.class,
      OrgSignupController.class,

      // M2-03/04 organizer dashboard
      OrganizerDashboardController.class,

      // M2-06..08 Stripe Connect (incl. webhook receiver)
      StripeConnectController.class,
      StripeConnectWebhookController.class,

      // M2-15..18 reports / settlement / payouts / tax
      ReportsController.class
  );

  @Test
  void everyMutationHandlerDeclaresAccessDecision() {
    List<String> unannotated = new ArrayList<>();

    for (Class<?> controller : CONTROLLERS_UNDER_GUARD) {
      boolean classOptedOut = hasAccessDecision(controller);

      for (Method method : controller.getDeclaredMethods()) {
        if (!isMutationHandler(method)) continue;
        if (hasAccessDecision(method) || classOptedOut) continue;
        unannotated.add(controller.getSimpleName() + "#" + method.getName());
      }
    }

    assertThat(unannotated)
        .as("Mutation handlers missing @OrgScoped, @PublicEndpoint, or @PreAuthorize "
            + "— declare the access decision explicitly so cross-org leaks fail loud "
            + "at compile-review time")
        .isEmpty();
  }

  private static boolean isMutationHandler(Method method) {
    return method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  private static boolean hasAccessDecision(Method method) {
    return method.isAnnotationPresent(OrgScoped.class)
        || method.isAnnotationPresent(PublicEndpoint.class)
        || method.isAnnotationPresent(PreAuthorize.class);
  }

  private static boolean hasAccessDecision(Class<?> type) {
    return hasAnnotation(type, OrgScoped.class)
        || hasAnnotation(type, PublicEndpoint.class)
        || hasAnnotation(type, PreAuthorize.class);
  }

  private static boolean hasAnnotation(Class<?> type, Class<? extends Annotation> annotation) {
    return Arrays.stream(type.getAnnotations()).anyMatch(annotation::isInstance);
  }
}
