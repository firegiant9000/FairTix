# Changelog

All notable changes to FairTix. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning is loose — milestones (M1, M2, …) drive cuts, not semver.

## [Unreleased] — M1 (2026-05)

### Added

- **Organizations module** ([#167](https://github.com/firegiant9000/FairTix/issues/167))
  multi-tenant foundation with `Organization`, `OrganizationMember`,
  `OrganizationInvite`, six role types (`OWNER` / `MANAGER` / `BOX_OFFICE` /
  `DOOR` / `MARKETING` / `ACCOUNTANT`), and an `@OrgScoped` interceptor that
  resolves orgs from path or header. Migrations V32–V34 create and backfill the
  schema; V36 fails the deploy if the backfill leaves any org without an OWNER.
- **Stripe refund execution** ([#161](https://github.com/firegiant9000/FairTix/issues/161))
  `StripePaymentService.createRefund()` is called from `RefundService.processRefund`.
  Idempotent on `RefundRequest.stripeRefundId`, gated by Stripe's 180-day window,
  records the refund id (V31), and rolls the order state back if the API call
  fails. Webhook (`charge.refunded`) flips the row to COMPLETED.
- **NotificationGate** ([#162](https://github.com/firegiant9000/FairTix/issues/162))
  all 17 outbound-mail call sites in `backend/src/main/java` now route through
  a single gate that consults `NotificationPreference` and bypasses for
  transactional categories (security mail, queue admission, event cancelled).
  CI fails the build if any class outside `com.fairtix.notifications` references
  `EmailService` directly.
- **Correlation IDs** ([#163](https://github.com/firegiant9000/FairTix/issues/163))
  V30 adds `audit_logs.request_id`; every audit row, outbound email
  (`X-Request-Id` header), Stripe metadata (`metadata.requestId`), and async
  task carries the MDC value. All 8 `@Scheduled` jobs inject a synthetic
  `sched-<name>-<uuid>` id so background work is no longer untraceable.
- **`/organizer` route tree** ([#168](https://github.com/firegiant9000/FairTix/issues/168))
  9 routes under `/organizer` with `OrganizerLayout`, `OrganizerRoute` guard,
  org switcher, and an `OrganizerOnboarding` flow that creates the first org
  for new users.
- **Plan tier scaffolding** ([#169](https://github.com/firegiant9000/FairTix/issues/169))
  `Plan` enum (FREE 200/mo, PRO/SCALE unlimited, ENTERPRISE custom),
  `PlanEnforcementService` stub returning true unconditionally, scheduled
  credit-reset job. M5 turns enforcement on. V35 extends `organizations` with
  plan, credit, and Stripe customer/subscription columns.
- **Staging environment scaffolding** ([#165](https://github.com/firegiant9000/FairTix/issues/165))
  `application-staging.properties`, deep-health endpoint at `GET /_health/deep`
  (admin-only) reporting per-component status for db/redis/mail/stripe,
  `scripts/reset-staging.sh` with a `STAGING_DB_URL` safety guard,
  `.env.staging.example`, and `docs/runbook-staging.md`. Railway/Netlify/DNS
  setup itself remains TODO.
- **JaCoCo backend coverage gate** ([#166](https://github.com/firegiant9000/FairTix/issues/166))
  with a provisional 15%-line / 5%-branch floor; both backend and frontend
  coverage reports are uploaded as GitHub Actions artifacts.
- **Cookie-auth regression guard** ([#164](https://github.com/firegiant9000/FairTix/issues/164))
  ESLint `no-restricted-globals` rejects `sessionStorage`,
  `no-restricted-syntax` rejects `Bearer ` literals, and a CI grep step fails
  the build if either pattern reappears in the frontend tree.
- **ADRs**: [`docs/adr/0001-cookie-auth.md`](docs/adr/0001-cookie-auth.md)
  documents the HttpOnly-cookie auth model, CSRF mitigation, and
  cross-subdomain note.
- **`develop` branch** for staging deploys; CI workflow now fires on PRs into
  both `main` and `develop` plus pushes to `develop`.

### Changed

- **`EventService.verifyOwnership`** consults `OrganizationMember` and grants
  the requester access if their role has `OrgPermission.EVENTS_WRITE`.
  Platform `Role.ADMIN` continues to bypass. Orphan events (no
  `organization_id` and no migration coverage) fall back to the original
  `organizer_id` check.
- **`EventService.createEvent`** auto-attaches the new event to the creator's
  organization when they are a member of exactly one org. Multi-org members
  must call the 5-arg overload with an explicit `organizationId`. Stops new
  events from being orphans the moment they're created.
- **All schedulers** (`HoldExpirationScheduler`, `QueueAdmissionScheduler`,
  `QueueExpirationScheduler`, `BehaviorAnalysisSweepScheduler`,
  `VerificationTokenCleanupScheduler`, `RiskScoringService.runDecaySweep`,
  `TransferService.expireStaleRequests`, `PlanCreditResetScheduler`) wrap
  their work in `MDC.put("sched-...") ... finally MDC.remove`.
- **`OrganizerRoute`** skips the orgless redirect when the user is already on
  `/organizer/onboarding`, breaking a self-loop the first prototype shipped.

### Fixed

- **V37 backfill** — `notification_preferences.email_hold` defaulted to FALSE
  in V8 but the pre-gate codebase ignored the preference and emailed everyone.
  Existing users with untouched preferences (`updated_at = created_at`) are
  flipped to TRUE so they don't silently stop receiving hold-expiring emails
  the moment NotificationGate ships. Users who explicitly toggled the
  preference are respected.

### Deferred (see [`STRATEGIC_ROADMAP.md`](STRATEGIC_ROADMAP.md))

- Stripe refund integration test with the live test API (needs a CI secret).
- Frontend tests for organizer routes.
- Stripe Connect (lands with M2 [#170](https://github.com/firegiant9000/FairTix/issues/170)).
- Cookie-domain decision for cross-subdomain staging.
- Bumping the JaCoCo floor from the provisional 15% to baseline−1% after the
  first CI run.

### Definition-of-done status

| Plan item | Status |
|---|---|
| All 9 M1 issues closed (committed) | ✅ |
| Coverage baseline locked | ✅ (provisional 15%; raise after CI) |
| PRs merged through `develop` → staging → main | ⬜ branch not pushed yet |
| Refund integration test passes in CI | ⬜ deferred |
| Real Stripe test refund performed in staging | ⬜ infra not deployed |
| End-to-end smoke recorded | ⬜ |
