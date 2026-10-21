package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeConnectServiceTest {

  /**
   * A failed AccountLink.create used to roll back the new account id, so the
   * retry created a second connected account. The account create is now keyed
   * by organization (exposure gate (b)).
   */
  @Test
  void createOnboardingLink_sendsAccountKey() throws Exception {
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    StripeConnectService service = new StripeConnectService(orgs, mock(AuditService.class));
    ReflectionTestUtils.setField(service, "stripeEnabled", true);
    ReflectionTestUtils.setField(service, "returnUrl", "http://localhost/return");
    ReflectionTestUtils.setField(service, "refreshUrl", "http://localhost/refresh");

    UUID orgId = UUID.randomUUID();
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(org.getSlug()).thenReturn("acme");
    when(org.getContactEmail()).thenReturn("ops@acme.test");
    when(orgs.findById(orgId)).thenReturn(Optional.of(org));

    try (MockedStatic<Account> accounts = Mockito.mockStatic(Account.class);
         MockedStatic<AccountLink> links = Mockito.mockStatic(AccountLink.class)) {
      Account account = mock(Account.class);
      when(account.getId()).thenReturn("acct_test_1");
      accounts.when(() -> Account.create(any(AccountCreateParams.class), any(RequestOptions.class)))
          .thenReturn(account);
      accounts.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(account);
      AccountLink link = mock(AccountLink.class);
      when(link.getUrl()).thenReturn("https://connect.stripe.test/onboard");
      links.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(link);

      service.createOnboardingLink(orgId, UUID.randomUUID(), "US");

      ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
      accounts.verify(() -> Account.create(any(AccountCreateParams.class), opts.capture()));
      assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("fairtix-acct-" + orgId);
    }
  }
}
