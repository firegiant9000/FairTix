# Implementation Plan: Issues #98, #99, #100, #101, #102, #103

**Branch:** `feature/issues-98-103-m2-implementation`
**Base branch:** `feature/issues-93-94-95-96-97`
**Date:** 2026-04-16

---

## Overview

| Issue | Title | Milestone | Complexity |
|-------|-------|-----------|------------|
| [#98](#98-refund-workflow) | Refund workflow | M2 | High |
| [#99](#99-admin-event-lifecycle-controls) | Admin event lifecycle controls | M2 | Medium |
| [#100](#100-support-ticket-system) | Support ticket system | M2 | Medium |
| [#101](#101-order-confirmation--hold-expiry-emails) | Order confirmation / hold-expiry emails | M2 | Low-Medium |
| [#102](#102-graphical-seat-map-ui) | Graphical seat map UI | M2 | High |
| [#103](#103-frontend-test-coverage-expansion) | Frontend test coverage expansion | M2 | Medium |

> **Recommended implementation order:** #99 → #101 → #98 → #100 → #102 → #103
>
> Event lifecycle first — other features reference event status (refund cascades on cancellation, emails reference event state). Emails next because refund notifications depend on the template system. Refunds after lifecycle since cancellation triggers automatic refunds. Support tickets and seat map are independent. Tests last to cover everything built.

---

## #99: Admin Event Lifecycle Controls

### Current State

Events have full CRUD via [`EventController.java`](backend/src/main/java/com/fairtix/events/api/EventController.java) with `POST/GET/PUT/DELETE /api/events`. The [`Event.java`](backend/src/main/java/com/fairtix/events/domain/Event.java) entity stores title, venue, startTime, organizerId, queueRequired, queueCapacity, and maxTicketsPerUser. ADMIN role is enforced via `@PreAuthorize("hasRole('ADMIN')")` and organizer ownership is checked on update/delete.

The [`AdminEventsPage.js`](frontend/webpages/src/admin/pages/AdminEventsPage.js) shows a paginated event table with search, create/edit/delete dialogs, and navigation to seat management.

**What's missing:** There is no `EventStatus` enum — events have no lifecycle states. No concept of draft, published, active, completed, cancelled, or archived. Events are hard-deleted (no soft delete). No state transition endpoints, cascade behaviors, or status-based filtering in the admin UI. Every other status-bearing entity in the system (holds, seats, tickets, orders, payments, queue, transfers) has a status enum — events are the exception.

### What Needs to Be Built

**1. DB Migration — V16**
```sql
-- Add lifecycle status and transition timestamps to events
ALTER TABLE events ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE events ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN cancellation_reason TEXT;

CREATE INDEX idx_events_status ON events(status);

-- Backfill: existing events become PUBLISHED (they were already visible)
UPDATE events SET status = 'PUBLISHED', published_at = created_at;
```

**2. EventStatus Enum** — New file `events/domain/EventStatus.java`
```java
public enum EventStatus {
    DRAFT,       // Created but not visible to public
    PUBLISHED,   // Visible, tickets not yet on sale (optional pre-sale state)
    ACTIVE,      // On sale — seats can be held/purchased
    COMPLETED,   // Event has occurred, no new purchases
    CANCELLED,   // Cancelled — triggers refund cascade
    ARCHIVED     // Hidden from all views, retained for records
}
```

**3. Event Entity** — [`events/domain/Event.java`](backend/src/main/java/com/fairtix/events/domain/Event.java)
- Add `status` (EventStatus, default DRAFT), `publishedAt`, `cancelledAt`, `completedAt`, `archivedAt` (Instant), `cancellationReason` (String)
- Add state transition methods with validation:
  - `publish()` — only from DRAFT
  - `activate()` — only from PUBLISHED
  - `complete()` — only from ACTIVE
  - `cancel(reason)` — from DRAFT, PUBLISHED, or ACTIVE
  - `archive()` — from COMPLETED or CANCELLED

**4. EventService** — [`events/application/EventService.java`](backend/src/main/java/com/fairtix/events/application/EventService.java)
- `publishEvent(eventId, userId)` — validates ownership, transitions DRAFT → PUBLISHED
- `activateEvent(eventId, userId)` — transitions PUBLISHED → ACTIVE
- `completeEvent(eventId, userId)` — transitions ACTIVE → COMPLETED
- `cancelEvent(eventId, userId, reason)` — transitions to CANCELLED, triggers cascade:
  - Release all ACTIVE holds for this event
  - Mark all VALID tickets as CANCELLED
  - Queue refund requests for all COMPLETED orders (depends on #98)
- `archiveEvent(eventId, userId)` — transitions to ARCHIVED
- Update `search()` to exclude DRAFT events from public queries (only PUBLISHED/ACTIVE visible to non-admins)

**5. EventController** — [`events/api/EventController.java`](backend/src/main/java/com/fairtix/events/api/EventController.java)
- `POST /api/events/{id}/publish` — ADMIN only
- `POST /api/events/{id}/activate` — ADMIN only
- `POST /api/events/{id}/complete` — ADMIN only
- `POST /api/events/{id}/cancel` — ADMIN only, accepts `{ reason: string }`
- `POST /api/events/{id}/archive` — ADMIN only
- Update `GET /api/events` to accept optional `status` filter param
- Update `EventResponse` DTO to include status and timestamp fields

**6. Frontend — AdminEventsPage**
- Add status badge column to event table (color-coded chips)
- Add status filter dropdown (All / Draft / Published / Active / Completed / Cancelled / Archived)
- Add action buttons per status: Publish, Activate, Complete, Cancel (with reason dialog), Archive
- Disable edit/delete for COMPLETED, CANCELLED, ARCHIVED events
- Add cancellation confirmation dialog with reason text field and warning about cascade effects

**7. Frontend — Public Event Pages**
- [`Events.js`](frontend/webpages/src/pages/Events.js) and [`EventDetail.js`](frontend/webpages/src/pages/EventDetail.js): show only PUBLISHED/ACTIVE events
- Display "Coming Soon" for PUBLISHED (no seat selection), enable seat selection only for ACTIVE
- Show "Event Cancelled" or "Event Completed" banners for those states

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Backfill: existing events defaulting to DRAFT would hide them | Backfill migration sets existing events to PUBLISHED |
| Cancel cascade without refund system (#98) | Implement cancel cascade to mark tickets CANCELLED; refund integration added when #98 is built |
| Race condition: admin cancels while user is checking out | Use optimistic locking (`@Version`) on Event entity; checkout validates event status before completing |
| ACTIVE vs PUBLISHED confusion | Clear UI labels: PUBLISHED = "Announced" (visible, no sales), ACTIVE = "On Sale" |

---

## #101: Order Confirmation / Hold-Expiry Emails

### Current State

Email infrastructure is **production-ready**:
- [`SmtpEmailService.java`](backend/src/main/java/com/fairtix/notifications/infrastructure/SmtpEmailService.java) — fully implemented with JavaMailSender, MIME support, HTML, UTF-8
- [`EmailTemplateService.java`](backend/src/main/java/com/fairtix/notifications/application/EmailTemplateService.java) — builds HTML templates for verification, password reset, and ticket transfers
- [`NotificationPreference.java`](backend/src/main/java/com/fairtix/notifications/domain/NotificationPreference.java) — has `emailOrder` (default: true) and `emailHold` (default: false) flags
- [`HoldExpirationScheduler.java`](backend/src/main/java/com/fairtix/inventory/scheduler/HoldExpirationScheduler.java) — runs every 30s, expires holds, releases seats — but **sends no notifications**
- [`OrderService.java`](backend/src/main/java/com/fairtix/orders/application/OrderService.java) — creates orders, issues tickets — but **sends no confirmation emails**

**What's missing:** Two email template methods and two service integration points. The preference flags exist but are never checked.

### What Needs to Be Built

**1. EmailTemplateService** — [`notifications/application/EmailTemplateService.java`](backend/src/main/java/com/fairtix/notifications/application/EmailTemplateService.java)

Add two new template methods:

- `buildOrderConfirmationEmail(userName, orderId, eventTitle, venueName, eventDate, seats, totalPrice)` — HTML email with:
  - Order ID and confirmation number
  - Event details (title, venue, date/time)
  - Seat list (section, row, seat number, price per seat)
  - Total price
  - Link to "View My Tickets" page
  - Cancellation/refund policy note

- `buildHoldExpiryEmail(userName, eventTitle, seats, holdId)` — HTML email with:
  - Notification that held seats have been released
  - Event name and seat details that were lost
  - Link to browse event again
  - Encouragement to act faster next time

**2. OrderService Integration** — [`orders/application/OrderService.java`](backend/src/main/java/com/fairtix/orders/application/OrderService.java)
- Inject `EmailService`, `EmailTemplateService`, `NotificationPreferenceService`, `UserRepository`
- After successful order creation in `createOrder()` and `createOrderWithPayment()`:
  1. Look up user's notification preferences
  2. If `emailOrder` is true, build and send order confirmation email
  3. Wrap email send in try-catch — log failure but **do not fail the order transaction**

**3. HoldExpirationScheduler Integration** — [`inventory/scheduler/HoldExpirationScheduler.java`](backend/src/main/java/com/fairtix/inventory/scheduler/HoldExpirationScheduler.java)
- Inject `EmailService`, `EmailTemplateService`, `NotificationPreferenceService`, `UserRepository`
- After marking each hold as EXPIRED:
  1. Look up the hold owner's notification preferences
  2. If `emailHold` is true, build and send hold expiry email
  3. Batch email sends to avoid overwhelming SMTP during large expiry runs
  4. Log failures, never block the expiry process

**4. Frontend — Checkout Success Enhancement** — [`pages/Checkout.js`](frontend/webpages/src/pages/Checkout.js)
- After successful payment, show "A confirmation email has been sent to your email address"
- Display full order details on success screen (event, venue, seats, total)

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Email send failure blocks order completion | Send email asynchronously or in try-catch after transaction commits; log failure |
| Hold expiry scheduler slows down from email sends | Batch emails, use async sending; scheduler continues even if email fails |
| SMTP not configured in production | Fail gracefully with logging; add health check for SMTP connectivity |
| Spam: users get emails they didn't want | Respect notification preference flags (emailOrder, emailHold) — already exist |
| Email template rendering fails | Add fallback plain-text email if HTML template throws |

---

## #98: Refund Workflow

### Current State

The payment/order infrastructure is **~40% ready** for refunds:
- [`Order.java`](backend/src/main/java/com/fairtix/orders/domain/Order.java) — `OrderStatus` already includes `REFUNDED`
- [`PaymentRecord.java`](backend/src/main/java/com/fairtix/payments/domain/PaymentRecord.java) — tracks transactions with orderId, amount, status, transactionId
- [`PaymentStatus.java`](backend/src/main/java/com/fairtix/payments/domain/PaymentStatus.java) — has SUCCESS, FAILURE, CANCELLED but **no REFUNDED**
- [`TicketStatus.java`](backend/src/main/java/com/fairtix/tickets/domain/TicketStatus.java) — has VALID, USED, TRANSFERRED, CANCELLED but **no REFUNDED**
- [`Seat.java`](backend/src/main/java/com/fairtix/inventory/domain/Seat.java) — SeatStatus has AVAILABLE, HELD, BOOKED, SOLD
- [`AuditService.java`](backend/src/main/java/com/fairtix/audit/application/AuditService.java) — ready for refund action logging
- Frontend: [`MyTickets.js`](frontend/webpages/src/pages/MyTickets.js) and [`TicketCard.js`](frontend/webpages/src/components/TicketCard.js) show tickets with status but have no refund action

**What's missing:** No RefundRecord entity, no refund service/controller/DTOs, no refund business rules, no refund UI, no refund email templates.

### What Needs to Be Built

**1. DB Migration — V17**
```sql
CREATE TABLE refund_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES orders(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    amount          NUMERIC(10,2) NOT NULL,
    reason          TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    admin_notes     TEXT,
    reviewed_by     UUID REFERENCES users(id),
    reviewed_at     TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_order ON refund_requests(order_id);
CREATE INDEX idx_refund_user ON refund_requests(user_id);
CREATE INDEX idx_refund_status ON refund_requests(status);
```

**2. Enum Extensions**
- `PaymentStatus.java` — add `REFUNDED`
- `TicketStatus.java` — add `REFUNDED`

**3. Refund Domain Model** — New file `refunds/domain/RefundRequest.java`
- Fields: id, orderId, userId, amount, reason, status (RefundStatus enum), adminNotes, reviewedBy, reviewedAt, completedAt, createdAt, updatedAt
- `RefundStatus` enum: `REQUESTED`, `APPROVED`, `COMPLETED`, `REJECTED`, `CANCELLED`

**4. Refund DTOs** — New files in `refunds/dto/`
- `CreateRefundRequest` — orderId, reason
- `RefundApprovalRequest` — approved (boolean), adminNotes
- `RefundResponse` — all fields for API response

**5. RefundService** — New file `refunds/application/RefundService.java`
- `requestRefund(userId, orderId, reason)`:
  1. Validate order exists and belongs to user
  2. Validate order status is COMPLETED (not already refunded/cancelled)
  3. Validate no existing pending refund for this order
  4. Validate refund eligibility (configurable time window, event not yet occurred)
  5. Calculate refund amount (full order total)
  6. Create RefundRequest with status REQUESTED
  7. Audit log: REFUND_REQUESTED
  8. Send email notification to user (refund request received)

- `reviewRefund(adminUserId, refundId, approved, notes)`:
  1. Validate refund exists and is in REQUESTED status
  2. If approved: transition to APPROVED, then process refund
  3. If rejected: transition to REJECTED with admin notes
  4. Audit log: REFUND_APPROVED or REFUND_REJECTED

- `processRefund(refundRequest)` (called after approval):
  1. Transition order status to REFUNDED
  2. Create PaymentRecord with REFUNDED status (negative amount)
  3. Mark all tickets for this order as REFUNDED
  4. Release seats back to AVAILABLE
  5. Transition refund to COMPLETED
  6. Audit log: REFUND_COMPLETED
  7. Send email: refund completed

- Configuration properties:
  ```properties
  fairtix.refund.auto-approve-threshold=50.00
  fairtix.refund.time-window-days=14
  fairtix.refund.enabled=true
  ```

**6. RefundController** — New file `refunds/api/RefundController.java`
- `POST /api/orders/{orderId}/refunds` — Authenticated user requests refund
- `GET /api/refunds` — List user's refund requests
- `GET /api/refunds/{refundId}` — Get refund details
- `POST /api/admin/refunds/{refundId}/review` — Admin approves/rejects
- `GET /api/admin/refunds` — Admin lists all refund requests (with status filter)

**7. Frontend — User Refund UI**
- [`TicketCard.js`](frontend/webpages/src/components/TicketCard.js): Add "Request Refund" button for VALID tickets
- New `RefundDialog.js` component: reason text field, eligibility check display, submit
- New `MyRefunds.js` page (or section in Dashboard): list refund requests with status badges
- Add route `/refunds` in [`App.js`](frontend/webpages/src/App.js)

**8. Frontend — Admin Refund UI**
- New `AdminRefundsPage.js` in admin pages: table of refund requests with filters (Pending/Approved/Rejected/All)
- Review dialog: approve/reject with admin notes field
- Add to admin sidebar navigation

**9. Email Templates** — [`EmailTemplateService.java`](backend/src/main/java/com/fairtix/notifications/application/EmailTemplateService.java)
- `buildRefundRequestedEmail(userName, orderId, amount, reason)` — confirmation of request submission
- `buildRefundCompletedEmail(userName, orderId, amount)` — refund processed notification
- `buildRefundRejectedEmail(userName, orderId, reason, adminNotes)` — rejection notification

**10. Event Cancellation Integration** (ties to #99)
- When an event is cancelled via `EventService.cancelEvent()`:
  - Auto-create refund requests for all COMPLETED orders with status APPROVED
  - Auto-process those refunds (skip manual review)
  - Send batch cancellation/refund emails

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Race condition: concurrent refund + new purchase for same order | Use optimistic locking on Order entity (`@Version`); refund validates order status atomically |
| Partial refunds complexity | V1: full refunds only. Partial refunds deferred to M3 |
| Refund for already-used tickets | Validate ticket status — reject refund if any ticket is USED |
| Event cancellation mass refund overwhelms system | Process cancellation refunds asynchronously via batch; log progress |
| No real payment processor to reverse charges | Simulated refund (matching simulated payment); record refund PaymentRecord for audit trail |
| Refund window edge cases (timezone, event start) | Compare against event startTime in UTC; refund window = `event.startTime - configuredDays` |
| Admin accidentally approves wrong refund | Confirmation dialog with order details; audit log tracks reviewer |

---

## #100: Support Ticket System

### Current State

No support ticket infrastructure exists. The codebase has:
- Email service ready ([`SmtpEmailService.java`](backend/src/main/java/com/fairtix/notifications/infrastructure/SmtpEmailService.java))
- Audit logging ([`AuditService.java`](backend/src/main/java/com/fairtix/audit/application/AuditService.java))
- Admin user management ([`AdminController.java`](backend/src/main/java/com/fairtix/users/api/AdminController.java))
- Modular backend structure ready for a new `support` module
- [`PrivacyPolicy.js`](frontend/webpages/src/pages/PrivacyPolicy.js) mentions contacting the FairTix team but provides no mechanism

### What Needs to Be Built

**1. DB Migration — V18**
```sql
CREATE TABLE support_tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    subject         VARCHAR(200) NOT NULL,
    category        VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    order_id        UUID REFERENCES orders(id),
    event_id        UUID REFERENCES events(id),
    assigned_to     UUID REFERENCES users(id),
    closed_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ticket_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    author_id       UUID NOT NULL REFERENCES users(id),
    message         TEXT NOT NULL,
    is_staff        BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_user ON support_tickets(user_id);
CREATE INDEX idx_support_status ON support_tickets(status);
CREATE INDEX idx_support_assigned ON support_tickets(assigned_to);
CREATE INDEX idx_ticket_msg_ticket ON ticket_messages(ticket_id);
```

**2. Domain Models** — New module `support/`
- `SupportTicket.java` — entity with fields above
- `TicketMessage.java` — entity for threaded messages
- `TicketStatus` enum: `OPEN`, `IN_PROGRESS`, `WAITING_ON_USER`, `RESOLVED`, `CLOSED`
- `TicketCategory` enum: `ORDER_ISSUE`, `REFUND`, `ACCOUNT`, `EVENT`, `TECHNICAL`, `OTHER`
- `TicketPriority` enum: `LOW`, `NORMAL`, `HIGH`, `URGENT`

**3. SupportTicketService** — `support/application/SupportTicketService.java`
- `createTicket(userId, subject, category, message, orderId?, eventId?)` — creates ticket + initial message
- `addMessage(ticketId, userId, message)` — adds reply, updates ticket timestamp
- `getUserTickets(userId, page)` — paginated list for the user
- `assignTicket(ticketId, adminId, assigneeId)` — admin assigns to staff
- `updateStatus(ticketId, adminId, newStatus)` — admin changes status
- `getAdminTickets(statusFilter, page)` — admin queue view
- `closeTicket(ticketId, userId)` — either party can close

**4. SupportTicketController** — `support/api/SupportTicketController.java`
- `POST /api/support/tickets` — Create ticket (authenticated)
- `GET /api/support/tickets` — List user's tickets
- `GET /api/support/tickets/{id}` — Get ticket with messages
- `POST /api/support/tickets/{id}/messages` — Add message
- `POST /api/support/tickets/{id}/close` — Close ticket
- `GET /api/admin/support/tickets` — Admin queue (with status/priority filters)
- `PATCH /api/admin/support/tickets/{id}` — Admin update status/priority/assignment

**5. Email Notifications**
- Ticket created confirmation to user
- New message notification (to user when staff replies, to assigned admin when user replies)
- Ticket resolved/closed notification

**6. Frontend — User Support UI**
- New `SupportPage.js`: create ticket form with subject, category dropdown, message textarea, optional order/event reference
- New `SupportTicketDetail.js`: threaded message view with reply box, status badge, close button
- New `MySupportTickets.js`: list of user's tickets with status filters
- Add "Help & Support" link in [`Navbar.js`](frontend/webpages/src/components/Navbar.js) and Dashboard
- Routes: `/support`, `/support/new`, `/support/tickets/:id`

**7. Frontend — Admin Support UI**
- New `AdminSupportPage.js`: queue table with filters (status, priority, category), search, assignment
- Ticket detail view with admin actions (assign, change priority/status, reply)
- Add "Support" section to admin sidebar in [`AdminSidebar.js`](frontend/webpages/src/admin/components/AdminSidebar.js)

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Spam ticket creation | Rate limit: max 5 tickets per user per hour; reCAPTCHA already implemented on the platform |
| XSS in ticket messages | Sanitize all user input server-side; render messages as plain text, not HTML |
| Ticket notification spam | Batch notifications; don't notify on own messages; respect notification preferences |
| No real-time updates | V1: polling or refresh. WebSocket support deferred to M3 |
| Orphaned tickets (no staff assigned) | Default unassigned tickets appear at top of admin queue; add "unassigned" filter |
| Privacy: admin sees user data in tickets | Only expose necessary user info (name, email); audit all admin access to ticket data |

---

## #102: Graphical Seat Map UI

### Current State

Seats are displayed in a **table-based layout** in [`EventDetail.js`](frontend/webpages/src/pages/EventDetail.js) — grouped by section/row with status badges, interactive row selection, and a sticky selection bar. Admin seat management in [`AdminSeatsPage.js`](frontend/webpages/src/admin/pages/AdminSeatsPage.js) uses tables with status chips.

The [`Seat.java`](backend/src/main/java/com/fairtix/inventory/domain/Seat.java) entity has section, rowLabel, seatNumber, price, status — but **no positional/coordinate data**. The [`Venue.java`](backend/src/main/java/com/fairtix/venues/domain/Venue.java) entity has name, address, city, country, capacity — but **no section geometry or layout configuration**.

No canvas/SVG libraries exist in `package.json`. No seat map components exist. No third-party seat selection libraries are installed.

### What Needs to Be Built

**1. DB Migration — V19**
```sql
-- Add coordinate data to seats for visual positioning
ALTER TABLE seats ADD COLUMN pos_x DOUBLE PRECISION;
ALTER TABLE seats ADD COLUMN pos_y DOUBLE PRECISION;
ALTER TABLE seats ADD COLUMN rotation DOUBLE PRECISION DEFAULT 0;

-- Add section geometry to venues for layout rendering
CREATE TABLE venue_sections (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id        UUID NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    section_type    VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    path_data       TEXT,
    pos_x           DOUBLE PRECISION NOT NULL DEFAULT 0,
    pos_y           DOUBLE PRECISION NOT NULL DEFAULT 0,
    width           DOUBLE PRECISION NOT NULL DEFAULT 100,
    height          DOUBLE PRECISION NOT NULL DEFAULT 100,
    color           VARCHAR(7) DEFAULT '#E0E0E0',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_sections_venue ON venue_sections(venue_id);
```

**2. Backend — Venue Section Model** — New file `venues/domain/VenueSection.java`
- Entity with: id, venueId, name, sectionType (STANDARD, VIP, ACCESSIBLE, STAGE), pathData (SVG path), posX, posY, width, height, color, sortOrder

**3. Backend — Venue Section API**
- `POST /api/admin/venues/{venueId}/sections` — Create section (ADMIN)
- `GET /api/venues/{venueId}/sections` — List sections (public)
- `PUT /api/admin/venues/{venueId}/sections/{id}` — Update section
- `DELETE /api/admin/venues/{venueId}/sections/{id}` — Delete section

**4. Backend — Seat Coordinate Extensions**
- Add `posX`, `posY`, `rotation` to [`Seat.java`](backend/src/main/java/com/fairtix/inventory/domain/Seat.java) entity
- Add fields to `SeatResponse` DTO
- Add `GET /api/events/{eventId}/seats/map` endpoint — returns seats with coordinates, optimized for rendering (minimal fields)

**5. Frontend — Add Konva.js** (or equivalent)
- Install `react-konva` and `konva` for canvas-based rendering
- Alternative: pure SVG approach (no new dependency, lighter weight, but less performant for large venues)
- **Recommendation:** Start with SVG for simplicity; migrate to Konva if performance is an issue with 1000+ seats

**6. Frontend — SeatMap Component** — New file `components/SeatMap.js`
- SVG-based venue rendering:
  - Render venue sections as colored regions
  - Render individual seats as circles/squares positioned by coordinates
  - Color-code by status: green (AVAILABLE), orange (HELD), red (SOLD), blue (selected)
  - Hover tooltip: section, row, seat number, price
  - Click to select/deselect seats
  - Zoom and pan controls for large venues
  - Legend showing color meanings

**7. Frontend — SeatMap Integration in EventDetail**
- [`EventDetail.js`](frontend/webpages/src/pages/EventDetail.js): Add toggle between "Map View" and "List View"
- Map view uses SeatMap component; list view retains existing table
- Selected seats sync between both views
- Sticky selection bar works with both views

**8. Frontend — Admin Seat Map Editor** — New file `admin/components/SeatMapEditor.js`
- Visual editor for placing seats on a venue layout
- Drag-and-drop seat positioning
- Section boundary editor
- Bulk seat creation: "Add row of N seats" with auto-positioning
- Preview mode showing how customers will see the map

**9. Frontend — Admin Venue Layout Configuration**
- Extend [`AdminVenuesPage.js`](frontend/webpages/src/admin/pages/AdminVenuesPage.js) with "Configure Layout" action per venue
- Section management: add/edit/remove sections with visual preview
- Seat coordinate assignment (manual placement or grid auto-layout)

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Performance: rendering 5000+ seats on canvas | Use SVG with virtualization (only render visible viewport); group by section; lazy-load seat details on zoom |
| No coordinate data for existing seats | Provide "Auto-Layout" tool that arranges seats in a grid pattern based on section/row/seatNumber; manual adjustment optional |
| Accessibility: canvas-based maps are not screen-reader friendly | Maintain list view as default; map view is enhancement; ensure keyboard navigation works in list view |
| Complex admin editor scope creep | V1: simple grid auto-layout only. Drag-and-drop editor in M3 |
| Mobile: touch interactions on small screens | Pinch-to-zoom, responsive breakpoints; fallback to list view on small screens |
| New dependency (Konva) | Start with pure SVG (no new dep); only add Konva if SVG performance is insufficient |
| Coordinate system inconsistency across venues | Normalize to percentage-based coordinates (0-100) so maps scale to any container size |

### Phased Approach (Recommended)

**Phase 1 (M2):** SVG-based read-only seat map with color-coded status, hover tooltips, click-to-select. Auto-layout existing seats from section/row/number. No admin editor yet — coordinates auto-generated.

**Phase 2 (M3):** Admin layout editor with drag-and-drop, section boundary drawing, custom seat placement. Konva migration if performance demands it.

---

## #103: Frontend Test Coverage Expansion

### Current State

**Test infrastructure** is properly configured:
- Jest via `react-scripts`, React Testing Library, `@testing-library/user-event`
- [`setupTests.js`](frontend/webpages/src/setupTests.js) imports jest-dom matchers

**Current coverage: 3 test files for 53 source files (5.7%)**
- [`App.test.js`](frontend/webpages/src/App.test.js) — smoke test (1 test)
- [`Login.test.js`](frontend/webpages/src/pages/Login.test.js) — 6 tests (error handling, lockout)
- [`Signup.test.js`](frontend/webpages/src/pages/Signup.test.js) — 5 tests (validation, errors)

**Existing test patterns** are solid: MemoryRouter wrapping, AuthContext mocking, async/await with waitFor, helper functions for common setup.

**CI pipeline** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs backend tests only — **no frontend tests in CI**.

### What Needs to Be Built

**1. CI Pipeline Update** — [`.github/workflows/ci.yml`](.github/workflows/ci.yml)
- Add frontend test job:
  ```yaml
  frontend-test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend/webpages
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/webpages/package-lock.json
      - run: npm ci
      - run: npm test -- --coverage --watchAll=false
      - name: Check coverage thresholds
        run: npx jest --coverage --coverageThreshold='{"global":{"branches":50,"functions":50,"lines":60,"statements":60}}'
  ```

**2. Coverage Configuration** — `frontend/webpages/package.json`
- Add Jest coverage config:
  ```json
  "jest": {
    "collectCoverageFrom": [
      "src/**/*.{js,jsx}",
      "!src/index.js",
      "!src/reportWebVitals.js",
      "!src/setupTests.js"
    ],
    "coverageThresholds": {
      "global": {
        "branches": 50,
        "functions": 50,
        "lines": 60,
        "statements": 60
      }
    }
  }
  ```

**3. Test Utilities** — New file `src/test-utils.js`
- Shared test helpers: `renderWithProviders(component)` wrapping MemoryRouter + AuthContext + ThemeProvider
- Mock factories: `mockUser()`, `mockEvent()`, `mockTicket()`, `mockOrder()`
- Common API mock setup for `fetch`

**4. Priority Test Files** (ordered by criticality)

| Priority | File to Test | Why Critical | Estimated Tests |
|----------|-------------|-------------|-----------------|
| P0 | `auth/AuthContext.js` | All auth flows depend on this | 8-10 |
| P0 | `components/ProtectedRoute.js` | Guards all authenticated pages | 3-4 |
| P0 | `components/AdminRoute.js` | Guards all admin pages | 3-4 |
| P0 | `api/client.js` | All API calls flow through this | 6-8 |
| P1 | `pages/Checkout.js` | Money flow — most critical user path | 8-10 |
| P1 | `pages/EventDetail.js` | Seat selection — core feature | 8-10 |
| P1 | `pages/MyTickets.js` | Ticket display + transfer actions | 5-6 |
| P1 | `pages/MyHolds.js` | Hold management + countdown logic | 5-6 |
| P1 | `components/TicketCard.js` | Shared component used across pages | 4-5 |
| P2 | `pages/Dashboard.js` | User profile + settings | 4-5 |
| P2 | `pages/Events.js` | Event browsing + search | 4-5 |
| P2 | `pages/ForgotPassword.js` | Password reset flow | 3-4 |
| P2 | `pages/ResetPassword.js` | Password reset completion | 3-4 |
| P2 | `pages/VerifyEmail.js` | Email verification flow | 3-4 |
| P2 | `components/Navbar.js` | Navigation + auth state display | 4-5 |
| P2 | `components/TransferDialog.js` | Ticket transfer modal | 4-5 |
| P3 | `admin/pages/AdminDashboard.js` | Admin overview | 3-4 |
| P3 | `admin/pages/AdminEventsPage.js` | Event CRUD UI | 5-6 |
| P3 | `admin/pages/AdminUsersPage.js` | User management UI | 4-5 |
| P3 | `admin/pages/AdminSeatsPage.js` | Seat management UI | 4-5 |
| P3 | `admin/pages/AdminVenuesPage.js` | Venue management UI | 4-5 |

**Target: ~100-120 new tests across 20+ files, reaching 60%+ line coverage**

**5. Test Pattern Guidelines**
- Mock `fetch` globally in test setup, not per test file
- Use `renderWithProviders` helper for consistent component wrapping
- Test user interactions (click, type, submit) not implementation details
- Test error states and loading states, not just happy path
- Test route guards (redirect when not authenticated, redirect when not admin)
- For admin pages: mock API responses, verify table rendering, test CRUD dialogs

### Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Tests slow down CI significantly | Run tests in parallel; only run on frontend file changes (path filter in CI) |
| Flaky async tests | Use `waitFor` consistently; avoid `setTimeout` in tests; use `findBy` queries |
| Test maintenance burden | Keep tests behavior-focused, not implementation-focused; avoid snapshot tests |
| Coverage threshold blocks PRs | Start with lower thresholds (50/60%), ratchet up incrementally |
| Tests for features built in this milestone (#98-#102) | Write tests for new components as each issue is implemented; #103 covers existing gap |

---

## Cross-Cutting Concerns

### Migration Ordering
| Migration | Issue | Description |
|-----------|-------|-------------|
| V16 | #99 | Event status + lifecycle timestamps |
| V17 | #98 | Refund requests table |
| V18 | #100 | Support tickets + messages tables |
| V19 | #102 | Seat coordinates + venue sections |

### Shared Dependencies
- **Email templates** (#101) are used by #98 (refund emails) and #100 (ticket notifications) — implement #101 first
- **Event status** (#99) is checked by #98 (refund eligibility depends on event state) — implement #99 first
- **Audit logging** already exists and is used by all new services
- **Notification preferences** already exist; extend with `emailSupport` flag for #100

### API Contract Updates
All new endpoints need to be added to [`docs/api-contract.md`](docs/api-contract.md).

### New Frontend Routes Summary
| Route | Component | Issue |
|-------|-----------|-------|
| `/support` | MySupportTickets.js | #100 |
| `/support/new` | SupportPage.js | #100 |
| `/support/tickets/:id` | SupportTicketDetail.js | #100 |
| `/refunds` | MyRefunds.js | #98 |
| `/admin/support` | AdminSupportPage.js | #100 |
| `/admin/refunds` | AdminRefundsPage.js | #98 |

### New Backend Modules
| Module | Package | Issue |
|--------|---------|-------|
| Refunds | `com.fairtix.refunds` | #98 |
| Support | `com.fairtix.support` | #100 |

---

## Verification Plan

| Issue | How to Verify |
|-------|--------------|
| #99 | Create event (defaults to DRAFT), publish, activate, complete, cancel with reason, archive. Verify public API only shows PUBLISHED/ACTIVE. Verify cancel cascades to holds/tickets. |
| #101 | Complete a purchase → verify confirmation email in MailHog. Let a hold expire → verify expiry email. Toggle notification preferences off → verify no email sent. |
| #98 | Complete a purchase → request refund → verify admin sees it → approve → verify order/tickets/seats updated. Test auto-refund on event cancellation. |
| #100 | Create support ticket → add messages → admin replies → close. Verify email notifications at each step. Test rate limiting. |
| #102 | Navigate to event detail → toggle map view → verify seats render with correct colors → click to select → verify selection bar updates. Test zoom/pan. |
| #103 | Run `npm test -- --coverage --watchAll=false` → verify 60%+ coverage. Verify CI pipeline runs frontend tests on PR. |
