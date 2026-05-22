package com.fairtix.organizations.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fairtix.organizations.domain.OrgPermission;

/**
 * Marks a controller method as scoped to the active organization (from
 * X-Organization-Id header or the {@code orgId} path variable). The interceptor
 * checks the caller has {@link #value()} permission within that org. Platform
 * admins bypass the check.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface OrgScoped {
  OrgPermission value() default OrgPermission.EVENTS_READ;
}
