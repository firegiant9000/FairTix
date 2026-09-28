# Implementation Plan: Issues #93, #94, #95, #96, #97

**Branch:** `feature/issues-93-94-95-96-97`
**Base branch:** `feature/m1-queue-email-password-reset`
**Date:** 2026-04-15

---

## Overview

| Issue | Title | Milestone | Complexity |
|-------|-------|-----------|------------|
| [#93](#93-purchase-caps-per-user-per-event) | Purchase caps per user per event | M1 | Low |
| [#94](#94-expand-audit-logging) | Expand audit logging | M1 | Low |
| [#95](#95-jwt-refresh-token) | JWT refresh token | M1 | High |
| [#96](#96-venue-entity-and-admin-crud) | Venue entity and admin CRUD | M2 | Medium |
| [#97](#97-ticket-transfer-flow) | Ticket transfer flow | M2 | High |

> **Recommended implementation order:** #94 → #93 → #95 → #96 → #97
> Audit logging first because every subsequent feature needs it. Caps before refresh tokens (simpler). Venue before tickets transfer (transfer references events which reference venues).

---

## #93: Purchase Caps Per User Per Event

### Current State

There are no per-event purchase limits. The only limits that exist are system-wide seat hold limits:
- `holds.max-active-per-holder=5` — max concurrent active holds per user (all events)
- `holds.max-seats-per-hold=10` — max seats in a single hold
- Frontend enforces the 10-seat display limit in [`EventDetail.js:8`](frontend/webpages/src/pages/EventDetail.js#L8)

The `Event` entity ([`events/domain/Event.java`](backend/src/main/java/com/fairtix/events/domain/Event.java)) has no `maxTicketsPerUser` field. `OrderService` ([`orders/application/OrderService.java`](backend/src/main/java/com/fairtix/orders/application/OrderService.java)) does not validate tickets already purchased before completing an order. `TicketRepository` ([`tickets/infrastructure/TicketRepository.java`](backend/src/main/java/com/fairtix/tickets/infrastructure/TicketRepository.java)) has no count-by-user-and-event query.

### What Needs to Be Built

**1. DB Migration — V12**
```sql
-- Add max tickets per user cap to events table
ALTER TABLE events ADD COLUMN max_tickets_per_user INTEGER DEFAULT NULL;
-- NULL = no cap (backwards compatible)
```

**2. Event Entity** — [`events/domain/Event.java`](backend/src/main/java/com/fairtix/events/domain/Event.java)
- Add `private Integer maxTicketsPerUser;` with `@Column(name = "max_tickets_per_user")` and getter/setter

**3. Event service/DTO** — [`events/application/EventService.java`](backend/src/main/java/com/fairtix/events/application/EventService.java), `CreateEventRequest.java`, `UpdateEventRequest.java`, `EventResponse.java`
- Accept, persist, and return the new field

**4. Ticket Repository** — [`tickets/infrastructure/TicketRepository.java`](backend/src/main/java/com/fairtix/tickets/infrastructure/TicketRepository.java)
```java
long countByUser_IdAndEvent_IdAndStatusNot(UUID userId, UUID eventId, TicketStatus status);
```

**5. Order Service** — [`orders/application/OrderService.java`](backend/src/main/java/com/fairtix/orders/application/OrderService.java)
- In `createOrder()` and `createOrderWithPayment()`, before completing the order:
  1. Determine the event(s) in the hold
  2. For each event, fetch `event.maxTicketsPerUser`
  3. Count existing `VALID` tickets for this user + event
  4. If `existingCount + newTicketCount > maxTicketsPerUser`, throw `PurchaseCapExceededException`

**6. Frontend** — [`pages/EventDetail.js`](frontend/webpages/src/pages/EventDetail.js)
- Read `maxTicketsPerUser` from the event API response
- Calculate `remaining = maxTicketsPerUser - alreadyOwned` and display it
- Prevent adding seats beyond `remaining`

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Race condition: two concurrent orders both pass the cap check | Enforce at DB level with a trigger or check constraint, or use `SELECT FOR UPDATE` on the user-event ticket count |
| Existing events without cap receive a cap by mistake | Use `NULL` as "no cap" — never apply limit when field is null |
| Seat holds are not yet orders — user can hold more than cap | Validate cap at hold creation too, or at minimum at order time (current approach) |
| Admin sets cap below existing ticket count | Validate at update time: cap must be >= currently issued tickets for that event |

---

## #94: Expand Audit Logging

### Current State

The audit infrastructure is solid:
- `AuditLog` entity — [`audit/domain/AuditLog.java`](backend/src/main/java/com/fairtix/audit/domain/AuditLog.java)
- `AuditService.log()` — [`audit/application/AuditService.java`](backend/src/main/java/com/fairtix/audit/application/AuditService.java), uses `REQUIRES_NEW` propagation (safe on rollback)
- DB: `audit_logs` table from V5 migration, indexed on `user_id` and `created_at`

**12 events are currently logged across 4 domains:**

| Domain | Service | Events Logged |
|--------|---------|---------------|
| Orders | `OrderService` | `CREATE ORDER`, `CANCEL ORDER` |
| Inventory | `SeatHoldService` | `CREATE HOLD`, `RELEASE HOLD`, `CONFIRM HOLD` |
| Events | `EventController` | `CREATE EVENT`, `UPDATE EVENT`, `DELETE EVENT` |
| Auth | `PasswordResetService` | `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED` |

**Domains with no audit logging at all:**

| Domain | Service | Missing Events |
|--------|---------|----------------|
| Auth | `AuthService` | User registration, login |
| Users | `UserService` | Account deletion (user + admin) |
| Payments | `PaymentSimulationService` | Payment processed, payment failed |
| Tickets | `TicketService` | Ticket issued |
| Queue | `QueueService` | Join queue, leave queue, admit batch, expire admissions |

### What Needs to Be Built

Inject `AuditService` into each service below and add `auditService.log(...)` calls. No schema changes needed.

**`AuthService`** — [`auth/application/AuthService.java`](backend/src/main/java/com/fairtix/auth/application/AuthService.java)
- After `register()`: log `USER_REGISTERED` / `USER` / userId
- After `login()`: log `USER_LOGIN` / `USER` / userId

**`UserService`** — [`users/application/UserService.java`](backend/src/main/java/com/fairtix/users/application/UserService.java)
- After `deleteAccount()`: log `USER_DELETED` / `USER` / userId
- After `adminDeleteAccount()`: log `ADMIN_USER_DELETED` / `USER` / targetUserId (actorId = adminId)

**`PaymentSimulationService`** — [`payments/application/PaymentSimulationService.java`](backend/src/main/java/com/fairtix/payments/application/PaymentSimulationService.java)
- On success: log `PAYMENT_PROCESSED` / `PAYMENT` / paymentRecordId
- On failure: log `PAYMENT_FAILED` / `PAYMENT` / orderId

**`TicketService`** — [`tickets/application/TicketService.java`](backend/src/main/java/com/fairtix/tickets/application/TicketService.java)
- After `issueTickets()`: log `TICKET_ISSUED` / `TICKET` / ticketId per ticket

**`QueueService`** — [`queue/application/QueueService.java`](backend/src/main/java/com/fairtix/queue/application/QueueService.java)
- After `joinQueue()`: log `QUEUE_JOINED` / `QUEUE` / eventId
- After `leaveQueue()`: log `QUEUE_LEFT` / `QUEUE` / eventId
- After `admitNextBatch()`: log `QUEUE_ADMITTED` / `QUEUE` / eventId
- After expired admissions cleaned: log `QUEUE_ADMISSION_EXPIRED` / `QUEUE` / eventId

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Bulk audit writes on `admitNextBatch()` (many users at once) | Log one event per batch (not per user) with user count in `details` |
| `TicketService.issueTickets()` issues N tickets in a loop — N audit rows per order | Log one `TICKETS_ISSUED` event per order with ticket count in `details` rather than per ticket |
| AuditService failure blocking business transaction | `REQUIRES_NEW` propagation already handles this; ensure all new call sites are non-critical |
| userId not available in `PaymentSimulationService` | Pass userId through to the payment service from `OrderService` |

---

## #95: JWT Refresh Token

### Current State

- Access token: 15-minute expiry, JJWT 0.13.0, stored in `fairtix_token` HttpOnly cookie — [`auth/application/JwtService.java:20`](backend/src/main/java/com/fairtix/auth/application/JwtService.java#L20)
- Cookie set with `maxAge=900`, `httpOnly=true`, `sameSite=Lax` — [`auth/api/AuthController.java:44`](backend/src/main/java/com/fairtix/auth/api/AuthController.java#L44)
- Logout clears cookie with `maxAge=0` — [`auth/api/AuthController.java:181`](backend/src/main/java/com/fairtix/auth/api/AuthController.java#L181)
- Filter reads cookie first, then Bearer header — [`auth/application/JwtAuthenticationFilter.java:35`](backend/src/main/java/com/fairtix/auth/application/JwtAuthenticationFilter.java#L35)
- Frontend `AuthContext.js` has `logout(expired)` and `sessionExpired` banner — no automatic refresh
- Frontend `client.js` throws on 401 but has no retry/refresh interceptor

**Nothing exists for refresh tokens:** no entity, no table, no endpoint, no rotation logic.

### What Needs to Be Built

**1. DB Migration — V13**
```sql
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(255) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_token_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_token_expiry ON refresh_tokens(expires_at);
```

**2. RefreshToken Entity** — new file `auth/domain/RefreshToken.java`
- Fields: `id`, `user`, `tokenHash`, `expiresAt`, `revoked`, `createdAt`, `lastUsedAt`

**3. RefreshTokenRepository** — new file `auth/infrastructure/RefreshTokenRepository.java`
```java
Optional<RefreshToken> findByTokenHash(String hash);
void deleteAllByUser_Id(UUID userId);
void deleteAllByExpiresAtBefore(Instant now); // for cleanup
```

**4. JwtService** — [`auth/application/JwtService.java`](backend/src/main/java/com/fairtix/auth/application/JwtService.java)
- Add `generateRefreshToken()` — generates an opaque random token (UUID or SecureRandom), stores hash in DB, returns raw token
- Keep access token generation as-is (15 min)
- Refresh token TTL: 7 days, configurable via `app.jwt.refresh-expiry-days`

**5. AuthController** — [`auth/api/AuthController.java`](backend/src/main/java/com/fairtix/auth/api/AuthController.java)
- On `login()`: generate both access + refresh token; set access token cookie (`fairtix_token`, 15 min) and refresh token cookie (`fairtix_refresh`, 7 days, httpOnly)
- New endpoint `POST /auth/refresh`:
  1. Read `fairtix_refresh` cookie
  2. Hash the raw token
  3. Look up in DB — validate not revoked, not expired
  4. Rotate: revoke old token, issue new refresh token (token rotation)
  5. Issue new access token
  6. Set both new cookies
  7. Return 200 or 401
- On `logout()`: revoke refresh token in DB + clear both cookies
- Expose `/auth/refresh` as a public route in `SecurityConfig`

**6. Frontend `client.js`** — [`api/client.js`](frontend/webpages/src/api/client.js)
- Add a response interceptor: on 401, call `POST /auth/refresh`
- If refresh succeeds, retry the original request once
- If refresh fails (401 again), call `logout(true)` to show the session-expired banner
- Use an in-flight flag to prevent multiple simultaneous refresh calls

**7. Frontend `AuthContext.js`** — [`auth/AuthContext.js`](frontend/webpages/src/auth/AuthContext.js)
- No changes needed to logout banner — it already handles `expired=true`

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Refresh token stolen from cookie | Cookie is httpOnly + Secure in production; Lax SameSite prevents CSRF on refresh |
| Refresh endpoint CSRF (POST to /auth/refresh with Lax cookie) | Lax SameSite blocks cross-origin POST; add `Origin` header check in prod |
| Token rotation race: two tabs both try to refresh simultaneously | Use DB unique constraint on `token_hash`; first one wins, second gets 401 and re-logs in gracefully |
| Revoked refresh tokens left in DB | Scheduled cleanup of expired/revoked tokens via `@Scheduled` + `deleteAllByExpiresAtBefore()` |
| Refresh loop: refresh fails, retry fails, infinite redirect | In-flight flag + max one retry in `client.js`; second 401 from refresh path goes straight to logout |
| Existing sessions break on deploy | Existing sessions have no refresh token in DB; on next 401, user sees session-expired banner and logs in again (acceptable) |

---

## #96: Venue Entity and Admin CRUD

### Current State

The `venues/` package directory exists with empty `.gitkeep` files in all subdirectories — no code is implemented.

The `Event` entity stores venue as a plain `String venue` column ([`events/domain/Event.java:25`](backend/src/main/java/com/fairtix/events/domain/Event.java#L25)) — no FK, no validation. The `events` table has `venue VARCHAR(255)`. `EventController` accepts `venueName` as a filter parameter and does a `LIKE` search against the string column. There is no venue management API or frontend page. The admin analytics page `EventsByVenueChart.js` groups events by the raw venue string.

### What Needs to Be Built

**1. DB Migration — V14**
```sql
-- Create venues table
CREATE TABLE venues (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL UNIQUE,
  address VARCHAR(500),
  city VARCHAR(100),
  country VARCHAR(100),
  capacity INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Migrate existing venue strings to venues rows
INSERT INTO venues (name)
SELECT DISTINCT venue FROM events WHERE venue IS NOT NULL
ON CONFLICT (name) DO NOTHING;

-- Add venue_id FK to events
ALTER TABLE events ADD COLUMN venue_id UUID REFERENCES venues(id);

-- Backfill venue_id from existing venue string
UPDATE events e SET venue_id = v.id FROM venues v WHERE e.venue = v.name;

-- Drop old venue string column (after confirming backfill)
ALTER TABLE events DROP COLUMN venue;
```

**2. Venue Entity** — new file [`venues/domain/Venue.java`](backend/src/main/java/com/fairtix/venues/domain/Venue.java)
- Fields: `id`, `name`, `address`, `city`, `country`, `capacity`, `createdAt`, `updatedAt`

**3. VenueRepository** — new file [`venues/infrastructure/VenueRepository.java`](backend/src/main/java/com/fairtix/venues/infrastructure/VenueRepository.java)
- `Optional<Venue> findByName(String name)`
- `Page<Venue> findAll(Pageable pageable)`

**4. VenueService** — new file [`venues/application/VenueService.java`](backend/src/main/java/com/fairtix/venues/application/VenueService.java)
- `createVenue(CreateVenueRequest)` — validates name uniqueness
- `updateVenue(UUID id, UpdateVenueRequest)` — validates name uniqueness if changed
- `deleteVenue(UUID id)` — reject if any events reference this venue
- `listVenues(Pageable)` — paginated
- `getVenue(UUID id)` — or 404
- Emit audit events: `CREATE VENUE`, `UPDATE VENUE`, `DELETE VENUE`

**5. VenueController** — new file [`venues/api/VenueController.java`](backend/src/main/java/com/fairtix/venues/api/VenueController.java)
```
POST   /api/venues            (ADMIN role required)
GET    /api/venues            (public)
GET    /api/venues/{id}       (public)
PUT    /api/venues/{id}       (ADMIN)
DELETE /api/venues/{id}       (ADMIN)
```

**6. Event Entity refactor** — [`events/domain/Event.java`](backend/src/main/java/com/fairtix/events/domain/Event.java)
- Replace `private String venue` with `@ManyToOne @JoinColumn(name = "venue_id") private Venue venue`

**7. Event DTOs** — `CreateEventRequest`, `UpdateEventRequest`, `EventResponse`
- Replace `String venue` with `UUID venueId` in request, `VenueResponse` embedded in response

**8. EventService** — [`events/application/EventService.java`](backend/src/main/java/com/fairtix/events/application/EventService.java)
- Look up `Venue` by `venueId` when creating/updating events; throw 404 if not found

**9. Frontend Admin Page** — new file [`admin/pages/AdminVenuesPage.js`](frontend/webpages/src/admin/pages/AdminVenuesPage.js)
- Table listing all venues with create/edit/delete actions
- Form dialog for create/edit
- Delete confirmation; block delete if venue has events

**10. Admin Sidebar** — [`admin/components/AdminSidebar.js`](frontend/webpages/src/admin/components/AdminSidebar.js)
- Add "Venues" navigation item

**11. Event creation form** — update the event form to select venue from a dropdown (fetched from `GET /api/venues`) instead of a free-text input

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Migration data loss: backfill from string to FK fails if `venue` string has inconsistent casing or whitespace | Normalize during insert: `TRIM(LOWER(...))` for dedup, or review distinct values first |
| Deleting a venue that has events | Check in `VenueService.deleteVenue()` via `eventRepository.existsByVenue_Id(venueId)`; throw 409 if true |
| Event analytics `EventsByVenueChart.js` breaks after migration | Update the chart to use `venue.name` from the nested object instead of the top-level string |
| `EventController` `venueName` filter param breaks | Update to join against venues table or filter by `venue.name` via the relationship |
| Existing `EventResponse` consumers (frontend) expect `venue: String` | Bump the field to `venue: { id, name, city }` or add `venueName` as a computed field to avoid a breaking change |

---

## #97: Ticket Transfer Flow

### Current State

`TicketStatus` enum ([`tickets/domain/TicketStatus.java`](backend/src/main/java/com/fairtix/tickets/domain/TicketStatus.java)) already has a `TRANSFERRED` value — it's a placeholder only. No business logic, no endpoints, no UI, no DB structure exists for transfers.

The `Ticket` entity has no transfer-tracking fields. `TicketService` has no transfer methods. `TicketController` exposes only `GET /api/tickets` and `GET /api/tickets/{id}`. The frontend `MyTickets.js` and `TicketCard.js` show ticket status but have no transfer action. Audit logging does not cover any transfer events.

### What Needs to Be Built

**1. DB Migration — V15**
```sql
CREATE TABLE ticket_transfer_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ticket_id UUID NOT NULL REFERENCES tickets(id),
  from_user_id UUID NOT NULL REFERENCES users(id),
  to_user_id UUID NOT NULL REFERENCES users(id),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  -- PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_transfer_ticket ON ticket_transfer_requests(ticket_id);
CREATE INDEX idx_transfer_to_user ON ticket_transfer_requests(to_user_id);
CREATE INDEX idx_transfer_from_user ON ticket_transfer_requests(from_user_id);
CREATE INDEX idx_transfer_expires ON ticket_transfer_requests(expires_at) WHERE status = 'PENDING';
```

**2. TicketTransferRequest Entity** — new file [`tickets/domain/TicketTransferRequest.java`](backend/src/main/java/com/fairtix/tickets/domain/TicketTransferRequest.java)
- Fields: `id`, `ticket`, `fromUser`, `toUser`, `status` (enum), `createdAt`, `expiresAt`, `resolvedAt`

**3. TransferStatus Enum** — new file [`tickets/domain/TransferStatus.java`](backend/src/main/java/com/fairtix/tickets/domain/TransferStatus.java)
- Values: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`, `EXPIRED`

**4. TicketTransferRequestRepository** — new file [`tickets/infrastructure/TicketTransferRequestRepository.java`](backend/src/main/java/com/fairtix/tickets/infrastructure/TicketTransferRequestRepository.java)
```java
List<TicketTransferRequest> findByToUser_IdAndStatus(UUID userId, TransferStatus status);
List<TicketTransferRequest> findByFromUser_IdAndTicket_Id(UUID userId, UUID ticketId);
List<TicketTransferRequest> findByStatusAndExpiresAtBefore(TransferStatus status, Instant now);
Optional<TicketTransferRequest> findByTicket_IdAndStatus(UUID ticketId, TransferStatus status);
```

**5. TransferService** — new file [`tickets/application/TransferService.java`](backend/src/main/java/com/fairtix/tickets/application/TransferService.java)
- `createTransferRequest(UUID ticketId, UUID fromUserId, String toEmail)`:
  - Validate ticket is VALID and owned by `fromUserId`
  - Validate no other PENDING transfer exists for this ticket
  - Look up `toUser` by email; 404 if not found
  - Reject transfer-to-self
  - Create `TicketTransferRequest` with 7-day expiry
  - Emit `TRANSFER_REQUESTED` audit event
  - Send notification email to `toUser`
- `acceptTransfer(UUID requestId, UUID toUserId)`:
  - Validate request exists, status=PENDING, `toUserId` matches
  - Validate not expired
  - Update ticket: `setUser(toUser)`, `setStatus(VALID)` (stays VALID, new owner)
  - Set original ticket's `status = TRANSFERRED` only if we want to preserve old record; otherwise update in place
  - Mark request `ACCEPTED`, set `resolvedAt`
  - Emit `TRANSFER_ACCEPTED` audit event
  - Notify original owner
- `rejectTransfer(UUID requestId, UUID toUserId)`: mark REJECTED
- `cancelTransfer(UUID requestId, UUID fromUserId)`: mark CANCELLED (only sender can cancel)
- `@Scheduled` expire job: find all PENDING with `expiresAt < now()`, mark EXPIRED, notify sender

**6. TransferController** — new file [`tickets/api/TransferController.java`](backend/src/main/java/com/fairtix/tickets/api/TransferController.java)
```
POST /api/tickets/{ticketId}/transfer                      → createTransferRequest
GET  /api/transfers/incoming                               → list pending incoming requests
GET  /api/transfers/outgoing                               → list sent requests (all statuses)
POST /api/transfers/{requestId}/accept                     → acceptTransfer
POST /api/transfers/{requestId}/reject                     → rejectTransfer
POST /api/transfers/{requestId}/cancel                     → cancelTransfer
```

**7. Frontend — Transfer Dialog** — new file [`components/TransferDialog.js`](frontend/webpages/src/components/TransferDialog.js)
- Recipient email input
- Confirmation summary: ticket event, seat, date
- Submit creates transfer request via API

**8. Frontend — MyTickets / TicketCard** — [`pages/MyTickets.js`](frontend/webpages/src/pages/MyTickets.js), [`components/TicketCard.js`](frontend/webpages/src/components/TicketCard.js)
- Add "Transfer" button (shown only for VALID tickets)
- Opens `TransferDialog`

**9. Frontend — Transfers Page** — new file [`pages/TransferRequests.js`](frontend/webpages/src/pages/TransferRequests.js)
- "Incoming Requests" tab: pending requests with Accept / Reject
- "Sent Requests" tab: outgoing with Cancel option
- Link from navbar or My Tickets page

**10. Notification Emails** — use existing `SmtpEmailService` + `EmailTemplateService`
- Transfer request received
- Transfer accepted / rejected / cancelled / expired

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Two users simultaneously accept the same transfer | Use `@Transactional` + query the request status inside the lock; second accept hits REJECTED request and throws 409 |
| Ticket transferred to a user who already owns max cap tickets for the event | Check purchase cap (from #93) during accept — count existing tickets for `toUser` on the event |
| Sender re-uses ticket after requesting a transfer (ticket is still VALID) | Lock: while a PENDING transfer exists, disallow ticket use (`TicketController` should check for PENDING transfer on ticket validation) |
| Transfer expires but sender is not notified | Scheduled expiry job (`@Scheduled`) sends notification email when setting status to EXPIRED |
| Large volume of pending/expired transfers bloat the table | Add cleanup job: purge EXPIRED/REJECTED/CANCELLED requests older than 90 days |
| `toUserEmail` lookup exposes whether an email is registered | Return a generic "Transfer request sent" regardless of whether the email was found (security: enumeration prevention) |

---

## Migration Version Map

| Version | File | Purpose |
|---------|------|---------|
| V12 | `V12__add_purchase_cap.sql` | Add `max_tickets_per_user` to `events` |
| V13 | `V13__create_refresh_tokens.sql` | Refresh tokens table |
| V14 | `V14__create_venues.sql` | Venues table + migrate event.venue string to FK |
| V15 | `V15__create_ticket_transfers.sql` | Ticket transfer requests table |

---

## Phased Implementation Order

### Phase 1 — #94: Expand Audit Logging (no schema changes, lowest risk)
1. Inject `AuditService` into: `AuthService`, `UserService`, `PaymentSimulationService`, `TicketService`, `QueueService`
2. Add `log()` calls at each mutation point (see table above)
3. Verify with integration test: trigger each action, assert `audit_logs` has a row

### Phase 2 — #93: Purchase Caps (small schema change, isolated to event + order domains)
1. Write V12 migration
2. Add `maxTicketsPerUser` to `Event` entity and DTOs
3. Add `countByUser_IdAndEvent_Id` to `TicketRepository`
4. Add cap validation in `OrderService.createOrder()` and `createOrderWithPayment()`
5. Update frontend `EventDetail.js` to display remaining allowance
6. Test: set cap on event, purchase up to cap, verify 7th ticket rejected

### Phase 3 — #95: JWT Refresh Token (complex, but self-contained)
1. Write V13 migration
2. Create `RefreshToken` entity + repository
3. Extend `JwtService` with `generateRefreshToken()` and hash/lookup helpers
4. Update `AuthController`: login sets both cookies, add `POST /auth/refresh`, logout revokes both
5. Update `SecurityConfig` to allow `/auth/refresh` public
6. Update frontend `client.js` with 401 interceptor + one-retry refresh
7. Add `@Scheduled` cleanup for expired/revoked tokens
8. Test: login → wait for access token to expire → make request → confirm auto-refresh → logout → confirm refresh revoked

### Phase 4 — #96: Venue Entity and Admin CRUD (schema + frontend)
1. Write V14 migration (including data backfill)
2. Create `Venue` entity, repository, service, controller
3. Refactor `Event` entity from `String venue` to `@ManyToOne Venue`
4. Update event DTOs, service, and controller
5. Create `AdminVenuesPage.js` and wire into sidebar
6. Update event creation form to use venue dropdown
7. Test: create venue → create event with venue → update venue → attempt to delete venue with events (should fail)

### Phase 5 — #97: Ticket Transfer Flow (complex, depends on #93 cap check)
1. Write V15 migration
2. Create `TransferStatus` enum, `TicketTransferRequest` entity, repository
3. Create `TransferService` with all transfer lifecycle methods
4. Create `TransferController` with all endpoints
5. Integrate purchase cap check in `acceptTransfer()`
6. Add notification emails via existing `SmtpEmailService`
7. Create frontend `TransferDialog`, `TransferRequests.js`, update `TicketCard`
8. Test: request transfer → accept → verify ticket owner changed → verify audit events → test expiry + cleanup

---

## Definition of Done

- [ ] All required unit/integration tests pass for each issue
- [ ] No unrelated files modified
- [ ] Each Flyway migration runs cleanly (forward and idempotent)
- [ ] Audit events emitted for all new state-mutating operations
- [ ] Summary PR includes: what changed, why, risks, follow-up tasks
