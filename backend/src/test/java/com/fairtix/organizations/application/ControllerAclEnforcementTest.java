package com.fairtix.organizations.application;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.fairtix.events.api.EventController;
import com.fairtix.organizations.api.OrganizationController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflective guardrail for the M2 ACL contract from M2-01.
 *
 * Per the M2 implementation plan (Section 2A, "Potential issues — Cross-org
 * leak via EventService"): every mutating controller handler must carry
 * either {@link OrgScoped} (the request is authorized by org-role) or
 * {@link PublicEndpoint} (the handler is explicitly opted out, e.g. webhook,
 * health endpoint, or legacy ADMIN-only path pending migration).
 *
 * A missing annotation is the "easy to miss one" failure mode the plan
 * flags — this test fails the build before a leak ships.
 *
 * Mutation = POST / PUT / PATCH / DELETE. GETs are out of scope today; the
 * plan owes that broader sweep to a follow-up PR.
 */
class ControllerAclEnforcementTest {

  private static final List<Class<?>> CONTROLLERS_UNDER_GUARD = List.of(
      EventController.class,
      OrganizationController.class
  );

  @Test
  void everyMutationHandlerDeclaresAccessDecision() {
    List<String> unannotated = new ArrayList<>();

    for (Class<?> controller : CONTROLLERS_UNDER_GUARD) {
      boolean classOptedOut = hasAnnotation(controller, OrgScoped.class)
          || hasAnnotation(controller, PublicEndpoint.class);

      for (Method method : controller.getDeclaredMethods()) {
        if (!isMutationHandler(method)) continue;
        boolean methodAnnotated = method.isAnnotationPresent(OrgScoped.class)
            || method.isAnnotationPresent(PublicEndpoint.class);
        if (methodAnnotated || classOptedOut) continue;
        unannotated.add(controller.getSimpleName() + "#" + method.getName());
      }
    }

    assertThat(unannotated)
        .as("Mutation handlers missing @OrgScoped or @PublicEndpoint — declare the access decision "
            + "explicitly so cross-org leaks fail loud at compile-review time")
        .isEmpty();
  }

  private static boolean isMutationHandler(Method method) {
    return method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  private static boolean hasAnnotation(Class<?> type, Class<? extends Annotation> annotation) {
    return Arrays.stream(type.getAnnotations()).anyMatch(annotation::isInstance);
  }
}
