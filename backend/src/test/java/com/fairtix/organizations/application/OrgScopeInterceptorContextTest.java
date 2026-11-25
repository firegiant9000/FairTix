package com.fairtix.organizations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.domain.OrgPermission;

/**
 * OrgContext is a thread-local on a pooled request thread. Spring calls afterCompletion only
 * for interceptors whose preHandle returned true, and for async requests not on the thread
 * that started them, so those paths must clear it themselves.
 */
class OrgScopeInterceptorContextTest {

  private final OrganizationService orgService = mock(OrganizationService.class);
  private final OrgScopeInterceptor interceptor =
      new OrgScopeInterceptor(orgService, mock(AuditService.class));
  private final UUID orgId = UUID.randomUUID();

  static class Handlers {
    @OrgScoped(OrgPermission.EVENTS_WRITE)
    public void scoped() {}

    public void unscoped() {}
  }

  @BeforeEach
  void signIn() {
    CustomUserPrincipal principal = new CustomUserPrincipal(
        UUID.randomUUID(), "user@example.test", "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @AfterEach
  void reset() {
    OrgContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void asyncStart_clearsOrgContextFromTheRequestThread() throws Exception {
    MockHttpServletRequest request = requestWithOrgHeader();
    Object handler = handler("unscoped");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
    assertThat(OrgContext.get()).isEqualTo(orgId);

    interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), handler);

    assertThat(OrgContext.get()).isNull();
  }

  @Test
  void deniedRequest_leavesNoOrgContext() throws Exception {
    doThrow(new AccessDeniedException("no"))
        .when(orgService).requirePermission(any(), any(), any());

    assertThatThrownBy(() ->
        interceptor.preHandle(requestWithOrgHeader(), new MockHttpServletResponse(), handler("scoped")))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(OrgContext.get()).isNull();
  }

  @Test
  void requestWithoutOrg_doesNotInheritAStaleValue() throws Exception {
    OrgContext.set(UUID.randomUUID()); // left behind by an earlier request on this thread

    interceptor.preHandle(new MockHttpServletRequest("GET", "/api/events"),
        new MockHttpServletResponse(), handler("unscoped"));

    assertThat(OrgContext.get()).isNull();
  }

  @Test
  void permittedScopedRequest_setsOrgContext() throws Exception {
    assertThat(interceptor.preHandle(requestWithOrgHeader(), new MockHttpServletResponse(),
        handler("scoped"))).isTrue();

    assertThat(OrgContext.get()).isEqualTo(orgId);
  }

  private MockHttpServletRequest requestWithOrgHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orgs/x/events");
    request.addHeader("X-Organization-Id", orgId.toString());
    return request;
  }

  private static HandlerMethod handler(String name) throws NoSuchMethodException {
    return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
  }
}
