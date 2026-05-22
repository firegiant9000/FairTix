package com.fairtix.organizations.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.domain.OrgPermission;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@Component
public class OrgScopeInterceptor implements HandlerInterceptor {

  private static final String HEADER = "X-Organization-Id";

  private final OrganizationService orgService;
  private final AuditService auditService;

  public OrgScopeInterceptor(OrganizationService orgService, AuditService auditService) {
    this.orgService = orgService;
    this.auditService = auditService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;

    OrgScoped scoped = method.getMethodAnnotation(OrgScoped.class);
    if (scoped == null) scoped = method.getBeanType().getAnnotation(OrgScoped.class);

    UUID orgId = resolveOrgId(request);
    if (orgId != null) OrgContext.set(orgId);

    if (scoped == null) return true;

    UUID userId = currentUserId();

    if (orgId == null) {
      auditDenial(userId, null, scoped.value(), request, "missing-org-context");
      throw new AccessDeniedException("Missing organization context (X-Organization-Id header or orgId path variable)");
    }

    if (userId == null) {
      // Unauthenticated requests are rejected before audit can persist a NOT NULL user_id;
      // Spring Security will surface this as 401. Skip the audit row.
      throw new AccessDeniedException("Authentication required");
    }
    try {
      orgService.requirePermission(userId, orgId, scoped.value());
    } catch (AccessDeniedException denied) {
      auditDenial(userId, orgId, scoped.value(), request, "permission-denied");
      throw denied;
    }
    return true;
  }

  private void auditDenial(UUID userId, UUID orgId, OrgPermission permission,
                           HttpServletRequest request, String reason) {
    if (userId == null) return;
    String details = String.format("permission=%s reason=%s method=%s path=%s",
        permission, reason, request.getMethod(), request.getRequestURI());
    try {
      auditService.log(userId, "ORG_ACCESS_DENIED", "ORGANIZATION", orgId, details);
    } catch (RuntimeException ignored) {
      // Audit failure must not mask the 403 the caller is about to see.
    }
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    OrgContext.clear();
  }

  private UUID resolveOrgId(HttpServletRequest request) {
    String header = request.getHeader(HEADER);
    if (header != null && !header.isBlank()) {
      try { return UUID.fromString(header.trim()); } catch (IllegalArgumentException ignored) {}
    }
    Object pathVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (pathVars instanceof Map<?, ?> map) {
      Object orgId = map.get("orgId");
      if (orgId instanceof String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
      }
    }
    return null;
  }

  private UUID currentUserId() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) return null;
    Object principal = auth.getPrincipal();
    if (principal instanceof CustomUserPrincipal p) return p.getUserId();
    return null;
  }
}
