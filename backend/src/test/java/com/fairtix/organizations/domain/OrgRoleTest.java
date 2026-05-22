package com.fairtix.organizations.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test of the permission matrix. The wiring of OrgRole into ACL checks
 * is exercised by OrganizationServiceTest and the EventService refactor tests;
 * this suite locks the matrix itself so accidental edits to OrgRole.java fail
 * the build before they reach an integration test.
 */
class OrgRoleTest {

  @Test
  void ownerHasEveryPermission() {
    for (OrgPermission p : OrgPermission.values()) {
      assertThat(OrgRole.OWNER.has(p))
          .as("OWNER should have %s", p)
          .isTrue();
    }
  }

  @Test
  void managerHasEventsAndRefundsWriteButNotScannerOrPayouts() {
    assertThat(OrgRole.MANAGER.has(OrgPermission.EVENTS_WRITE)).isTrue();
    assertThat(OrgRole.MANAGER.has(OrgPermission.REFUNDS_WRITE)).isTrue();
    assertThat(OrgRole.MANAGER.has(OrgPermission.SCANNER_USE)).isFalse();
    assertThat(OrgRole.MANAGER.has(OrgPermission.PAYOUTS_READ)).isFalse();
  }

  @Test
  void boxOfficeCanSellAndIssueCompsButCannotEditEventsOrRefund() {
    assertThat(OrgRole.BOX_OFFICE.has(OrgPermission.BOX_OFFICE_SELL)).isTrue();
    assertThat(OrgRole.BOX_OFFICE.has(OrgPermission.COMPS_WRITE)).isTrue();
    assertThat(OrgRole.BOX_OFFICE.has(OrgPermission.EVENTS_WRITE)).isFalse();
    assertThat(OrgRole.BOX_OFFICE.has(OrgPermission.REFUNDS_WRITE)).isFalse();
  }

  @Test
  void doorOnlyScans() {
    for (OrgPermission p : OrgPermission.values()) {
      boolean expected = p == OrgPermission.SCANNER_USE;
      assertThat(OrgRole.DOOR.has(p))
          .as("DOOR.%s should be %s", p, expected)
          .isEqualTo(expected);
    }
  }

  @Test
  void marketingHasEmailSendButNotSalesOrPayouts() {
    assertThat(OrgRole.MARKETING.has(OrgPermission.EMAIL_SEND)).isTrue();
    assertThat(OrgRole.MARKETING.has(OrgPermission.SALES_READ)).isFalse();
    assertThat(OrgRole.MARKETING.has(OrgPermission.PAYOUTS_READ)).isFalse();
    assertThat(OrgRole.MARKETING.has(OrgPermission.EVENTS_WRITE)).isFalse();
  }

  @Test
  void accountantSeesMoneyButCannotEditEventsOrTeam() {
    assertThat(OrgRole.ACCOUNTANT.has(OrgPermission.PAYOUTS_READ)).isTrue();
    assertThat(OrgRole.ACCOUNTANT.has(OrgPermission.REFUNDS_READ)).isTrue();
    assertThat(OrgRole.ACCOUNTANT.has(OrgPermission.REFUNDS_WRITE)).isFalse();
    assertThat(OrgRole.ACCOUNTANT.has(OrgPermission.EVENTS_WRITE)).isFalse();
    assertThat(OrgRole.ACCOUNTANT.has(OrgPermission.TEAM_WRITE)).isFalse();
  }

  @Test
  void noNonOwnerRoleEverHasAll() {
    for (OrgRole role : OrgRole.values()) {
      if (role == OrgRole.OWNER) continue;
      assertThat(role.has(OrgPermission.ALL))
          .as("only OWNER should hold ALL; %s does not", role)
          .isFalse();
    }
  }
}
