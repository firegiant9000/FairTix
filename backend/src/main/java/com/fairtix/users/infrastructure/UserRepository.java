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

  /** Registration's duplicate check: {@code users.email} is unique only case-sensitively. */
  boolean existsByEmailIgnoreCase(String email);

  @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
  List<Object[]> countByRoleGrouped();
}
