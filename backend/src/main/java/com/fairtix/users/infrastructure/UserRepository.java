package com.fairtix.users.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.fairtix.users.domain.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);

  /** Registration's duplicate check; V57's unique index on upper(email) backs it. */
  boolean existsByEmailIgnoreCase(String email);

  /**
   * Every account whose email matches ignoring case, oldest first. At most one since V57;
   * {@code UserEmailLookup} still ranks several, as a defence.
   */
  List<User> findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc(String email);

  @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
  List<Object[]> countByRoleGrouped();
}
