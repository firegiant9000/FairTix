package com.fairtix.organizations.domain;

import java.util.EnumSet;
import java.util.Set;

public enum OrgRole {
  OWNER(EnumSet.of(OrgPermission.ALL)),
  MANAGER(EnumSet.of(
      OrgPermission.EVENTS_READ,
      OrgPermission.EVENTS_WRITE,
      OrgPermission.SALES_READ,
      OrgPermission.REFUNDS_READ,
      OrgPermission.REFUNDS_WRITE,
      OrgPermission.COMPS_WRITE,
      OrgPermission.TEAM_READ,
      OrgPermission.ATTENDEES_READ,
      OrgPermission.REPORTS_READ)),
  BOX_OFFICE(EnumSet.of(
      OrgPermission.EVENTS_READ,
      OrgPermission.SALES_READ,
      OrgPermission.COMPS_WRITE,
      OrgPermission.BOX_OFFICE_SELL,
      OrgPermission.ATTENDEES_READ)),
  DOOR(EnumSet.of(
      OrgPermission.SCANNER_USE)),
  MARKETING(EnumSet.of(
      OrgPermission.EVENTS_READ,
      OrgPermission.ATTENDEES_READ,
      OrgPermission.EMAIL_SEND)),
  ACCOUNTANT(EnumSet.of(
      OrgPermission.SALES_READ,
      OrgPermission.REFUNDS_READ,
      OrgPermission.PAYOUTS_READ,
      OrgPermission.REPORTS_READ));

  private final Set<OrgPermission> permissions;

  OrgRole(Set<OrgPermission> permissions) {
    this.permissions = permissions;
  }

  public boolean has(OrgPermission p) {
    return permissions.contains(OrgPermission.ALL) || permissions.contains(p);
  }
}
