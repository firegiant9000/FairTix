# Implementation Plan: M1 Issues #88, #89, #90, #91

> Branch: `feature/m1-queue-email-password-reset`
> Base: `feature/implement-issues-49-66-67-71-74`
> Date: 2026-04-15

---

## Table of Contents

1. [Dependency Graph & Implementation Order](#dependency-graph--implementation-order)
2. [Issue #90 - Transactional Email Service](#issue-90---transactional-email-service)
3. [Issue #89 - Email Verification on Signup](#issue-89---email-verification-on-signup)
4. [Issue #91 - Forgot/Reset Password Flow](#issue-91---forgotreset-password-flow)
5. [Issue #88 - Waiting Room & Queue Token System](#issue-88---waiting-room--queue-token-system)
6. [Cross-Issue Conflicts & Risks](#cross-issue-conflicts--risks)
7. [Migration Summary](#migration-summary)

---

## Dependency Graph & Implementation Order

```
#90 Transactional Email Service (foundation)
 ├── #89 Email Verification on Signup (depends on #90)
 └── #91 Forgot/Reset Password Flow (depends on #90)

#88 Waiting Room & Queue Token System (independent)
```

**Recommended order:** `#90 → #89 → #91 → #88`

- #90 must go first — #89 and #91 both require a working email sender.
- #89 before #91 because verification modifies the User entity and auth flow; password reset builds on those same files, so doing them in sequence avoids merge conflicts.
- #88 is fully independent and can be parallelized with #89/#91 if split across developers.

---

## Implementation Status

> Reviewed: 2026-04-15 — Branch `feature/m1-queue-email-password-reset`

### Issue Summary

| Issue | Status | Notes |
|-------|--------|-------|
| #90 Transactional Email Service | ✅ COMPLETE | `spring-boot-starter-mail`, `SmtpEmailService`, `EmailTemplateService`, MailHog in Docker Compose — all present |
| #89 Email Verification on Signup | ✅ COMPLETE | V9 migration correctly grandfathers existing users (`UPDATE users SET email_verified = TRUE WHERE deleted_at IS NULL`) |
| #91 Forgot/Reset Password Flow | ✅ COMPLETE | Redis rate-limiting (3/15 min), `LoginAttemptService` integration, and audit logging all present |
| #88 Waiting Room & Queue | ✅ COMPLETE | Redis + DB fallback for admission checks, `SeatHoldService` gate, schedulers, and all frontend components present |

### Deviations from Plan

| # | Description | Location | Severity |
|---|-------------|----------|----------|
| 1 | **Admin queue endpoint paths differ.** Plan specified `/api/admin/events/{eventId}/queue`. Implementation uses `/api/events/{eventId}/queue/admin` and `/api/events/{eventId}/queue/admin/admit`. Both are secured with `@PreAuthorize("hasRole('ADMIN')")`. | `queue/api/QueueController.java` | Low — functionally equivalent, security intact |
| 2 | **`QueueEntry` uses `GenerationType.AUTO` instead of `GenerationType.UUID`.** Works correctly; PostgreSQL resolves AUTO and the DB migration uses `gen_random_uuid()`. Minor style inconsistency with other entities. | `queue/domain/QueueEntry.java` | Low — not a bug |
| 3 | **`VerifyEmail.js` has a dual-path.** The plan described a redirect-landing page only. Implementation also handles a raw `?token=...` param by making a direct API call. Backend always redirects to `?success=true`/`?error=true`, so the token path is a secondary fallback. | `frontend/webpages/src/pages/VerifyEmail.js` | Low — enhancement, not a problem |
| 4 | **`/auth/verify` maps all error types to a single `?error=true` redirect.** Expired, already-used, and invalid tokens all produce the same user-facing error. Acceptable for MVP; a future improvement could distinguish expiry from invalidity. | `auth/api/AuthController.java` | Low — acceptable MVP behaviour |

### Outstanding Test Gaps

Tests exist for `SmtpEmailService` and `EmailTemplateService` only. The following are missing:

**Backend unit tests:**
- `EmailVerificationService` — token generation, expiry, one-time-use, max active tokens
- `PasswordResetService` — rate limiting, silent enumeration, lockout reset, password strength
- `QueueService` — join, status, leave, admission check (Redis hit, Redis miss → DB fallback)
- `SeatHoldService` — queue gate rejection (no admission), queue gate pass, `completeQueueEntry` on hold success
- `QueueAdmissionScheduler` — batch sizing, seat availability math
- `QueueExpirationScheduler` — expires ADMITTED entries, removes from Redis admitted set
- `VerificationTokenCleanupScheduler` — deletes expired verification and reset tokens

**Frontend tests:**
- `VerifyEmail.js` — success state, error state, already-verified state
- `ForgotPassword.js` — form submission, success screen, rate-limit (429) error
- `ResetPassword.js` — valid token flow, expired/invalid token error, password strength validation, confirm-password mismatch
- `WaitingRoom.js` — shows position, updates on poll, transitions to admitted state

---

## Issue #90 - Transactional Email Service

**Goal:** Build a reusable email sending abstraction so verification, password reset, and future transactional emails have a single integration point.

### Current State

| Layer | What exists today |
|-------|-------------------|
| **NotificationPreferenceController** | Stores per-user email toggles (order confirmations, hold reminders, marketing). No email is ever sent. |
| **application.properties** | No `spring.mail.*` config. No email provider env vars. |
| **.env.example** | No mail-related variables. |
| **Dependencies** | No `spring-boot-starter-mail`, no SendGrid/SES SDK in `pom.xml`. |

### What Needs to Be Built

#### Backend

| Component | Location | Details |
|-----------|----------|---------|
| **Maven dependency** | `backend/pom.xml` | Add `spring-boot-starter-mail` (uses Jakarta Mail under the hood). No new third-party SDK needed — SMTP works with SendGrid, SES, Mailgun, or local dev tools like MailHog. |
| **EmailService interface** | `notifications/application/EmailService.java` | `sendEmail(String to, String subject, String htmlBody)` — simple abstraction. |
| **SmtpEmailService** | `notifications/infrastructure/SmtpEmailService.java` | Implements `EmailService` using Spring's `JavaMailSender`. Handles send failures with logging + exception wrapping. |
| **Email templates** | `notifications/application/EmailTemplateService.java` | Methods like `buildVerificationEmail(String name, String link)` and `buildPasswordResetEmail(String name, String link)` returning HTML strings. Keep templates as inline string builders or simple resource files — no Thymeleaf dependency. |
| **application.properties** | `backend/src/main/resources/application.properties` | Add mail config block with env var fallbacks. |
| **Dev mail config** | `docker-compose.yml` | Add MailHog container (`mailhog/mailhog`) for local dev. SMTP on port 1025, web UI on 8025. |

#### Configuration additions

**application.properties:**
```properties
# Email (SMTP)
spring.mail.host=${MAIL_HOST:localhost}
spring.mail.port=${MAIL_PORT:1025}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=${MAIL_AUTH:false}
spring.mail.properties.mail.smtp.starttls.enable=${MAIL_STARTTLS:false}
app.mail.from=${MAIL_FROM:noreply@fairtix.com}
app.base-url=${APP_BASE_URL:http://localhost:3000}
```

**.env.example additions:**
```
MAIL_HOST=mailhog
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_AUTH=false
MAIL_STARTTLS=false
MAIL_FROM=noreply@fairtix.com
APP_BASE_URL=http://localhost:3000
```

### Potential Issues & Mitigations

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **SMTP failures silently swallow registration/reset** | User never gets email, can't verify or reset | Log errors, return success to user anyway (don't leak whether email exists), provide "resend" option |
| **MailHog not in Docker Compose** | Local dev can't test emails | Add MailHog service; document web UI at `localhost:8025` |
| **Rate limiting on email sends** | Abuse via resend endpoints | Rate-limit resend/forgot-password endpoints (reuse existing `RateLimitService`) |
| **Email content injection** | User-controlled names in templates | HTML-escape all interpolated values in templates |
| **spring-boot-starter-mail is a new dependency** | Must get approval per project rules | Minimal dependency — it's part of Spring Boot's own starter set, not a third-party library |

### Tests

- Unit test `SmtpEmailService` with mocked `JavaMailSender` — verify `send()` called with correct `MimeMessage`.
- Unit test `EmailTemplateService` — verify HTML output contains expected links and escaped content.
- Integration: manual verification via MailHog web UI during local dev.

---

## Issue #89 - Email Verification on Signup

**Goal:** Require users to verify their email address before accessing checkout. Unverified users can browse and log in but cannot purchase tickets.

### Current State

| Layer | What exists today |
|-------|-------------------|
| **User entity** (`users/domain/User.java`) | Fields: `id`, `email`, `password`, `role`, `deletedAt`. **No `emailVerified` field.** |
| **AuthService.register()** | Creates user, encodes password, saves, returns JWT cookie immediately. No verification step. |
| **SecurityConfig** | `/auth/**` is permitAll. All other non-GET endpoints require authentication but not verification. |
| **PaymentController.checkout()** | Gets user from JWT, validates holds, creates order. **No verification check.** |
| **Frontend Signup.js** | On success, redirects to home. No "check your email" step. |
| **Database** | `users` table has no `email_verified` column. No `email_verification_tokens` table. |

### What Needs to Be Built

#### Database (Flyway migration V9)

```sql
-- V9__add_email_verification.sql

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evt_token ON email_verification_tokens(token);
CREATE INDEX idx_evt_user_id ON email_verification_tokens(user_id);
```

#### Backend

| Component | Location | Details |
|-----------|----------|---------|
| **User entity update** | `users/domain/User.java` | Add `private boolean emailVerified = false;` with `@Column(name = "email_verified", nullable = false)` |
| **EmailVerificationToken entity** | `auth/domain/EmailVerificationToken.java` | Fields: `id`, `userId`, `token`, `expiresAt`, `usedAt`, `createdAt` |
| **EmailVerificationTokenRepository** | `auth/infrastructure/EmailVerificationTokenRepository.java` | `findByToken(String token)`, `deleteExpiredTokens()` |
| **AuthService changes** | `auth/application/AuthService.java` | On `register()`: generate token (UUID), save to DB, call `EmailService` to send verification link. Return JWT as before (user is logged in but unverified). |
| **AuthController additions** | `auth/api/AuthController.java` | `GET /auth/verify?token={token}` — validates token, marks user verified, redirects to frontend. `POST /auth/resend-verification` — generates new token, sends new email (rate-limited). |
| **Checkout gate** | `payments/api/PaymentController.java` | At start of `checkout()`: load user, check `emailVerified == true`, else return 403 with message. |
| **UserResponse DTO update** | `users/api/UserResponse.java` (or equivalent) | Include `emailVerified` field so frontend can show status. |
| **AuthResponse update** | `auth/api/` responses | Include `emailVerified` in `/auth/me` response. |
| **Scheduled cleanup** | `auth/scheduler/VerificationTokenCleanupScheduler.java` | Delete expired tokens older than 24h. Run daily or hourly. |

#### Frontend

| Component | Details |
|-----------|---------|
| **Post-signup redirect** | After registration success, show "Check your email to verify your account" page instead of redirecting to home. |
| **Verification landing page** | New route `/verify?token=...` — calls `GET /auth/verify?token=...`, shows success or error. |
| **AuthContext update** | Store `emailVerified` from `/auth/me` response. Expose it to components. |
| **Checkout gate** | In `Checkout.js`, check `user.emailVerified` before rendering payment form. Show "Verify your email to continue" with resend link if unverified. |
| **Dashboard indicator** | In `Dashboard.js`, show verification status. Offer resend button if unverified. |
| **Navbar hint** | Optional: show a banner/badge if user is unverified. |

#### Token Design

| Property | Value | Rationale |
|----------|-------|-----------|
| Token format | UUID (random) | Simple, unguessable, no crypto needed |
| Expiry | 24 hours | Standard for email verification |
| One-time use | Yes (mark `used_at` on verification) | Prevent replay |
| Resend cooldown | 60 seconds | Prevent abuse |
| Max active tokens per user | 3 | Old tokens still valid until expired; generating a 4th deletes the oldest |

### Potential Issues & Mitigations

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Existing users have `email_verified = FALSE`** | All current users are locked out of checkout | Migration sets `DEFAULT FALSE`. Add a one-time admin endpoint or migration to verify existing users, OR set `DEFAULT TRUE` for existing rows only: `UPDATE users SET email_verified = TRUE WHERE deleted_at IS NULL;` in the migration |
| **Verification link expires before user checks email** | User can't verify | "Resend verification" button on login/dashboard. Rate-limited to prevent abuse. |
| **User changes email** | No email change flow exists currently — not a concern now | If email change is added later, re-trigger verification |
| **Bot registration spam** | Unverified accounts pile up | Scheduled cleanup of unverified accounts older than 7 days (optional, separate task). Token cleanup already handles stale tokens. |
| **Frontend race condition** | User verifies in another tab, current tab still shows unverified | Re-fetch `/auth/me` on checkout page mount. |
| **Checkout 403 confusion** | User doesn't understand why checkout fails | Clear error message with direct link to resend verification. Frontend pre-check before showing payment form. |

### Tests

- Backend: test token generation, verification endpoint (valid token, expired token, used token, invalid token), resend with rate limit, checkout rejection for unverified user.
- Frontend: test signup flow shows verification message, test checkout blocks unverified user, test verification landing page.

---

## Issue #91 - Forgot/Reset Password Flow

**Goal:** Allow users to request a password reset via email and set a new password using a time-limited token.

**Depends on:** #90 (email service) and partially on #89 (shares token pattern and User entity changes).

### Current State

| Layer | What exists today |
|-------|-------------------|
| **AuthController** | `POST /auth/login`, `POST /auth/register`, `GET /auth/me`, `POST /auth/logout`. No forgot/reset endpoints. |
| **AuthService** | `register()` and `authenticate()`. No password reset logic. |
| **User entity** | Password stored as BCrypt hash. No reset token field. |
| **Frontend Login.js** | Email + password form with lockout display. No "Forgot password?" link. |
| **SecurityConfig** | `/auth/**` is permitAll — new reset endpoints under `/auth/` are automatically public. |
| **LoginAttemptService** | Tracks failed logins in Redis. A successful password reset should clear this counter. |

### What Needs to Be Built

#### Database (Flyway migration V10)

```sql
-- V10__add_password_reset_tokens.sql

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_prt_token ON password_reset_tokens(token);
CREATE INDEX idx_prt_user_id ON password_reset_tokens(user_id);
```

#### Backend

| Component | Location | Details |
|-----------|----------|---------|
| **PasswordResetToken entity** | `auth/domain/PasswordResetToken.java` | Fields: `id`, `userId`, `token`, `expiresAt`, `usedAt`, `createdAt` |
| **PasswordResetTokenRepository** | `auth/infrastructure/PasswordResetTokenRepository.java` | `findByToken()`, `deleteByUserId()` (invalidate old tokens on new request) |
| **PasswordResetService** | `auth/application/PasswordResetService.java` | `requestReset(String email)`: find user by email, generate token (UUID), save, send email via `EmailService`. Always return 200 (don't leak whether email exists). `resetPassword(String token, String newPassword)`: validate token not expired/used, update user password (BCrypt), mark token used, clear login attempt counter. |
| **AuthController additions** | `auth/api/AuthController.java` | `POST /auth/forgot-password` body: `{ "email": "..." }` — calls `requestReset()`. `POST /auth/reset-password` body: `{ "token": "...", "newPassword": "..." }` — calls `resetPassword()`. |
| **Password validation** | Reuse existing signup password rules | 8+ chars, upper/lower/digit/special. Same `PasswordValidator` or validation annotations used in registration. |
| **LoginAttemptService integration** | `auth/application/LoginAttemptService.java` | On successful password reset, call `resetAttempts(email)` to clear lockout. |
| **Scheduled cleanup** | Can share or extend the verification token cleanup scheduler | Delete expired/used reset tokens older than configurable period. |
| **Audit logging** | `AuditService` | Log password reset requests and completions (don't log the token itself). |

#### Frontend

| Component | Details |
|-----------|---------|
| **"Forgot password?" link** | Add to `Login.js` below the password field, links to `/forgot-password`. |
| **ForgotPassword page** | New route `/forgot-password`. Simple form: email input + submit. On success, show "If an account exists with that email, we've sent reset instructions." (don't confirm email existence). |
| **ResetPassword page** | New route `/reset-password?token=...`. Form: new password + confirm password. Password strength indicator (reuse from Signup.js). On success, redirect to login with success message. On invalid/expired token, show error with link to request new reset. |
| **App.js routing** | Add routes for `/forgot-password` and `/reset-password`. Both are public (no auth required). |

#### Token Design

| Property | Value | Rationale |
|----------|-------|-----------|
| Token format | UUID (random) | Consistent with verification tokens |
| Expiry | 1 hour | Standard for password reset — shorter than verification |
| One-time use | Yes | Mark `used_at` on successful reset |
| Invalidation | Delete all user's existing reset tokens on new request | Prevent token accumulation; only latest link works |
| Rate limit | 3 requests per 15 minutes per email | Prevent enumeration and spam |

### Potential Issues & Mitigations

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Email enumeration** | Attacker discovers which emails are registered | Always return 200 with generic message regardless of whether email exists. |
| **Token brute force** | Attacker guesses token | UUID has 122 bits of entropy — effectively unguessable. 1-hour expiry limits window. |
| **Race condition: concurrent resets** | Multiple tokens active | Delete old tokens on new request. Only one active token per user at a time. |
| **Password reset during active session** | User's existing JWT still valid after password change | JWT has 15-min expiry with no refresh token — existing sessions naturally expire. Acceptable for MVP. For stronger security, add a `passwordChangedAt` field and check it in JWT filter (future enhancement). |
| **Login lockout not cleared** | User resets password but is still locked out | Explicitly call `loginAttemptService.resetAttempts(email)` on successful reset. |
| **Soft-deleted users** | Should not be able to reset password | Check `user.isDeleted()` in `requestReset()` — treat as "email not found" (return 200, don't send email). |

### Tests

- Backend: test forgot-password (existing email, non-existing email, rate limited), test reset-password (valid token, expired token, used token, weak password), test lockout cleared after reset.
- Frontend: test forgot-password form submission and success message, test reset-password form with validation, test invalid token error display.

---

## Issue #88 - Waiting Room & Queue Token System

**Goal:** Gate seat hold creation for high-demand events behind a Redis-backed queue, so users get fair access based on queue position rather than raw speed.

**Dependencies:** None (independent of email issues).

### Current State

| Layer | What exists today |
|-------|-------------------|
| **Event entity** | Fields: `id`, `title`, `venue`, `startTime`, `organizerId`. **No `queueRequired` flag.** |
| **SeatHoldController** | `POST /api/events/{eventId}/holds` — authenticated users can immediately attempt holds. No queue check. |
| **SeatHoldService.createHold()** | Validation sequence: event exists → active hold count < 5 → duration clamped → seats de-duped/sorted → pessimistic lock → seats available → hold created. **No queue token validation step.** |
| **Redis (Redisson)** | Used for rate limiting (`RRateLimiter`) and login lockout (`RAtomicLong`). `RedissonClient` bean available for injection. Queue-compatible data structures (`RQueue`, `RSortedSet`, `RAtomicLong`) available but unused. |
| **Frontend EventDetail.js** | Seat table with selection → "Hold Seats" button → `POST /api/events/{eventId}/holds`. No queue awareness. 10-second polling for seat status updates. |
| **HoldExpirationScheduler** | Runs every 30s, expires holds past TTL in batches of 500. |

### What Needs to Be Built

#### Database (Flyway migration V11)

```sql
-- V11__add_queue_system.sql

ALTER TABLE events ADD COLUMN queue_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN queue_capacity INTEGER;

CREATE TABLE queue_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    position INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    admitted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_queue_event_user UNIQUE (event_id, user_id)
);

CREATE INDEX idx_qe_event_status ON queue_entries(event_id, status);
CREATE INDEX idx_qe_token ON queue_entries(token);
CREATE INDEX idx_qe_event_position ON queue_entries(event_id, position);
```

#### Backend

| Component | Location | Details |
|-----------|----------|---------|
| **Event entity update** | `events/domain/Event.java` | Add `private boolean queueRequired = false;` and `private Integer queueCapacity;`. Update DTOs (`CreateEventRequest`, `UpdateEventRequest`, `EventResponse`). |
| **QueueEntry entity** | `queue/domain/QueueEntry.java` | Fields: `id`, `eventId`, `userId`, `token` (UUID string), `position`, `status` (enum: `WAITING`, `ADMITTED`, `EXPIRED`, `COMPLETED`), `admittedAt`, `expiresAt`, `createdAt`. |
| **QueueStatus enum** | `queue/domain/QueueStatus.java` | `WAITING`, `ADMITTED`, `EXPIRED`, `COMPLETED` |
| **QueueRepository** | `queue/infrastructure/QueueRepository.java` | JPA repository. Key queries: `findByEventIdAndUserId()`, `findByToken()`, `countByEventIdAndStatus(WAITING)`, `findNextWaiting(eventId, limit)`. |
| **QueueService** | `queue/application/QueueService.java` | Core logic — see detailed flow below. |
| **QueueController** | `queue/api/QueueController.java` | Endpoints — see below. |
| **Redis queue position counter** | Via `RedissonClient` | `RAtomicLong` keyed `queue:position:{eventId}` for fast position assignment. |
| **Redis admitted set** | Via `RedissonClient` | `RSet<String>` keyed `queue:admitted:{eventId}` for O(1) admission check during hold creation. TTL matches admission window. |
| **SeatHoldService gate** | `inventory/application/SeatHoldService.java` | Insert check at start of `createHold()`: if `event.isQueueRequired()`, verify user has `ADMITTED` queue entry (check Redis set first, fall back to DB). |
| **QueueAdmissionScheduler** | `queue/scheduler/QueueAdmissionScheduler.java` | Runs every 30s. For each queue-required event: count available seats, count currently admitted users, admit next N users from WAITING to ADMITTED. Set admission expiry (15 min). |
| **QueueExpirationScheduler** | `queue/scheduler/QueueExpirationScheduler.java` | Runs every 30s. Expire ADMITTED entries past their `expires_at`. Remove from Redis admitted set. |

#### Queue Flow (Detailed)

**Joining the queue:**
```
1. User visits EventDetail for a queue-required event
2. Frontend detects event.queueRequired === true
3. User clicks "Join Queue" → POST /api/events/{eventId}/queue/join
4. QueueService:
   a. Check user not already in queue (UNIQUE constraint)
   b. Atomically increment Redis position counter
   c. Create QueueEntry(status=WAITING, position=N)
   d. Return: { token, position, estimatedWaitMinutes, totalAhead }
5. Frontend enters "waiting room" mode — polls for status
```

**Polling queue status:**
```
1. Frontend polls GET /api/events/{eventId}/queue/status every 5s
2. QueueService returns:
   - position (current)
   - status (WAITING | ADMITTED | EXPIRED)
   - estimatedWaitMinutes
   - totalAhead (users still waiting ahead)
   - If ADMITTED: expiresAt (deadline to complete hold)
3. When status === ADMITTED, frontend transitions to seat selection
```

**Seat hold with queue enforcement:**
```
1. User selects seats, clicks "Hold Seats"
2. POST /api/events/{eventId}/holds (existing endpoint)
3. SeatHoldService.createHold():
   a. Load event
   b. NEW: if event.queueRequired → check Redis admitted set
      - If user NOT in set → 403 "Queue admission required"
      - If user in set → proceed
   c. Existing: active hold count, seat validation, pessimistic lock, etc.
4. On successful hold: mark queue entry as COMPLETED
```

#### API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/events/{eventId}/queue/join` | Authenticated | Join queue for event |
| `GET` | `/api/events/{eventId}/queue/status` | Authenticated | Get user's queue position and status |
| `DELETE` | `/api/events/{eventId}/queue/leave` | Authenticated | Leave queue voluntarily |
| `GET` | `/api/admin/events/{eventId}/queue` | Admin | View full queue state (admin) |
| `POST` | `/api/admin/events/{eventId}/queue/admit` | Admin | Manually admit next N users (admin) |

#### Frontend

| Component | Details |
|-----------|---------|
| **EventDetail.js changes** | Detect `event.queueRequired`. If true and user not admitted: hide seat table, show "Join Queue" button. If in queue: show waiting room UI. If admitted: show seat table with countdown timer for admission expiry. |
| **WaitingRoom component** | New component rendered within EventDetail. Shows: queue position, estimated wait, "Leave Queue" button, auto-polls status. Animated/visual indicator of progress. |
| **Admin event form** | Add "Require Queue" toggle and "Queue Capacity" field to event create/edit forms in `AdminEventsPage.js`. |
| **Admin queue view** | Optional: admin page showing queue entries for an event (position, status, user). |

#### Queue Admission Design

| Property | Value | Rationale |
|----------|-------|-----------|
| Admission window | 15 minutes | Enough time to browse seats and create hold |
| Admission batch size | Dynamic: `available_seats - admitted_users` (min 1, max 50) | Don't admit more users than there are seats |
| Position counter | Redis `RAtomicLong` | Fast, atomic, no DB contention |
| Admitted set | Redis `RSet` with per-entry TTL | O(1) lookup during hold creation hot path |
| DB persistence | `queue_entries` table | Durable record; Redis is cache layer for performance |
| Unique constraint | One queue entry per user per event | Prevents re-joining after leaving |
| Scheduler interval | 30 seconds | Matches hold expiration scheduler cadence |

### Potential Issues & Mitigations

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Redis unavailability loses queue state** | Users lose position, admission checks fail | DB is source of truth. Redis is performance cache. On Redis miss, fall back to DB query. Rebuild Redis state from DB on startup. |
| **Race condition: admission expiry vs. hold creation** | User starts hold creation, admission expires mid-transaction | Check admission inside the pessimistic lock transaction. If expired, reject. 15-min window is generous. |
| **Position counter drift** | Redis counter resets on Redis restart | On scheduler tick, if Redis counter < max DB position for event, reset counter to max DB position + 1. |
| **Large queues (10k+ users)** | DB queries for position counting get slow | Index on `(event_id, status)` handles this. Redis counter avoids DB for position assignment. Polling is per-user (not full queue scan). |
| **User admitted but never selects seats** | Admission slot wasted for 15 minutes | 15-min expiry auto-releases. Scheduler re-admits next batch. Could reduce window for very high-demand events. |
| **Queue for events that don't need it** | Unnecessary friction for low-demand events | `queueRequired` defaults to `false`. Only admin-toggled. Could add auto-detection later (future enhancement). |
| **Frontend polling load** | Many users polling every 5s | Status endpoint is lightweight (single DB lookup or Redis check). Rate-limit to 60/min per user. Consider SSE for future optimization. |
| **Hold expiration scheduler interaction** | If hold expires while user is admitted, they can hold again (still admitted) | This is correct behavior — admission allows hold attempts, not just one. Admission expires independently. |
| **Existing events default to no queue** | No disruption | `DEFAULT FALSE` in migration means all existing events work exactly as before. |
| **Admin forgets to enable queue** | High-demand event has no protection | Document in admin UI. Could add recommendation based on event capacity vs. expected demand (future). |
| **Deadlock between queue admission and hold creation** | Both touch seats table | Queue admission doesn't touch seats — it only changes `queue_entries`. Hold creation touches `seats`. No shared lock contention. |

### Tests

**Backend:**
- QueueService: join queue (success, duplicate, non-queue event), get status (waiting, admitted, expired), leave queue.
- QueueAdmissionScheduler: admits correct batch size, respects available seat count, handles no-waiters.
- QueueExpirationScheduler: expires old admissions, removes from Redis.
- SeatHoldService: rejects hold for queue-required event without admission, allows hold with valid admission.
- Integration: full flow — join → wait → admit → hold → complete.

**Frontend:**
- EventDetail renders queue join button for queue-required events.
- WaitingRoom component shows position and updates on poll.
- Seat selection hidden until admitted.
- Admission expiry countdown displays correctly.

---

## Cross-Issue Conflicts & Risks

### 1. Flyway Migration Ordering

Three migrations are being added: V9 (verification), V10 (password reset), V11 (queue). These must be committed in order and never reordered. If developers work in parallel, coordinate version numbers to avoid Flyway checksum conflicts.

**Mitigation:** Assign version numbers now. If a migration lands on `main` before this branch merges, renumber accordingly.

### 2. User Entity Modifications

Both #89 (adds `emailVerified`) and the existing User entity are touched. If another branch also modifies `User.java`, merge conflicts are likely.

**Mitigation:** Implement #89's User changes first and merge before starting #91. Field additions are additive — low conflict risk if done sequentially.

### 3. AuthController Expansion

Issues #89 and #91 both add endpoints to `AuthController.java`:
- #89: `GET /auth/verify`, `POST /auth/resend-verification`
- #91: `POST /auth/forgot-password`, `POST /auth/reset-password`

**Mitigation:** Implement sequentially (#89 then #91). Both fit naturally under `/auth/**` which is already `permitAll` in SecurityConfig — no security config changes needed.

### 4. SecurityConfig Interactions

`/auth/**` is already `permitAll`. New verification and password reset endpoints under `/auth/` need no config changes. Queue endpoints under `/api/events/{eventId}/queue/` require authentication — they fall under the default `anyRequest().authenticated()` rule. Admin queue endpoints under `/api/admin/` are already gated by `hasRole("ADMIN")`.

**No SecurityConfig changes required for any of these issues.**

### 5. Existing User Data (Migration Safety)

- V9 adds `email_verified BOOLEAN DEFAULT FALSE` — existing users become unverified. **Must include `UPDATE users SET email_verified = TRUE WHERE deleted_at IS NULL;`** in the migration to avoid locking out existing users.
- V10 creates a new table only — no existing data impact.
- V11 adds `queue_required BOOLEAN DEFAULT FALSE` to events — existing events unaffected.

### 6. Docker Compose Changes

Issue #90 adds a MailHog container. This must not conflict with existing postgres/redis/backend services. Port 8025 (MailHog web UI) and 1025 (SMTP) are unlikely to conflict.

### 7. Rate Limiting Considerations

New endpoints that need rate limiting:
- `POST /auth/resend-verification` — 3/min per user
- `POST /auth/forgot-password` — 3/15min per IP (prevent enumeration)
- `POST /api/events/{eventId}/queue/join` — 10/min per user
- `GET /api/events/{eventId}/queue/status` — 60/min per user (polling)

These can use the existing `RateLimitService` with new URL-specific limits in `application.properties`.

---

## Migration Summary

| Version | Issue | Table/Column | Type |
|---------|-------|-------------|------|
| V9 | #89 | `users.email_verified`, `email_verification_tokens` | ALTER + CREATE |
| V10 | #91 | `password_reset_tokens` | CREATE |
| V11 | #88 | `events.queue_required`, `events.queue_capacity`, `queue_entries` | ALTER + CREATE |

All migrations are additive (new columns with defaults, new tables). No destructive changes. Rollback: drop tables and columns in reverse order.
