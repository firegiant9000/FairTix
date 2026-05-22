package com.fairtix.organizations.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.fairtix.auth.domain.CustomUserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@Component
public class OrgScopeInterceptor implements HandlerInterceptor {

  private static final String HEADER = "X-Organization-Id";

  private final OrganizationService orgService;

  public OrgScopeInterceptor(OrganizationService orgService) {
    this.orgService = orgService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;

    OrgScoped scoped = method.getMethodAnnotation(OrgScoped.class);
    if (scoped == null) scoped = method.getBeanType().getAnnotation(OrgScoped.class);

    UUID orgId = resolveOrgId(request);
    if (orgId != null) OrgContext.set(orgId);

    if (scoped == null) return true;

    if (orgId == null) {
      throw new AccessDeniedException("Missing organization context (X-Organization-Id header or orgId path variable)");
    }

    UUID userId = currentUserId();
    if (userId == null) {
      throw new AccessDeniedException("Authentication required");
    }
    orgService.requirePermission(userId, orgId, scoped.value());
    return true;
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
