package com.fairtix.boxoffice.dto;

import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.boxoffice.domain.SessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
    UUID id,
    UUID organizationId,
    UUID staffUserId,
    SessionStatus status,
    BigDecimal openingCash,
    BigDecimal closingCash,
    BigDecimal expectedCash,
    BigDecimal variance,
    String varianceReason,
    UUID signedOffByUserId,
    Instant signedOffAt,
    Instant openedAt,
    Instant closedAt) {

  public static SessionResponse from(BoxOfficeSession s) {
    return new SessionResponse(s.getId(), s.getOrganizationId(), s.getStaffUserId(),
        s.getStatus(), s.getOpeningCash(), s.getClosingCash(), s.getExpectedCash(),
        s.getVariance(), s.getVarianceReason(), s.getSignedOffByUserId(), s.getSignedOffAt(),
        s.getOpenedAt(), s.getClosedAt());
  }
}
