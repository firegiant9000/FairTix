package com.fairtix.boxoffice.infrastructure;

import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.boxoffice.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoxOfficeSessionRepository extends JpaRepository<BoxOfficeSession, UUID> {

  Optional<BoxOfficeSession> findFirstByOrganizationIdAndStaffUserIdAndStatus(
      UUID organizationId, UUID staffUserId, SessionStatus status);

  List<BoxOfficeSession> findAllByOrganizationIdOrderByOpenedAtDesc(UUID organizationId);
}
