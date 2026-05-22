package com.fairtix.branding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fairtix.audit.application.AuditService;
import com.fairtix.branding.domain.OrgCustomDomain;
import com.fairtix.branding.infrastructure.OrgCustomDomainRepository;

class CustomDomainServiceTest {

  private OrgCustomDomainRepository repo;
  private AuditService audit;
  private CustomDomainService service;

  @BeforeEach
  void setUp() {
    repo = mock(OrgCustomDomainRepository.class);
    audit = mock(AuditService.class);
    service = new CustomDomainService(repo, audit);
    lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void rejectsInvalidHostnames() {
    UUID org = UUID.randomUUID();
    assertThatThrownBy(() -> service.add(org, "http://x.com", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.add(org, "no-tld", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAlreadyClaimedHostname() {
    when(repo.existsByHostnameIgnoreCase("tickets.example.com")).thenReturn(true);
    assertThatThrownBy(() -> service.add(UUID.randomUUID(), "tickets.example.com", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void verifyMarksDomainActiveWhenTxtMatches() {
    UUID org = UUID.randomUUID();
    OrgCustomDomain d = new OrgCustomDomain(org, "tickets.example.com", "tok123");
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.of(d));

    CustomDomainService.DnsTxtResolver resolver =
        name -> List.of("fairtix-verify=tok123");
    OrgCustomDomain after = service.verify(org, id, resolver, UUID.randomUUID());

    assertThat(after.isActive()).isTrue();
    assertThat(after.getVerifiedAt()).isNotNull();
  }

  @Test
  void verifyFailsWhenTxtMissing() {
    UUID org = UUID.randomUUID();
    OrgCustomDomain d = new OrgCustomDomain(org, "tickets.example.com", "tok123");
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.of(d));

    CustomDomainService.DnsTxtResolver resolver = name -> List.of();
    assertThatThrownBy(() -> service.verify(org, id, resolver, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(d.isActive()).isFalse();
  }
}
