package com.fairtix.organizations.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler as intentionally outside the org-scope ACL.
 *
 * Why: every state-mutating controller handler must either declare which org
 * it scopes to via {@link OrgScoped} or be explicitly opted-out via this
 * annotation, so the controller-scan enforcement test can fail the build when
 * a new endpoint slips through without an access decision. Use sparingly:
 * unauthenticated endpoints (webhooks, health, public listings, self-service
 * signup) and per-user endpoints that have no org dimension yet.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
  String value() default "";
}
