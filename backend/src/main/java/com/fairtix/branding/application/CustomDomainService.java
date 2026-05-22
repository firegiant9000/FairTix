package com.fairtix.branding.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.audit.application.AuditService;
import com.fairtix.branding.domain.OrgCustomDomain;
import com.fairtix.branding.infrastructure.OrgCustomDomainRepository;
import com.fairtix.common.ResourceNotFoundException;

/**
 * Custom-domain CNAME support (M2-22). Verification works via a TXT record
 * (_fairtix-verify.&lt;host&gt; = "fairtix-verify=&lt;token&gt;") that the org's
 * DNS admin adds; the worker that actually resolves DNS lives outside this PR
 * (defer to ops job in M3), but the verification *flow* — token issuance,
 * lookup, and confirmation — is plumbed end-to-end so the UI can show the
 * "copy this TXT record" instructions and a "check now" button.
 */
@Service
public class CustomDomainService {

  private static final SecureRandom RANDOM = new SecureRandom();
  // Conservative hostname pattern: labels separated by '.', alphanumerics + '-'
  // inside labels, no leading/trailing '-', total length capped.
  private static final Pattern HOSTNAME = Pattern.compile(
      "^(?=.{4,253}$)([a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

  private final OrgCustomDomainRepository domains;
  private final AuditService auditService;

  public CustomDomainService(OrgCustomDomainRepository domains, AuditService auditService) {
    this.domains = domains;
    this.auditService = auditService;
  }

  @Transactional
  public OrgCustomDomain add(UUID orgId, String hostname, UUID actorUserId) {
    String normalized = normalize(hostname);
    if (domains.existsByHostnameIgnoreCase(normalized)) {
      throw new IllegalArgumentException("Hostname is already claimed");
    }
    String token = randomToken();
    OrgCustomDomain saved = domains.save(new OrgCustomDomain(orgId, normalized, token));
    auditService.log(actorUserId, "ORG_CUSTOM_DOMAIN_ADDED", "ORG_CUSTOM_DOMAIN", saved.getId(),
        "orgId=" + orgId + " host=" + normalized);
    return saved;
  }

  public List<OrgCustomDomain> list(UUID orgId) {
    return domains.findAllByOrganizationId(orgId);
  }

  @Transactional
  public void delete(UUID orgId, UUID domainId, UUID actorUserId) {
    OrgCustomDomain d = domains.findById(domainId)
        .orElseThrow(() -> new ResourceNotFoundException("Domain not found"));
    if (!d.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Domain not found in this organization");
    }
    domains.delete(d);
    auditService.log(actorUserId, "ORG_CUSTOM_DOMAIN_REMOVED", "ORG_CUSTOM_DOMAIN", d.getId(),
        "orgId=" + orgId + " host=" + d.getHostname());
  }

  /**
   * Verifies the TXT record by delegating to the supplied resolver. Splitting
   * DNS out as a function makes the test path trivial (pass a fake) and lets
   * the production wiring choose dnsjava vs. javax.naming without churn here.
   */
  @Transactional
  public OrgCustomDomain verify(UUID orgId, UUID domainId, DnsTxtResolver resolver, UUID actorUserId) {
    OrgCustomDomain d = domains.findById(domainId)
        .orElseThrow(() -> new ResourceNotFoundException("Domain not found"));
    if (!d.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Domain not found in this organization");
    }
    String expected = "fairtix-verify=" + d.getVerificationToken();
    List<String> values = resolver.lookupTxt("_fairtix-verify." + d.getHostname());
    boolean match = values.stream().anyMatch(v -> v.trim().equals(expected));
    if (!match) {
      throw new IllegalStateException(
          "Verification TXT record not found at _fairtix-verify." + d.getHostname());
    }
    d.markVerified();
    auditService.log(actorUserId, "ORG_CUSTOM_DOMAIN_VERIFIED", "ORG_CUSTOM_DOMAIN", d.getId(),
        "orgId=" + orgId + " host=" + d.getHostname());
    return d;
  }

  public Optional<OrgCustomDomain> resolveActiveByHost(String hostname) {
    if (hostname == null) return Optional.empty();
    return domains.findByHostname(hostname.trim().toLowerCase())
        .filter(OrgCustomDomain::isActive);
  }

  private static String normalize(String hostname) {
    if (hostname == null) throw new IllegalArgumentException("hostname is required");
    String n = hostname.trim().toLowerCase();
    if (n.startsWith("http://") || n.startsWith("https://")) {
      throw new IllegalArgumentException("hostname must not include protocol");
    }
    if (!HOSTNAME.matcher(n).matches()) {
      throw new IllegalArgumentException("hostname is not a valid DNS name");
    }
    return n;
  }

  private static String randomToken() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** Tiny abstraction so verification is testable without hitting real DNS. */
  @FunctionalInterface
  public interface DnsTxtResolver {
    List<String> lookupTxt(String name);
  }
}
