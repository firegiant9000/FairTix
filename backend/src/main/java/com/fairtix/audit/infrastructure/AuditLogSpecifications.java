package com.fairtix.audit.infrastructure;

import com.fairtix.audit.domain.AuditLog;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Optional filters for the admin audit query. Each filter adds a predicate only when
 * it is set, so no null is ever bound as a parameter. The previous
 * {@code (:param IS NULL OR ...)} JPQL bound omitted filters as untyped nulls, which
 * PostgreSQL resolves as {@code bytea}, so the query failed with a 500 whenever a
 * filter was left out (H2 accepted it).
 */
public final class AuditLogSpecifications {

  private AuditLogSpecifications() {
  }

  /** {@code action} is a prefix match, as before. */
  public static Specification<AuditLog> withFilters(UUID userId, String action, Instant from) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (userId != null) {
        predicates.add(cb.equal(root.get("userId"), userId));
      }
      if (action != null) {
        predicates.add(cb.like(root.get("action"), action + "%"));
      }
      if (from != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }
}
