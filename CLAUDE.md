# Project context

Project name: FairTix
Project type: fullstack
Primary goal: Fair-access ticketing platform with real-time seat holds, rate limiting, and audit logging.
Team: group

# Stack

Languages: Java 21 (backend), JavaScript (frontend)
Frameworks: Spring Boot 4.0.5, React 18.2 (Create React App)
Data layer: PostgreSQL 16 via JPA/Hibernate + Flyway migrations (seat holds live here, as rows under pessimistic row locks); Redis 7 via Redisson for the waiting-room queue, rate limiting and login throttling
Auth: JWT (JJWT 0.13.0) via HttpOnly cookies; /auth/me for user hydration
Deployment: Docker Compose (local/dev); GitHub Actions CI with Trivy + OWASP checks

# Commands

Build (backend):   cd backend && mvn clean package
Test (backend):    cd backend && mvn clean verify
Test (frontend):   cd frontend/webpages && npm test
Lint/format:       n/a (no formatter configured)
Run locally:       docker compose up --build
DB migrate:        runs automatically on backend startup via Flyway

# Code locations

Source (backend):   backend/src/main/java/com/fairtix/
Source (frontend):  frontend/webpages/src/
Tests (backend):    backend/src/test/java/com/fairtix/
Tests (frontend):   frontend/webpages/src/*.test.js
Migrations:         backend/src/main/resources/db/migration/
Config:             backend/src/main/resources/application.properties

# Architecture notes

- Backend is domain-driven and modular: each feature (events, orders, payments, inventory, auth, audit, analytics, notifications, venues) follows an api/application/domain/infrastructure layer pattern.
- Seat holds are PostgreSQL rows: `SeatRepository.findAndLockByIdIn` takes `PESSIMISTIC_WRITE` locks in sorted-ID order inside one transaction, `Seat` carries `@Version`, holds carry an expiry column and `HoldExpirationScheduler` sweeps expired `ACTIVE` holds every 30 seconds. Per-user limits come from `holds.max-active-per-holder` (check the property, do not assume a number). Never bypass `SeatHoldService` for concurrency correctness. Known gap (2026-09): `CONFIRMED` holds are not swept, and `tickets.seat_id` has no unique index; see `docs/roadmap-review-2026-09.md`.
- Auth is transitioning from sessionStorage + Bearer token to HttpOnly cookies + /auth/me. New code must follow the cookie-based approach.
- Database schema is managed entirely via Flyway versioned migrations. Never modify the DB schema directly — always add a new migration file.
- Audit logging tracks all user actions with request ID correlation. New features that mutate state should emit audit events.

# Implementation rules

- Follow existing patterns before introducing new ones.
- Keep changes minimal and task-scoped. Do not modify unrelated files.
- Do not add new packages without explicit approval.
- For risky or broad changes, propose a plan before implementing.
- Write tests for new logic if a test suite exists for that module.
- Schema changes require a new Flyway migration file, never manual edits.

# Investigation rules

When asked to investigate:

1. Map current behavior and owning modules.
2. Identify required changes by layer: data, API, service, UI, reporting.
3. Identify migration needs and backward compatibility concerns.
4. Identify risks and mitigation.
5. Identify test coverage needed.
6. Check local and remote branches, and open PRs for existing work.
7. Provide phased rollout and rollback notes.

# Definition of done

- Required tests pass, or blockers are clearly documented.
- No unrelated edits remain.
- Summary includes: what changed, why, risks, and follow-up tasks.
