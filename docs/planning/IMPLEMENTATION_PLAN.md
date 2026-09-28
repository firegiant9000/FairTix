# Implementation Plan: Issues #49, #66, #67, #71, #74

> Branch: `feature/implement-issues-49-66-67-71-74`
> Base: `feature/implement-issues-61-64-65-69-73`
> Date: 2026-04-08

---

## Table of Contents

1. [Issue #49 - HTTP-Only Cookies](#issue-49---http-only-cookies)
2. [Issue #66 - Event Detail Page / Seat Listing](#issue-66---event-detail-page--seat-listing)
3. [Issue #67 - Webpages / Layout Integration](#issue-67---webpages--layout-integration)
4. [Issue #71 - My Tickets Frontend Page](#issue-71---my-tickets-frontend-page)
5. [Issue #74 - Notification / Privacy Considerations](#issue-74---notification--privacy-considerations)
6. [Cross-Issue Conflicts & Dependencies](#cross-issue-conflicts--dependencies)
7. [Suggested Implementation Order](#suggested-implementation-order)

---

## Issue #49 - HTTP-Only Cookies

**Goal:** Replace client-side JWT storage (`sessionStorage`) with HTTP-only cookies to eliminate XSS token theft.

### Current State

| Layer | How it works today |
|-------|-------------------|
| **Backend** `AuthController.java` | `/auth/login` and `/auth/register` return `{ "token": "<jwt>" }` in the JSON body |
| **Backend** `JwtAuthenticationFilter.java` | Reads token from `Authorization: Bearer <token>` header |
| **Backend** `SecurityConfig.java` | CSRF disabled, session policy `STATELESS`, CORS `allowCredentials(true)` |
| **Frontend** `tokenUtils.js` | Stores JWT in `sessionStorage`, decodes it client-side with `jwt-decode` to extract `userId`, `email`, `role` |
| **Frontend** `client.js` | Manually attaches `Authorization: Bearer` header to every fetch call |
| **Frontend** `AuthContext.js` | Hydrates user state by decoding the JWT from `sessionStorage` on mount; auto-logout timer based on decoded `exp` claim |

### Implementation Plan

#### Backend Changes

**1. Set HTTP-only cookie on login/register responses**
- File: `AuthController.java`
- After generating the JWT, attach it as a `Set-Cookie` header instead of (or in addition to) the JSON body.
- Cookie attributes:
  - `HttpOnly` - prevents JavaScript access (the whole point)
  - `Secure` - only sent over HTTPS (skip in dev with a config flag)
  - `SameSite=Lax` - blocks cross-origin cookie sending (CSRF mitigation)
  - `Path=/` - available to all endpoints
  - `Max-Age=900` - matches the 15-minute JWT expiry
- Use Spring's `ResponseCookie` builder via `HttpServletResponse.addHeader("Set-Cookie", ...)`.

**2. Create a `/auth/me` endpoint**
- File: new method in `AuthController.java`
- Returns `{ userId, email, role }` for the currently authenticated user.
- Why: the frontend can no longer decode the JWT client-side (it's HttpOnly), so it needs a way to know who is logged in.

**3. Create a `/auth/logout` endpoint**
- File: new method in `AuthController.java`
- Clears the auth cookie by setting `Max-Age=0`.
- Why: frontend can't clear an HttpOnly cookie via JavaScript.

**4. Update `JwtAuthenticationFilter.java` to read from cookies**
- Try cookie named `fairtix_token` first, then fall back to `Authorization` header.
- This lets both cookie-based (browser) and header-based (Swagger, API clients) auth work simultaneously.

**5. Enable CSRF protection (or validate it's unnecessary)**
- With `SameSite=Lax` cookies, cross-origin `POST`/`PUT`/`DELETE` requests won't include the cookie, which handles most CSRF vectors.
- **Option A (recommended):** Keep CSRF disabled but enforce `SameSite=Lax`. Simpler, sufficient for this app.
- **Option B:** Enable Spring's `CsrfTokenRepository` with a cookie-based double-submit pattern. More secure but adds frontend complexity.
- Document the chosen approach.

**6. CORS configuration update**
- File: `SecurityConfig.java`
- `allowCredentials(true)` is already set. No change needed unless origins change.

#### Frontend Changes

**7. Remove manual token handling from `client.js`**
- Remove the `Authorization: Bearer` header logic.
- Add `credentials: 'include'` to all `fetch()` calls so the browser sends the cookie automatically.

**8. Rewrite `tokenUtils.js`**
- Remove `saveToken`, `getToken`, `removeToken`, `decodeToken`, `isTokenExpired`, `getAuthPayload`.
- Replace with a single `fetchCurrentUser()` function that calls `GET /auth/me`.

**9. Rewrite `AuthContext.js`**
- On mount: call `/auth/me` to hydrate user state (replaces decoding JWT from sessionStorage).
- `login()`: call `/auth/login`, then `/auth/me` to get user info.
- `signup()`: call `/auth/register`, then `/auth/me` to get user info.
- `logout()`: call `/auth/logout` (server clears cookie), then clear local state.
- Remove the `jwt-decode` dependency from `package.json`.
- Session expiry: instead of a client-side timer based on `exp`, rely on 401 responses from `/auth/me` or any API call. On 401, trigger the session-expired banner and redirect.

**10. Update `ProtectedRoute.js` and `AdminRoute.js`**
- These currently check `user` from context, which should still work if `AuthContext` hydrates correctly.
- Add handling for the loading state while `/auth/me` is in-flight on page refresh.

### Potential Issues & Mitigations

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Can't decode JWT client-side** | Frontend loses access to `userId`, `email`, `role` from the token | `/auth/me` endpoint returns this info; called on mount and after login |
| **CSRF risk with cookies** | Cross-site forms could forge requests | `SameSite=Lax` blocks cross-origin non-GET requests; evaluate if double-submit CSRF token is also needed |
| **Swagger UI breaks** | Swagger can't set cookies easily | Keep `Authorization` header as fallback in `JwtAuthenticationFilter`; Swagger continues using bearer auth |
| **Dev environment (HTTP, not HTTPS)** | `Secure` flag prevents cookie on localhost | Use a config property `app.cookie.secure=false` for dev, `true` for prod |
| **Page refresh latency** | `/auth/me` call on every page load adds latency | Show a brief loading state; cache result in-memory for the session |
| **Cross-port dev setup** | React dev server (port 3000/5173) proxies to backend (port 8080) | Proxy already handles this; cookies set by backend flow through the proxy transparently |
| **Existing tests** | Tests that mock `Authorization` header will need updating | Update integration tests to also test cookie-based auth |

### Files Modified

| File | Change |
|------|--------|
| `AuthController.java` | Add cookie setting, `/auth/me`, `/auth/logout` |
| `JwtAuthenticationFilter.java` | Read from cookie first, then header |
| `SecurityConfig.java` | Possibly enable CSRF, permit `/auth/me` and `/auth/logout` |
| `AuthResponse.java` | May still return token for non-browser clients, or remove it |
| `client.js` | Add `credentials: 'include'`, remove `Authorization` header |
| `tokenUtils.js` | Complete rewrite to `fetchCurrentUser()` |
| `AuthContext.js` | Rewrite hydration logic, remove jwt-decode usage |
| `ProtectedRoute.js` | Handle loading state during `/auth/me` |
| `package.json` | Remove `jwt-decode` dependency |

---

## Issue #66 - Event Detail Page / Seat Listing

**Goal:** Implement event detail page with seat listing.

### Current State

**This page already exists and is functional.** `EventDetail.js` (309 lines) provides:
- Event info header (title, venue, date)
- Seats grouped by section with price ranges
- Availability summary chips (total, available, held, booked)
- Interactive seat selection (click to select, max 10 per hold)
- Hold creation, confirm-and-checkout flow
- Loading skeleton, error states, empty states
- Refresh button for real-time updates

Backend APIs are fully implemented:
- `GET /api/events/{id}` - event details
- `GET /api/events/{eventId}/seats` - seat listing
- `POST /api/events/{eventId}/holds` - create holds

### Remaining Enhancements (if any are wanted)

The following are not implemented and could be in-scope:

**1. Price column missing in seat table**
- The seat table currently renders `Row | Seat | Status` but the price column `<th>Price</th>` exists in the header while the `<td>` for price is missing from the body. Each row renders 3 `<td>` elements but the header has 4 `<th>` elements.
- Fix: add `<td>${seat.price?.toFixed(2) ?? '—'}</td>` to each seat row.

**2. Event description field**
- The `events` database table does not have a `description` column.
- If we want to show a description on the detail page, we'd need a Flyway migration to add the column, plus DTO/controller updates.

**3. Visual seat map**
- Currently seats are displayed as a table. A graphical seat map (grid/theater layout) would improve UX.
- This is a significant UI effort and could be a separate issue.

**4. Seat filtering/sorting**
- Allow users to filter by price range, section, or availability status.

### Recommendation

Mark this issue as largely complete. The price column bug should be fixed. Other enhancements can be filed as separate issues.

### Potential Issues

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Price column mismatch** | Table header/body column count is off | Fix the missing `<td>` for price |
| **No event description** | Detail page only shows title/venue/date | Either add description to DB schema or leave for a future issue |

### Files Modified

| File | Change |
|------|--------|
| `EventDetail.js` | Fix price column in seat table body |
| (Optional) Flyway migration | Add `description` column to `events` table |
| (Optional) `Event.java`, `EventController.java`, DTOs | Support description field |

---

## Issue #67 - Webpages / Layout Integration

**Goal:** Create a consistent layout wrapper for all public-facing pages.

### Current State

- `Layout.js` exists but is **legacy/unused** - contains a simple nav with plain `<button>` elements, no styling.
- `Navbar.js` is the actual nav component, rendered directly in `App.js` above `<Routes>`.
- **No shared layout wrapper** - each page manages its own container, padding, and max-width independently.
- **No footer** exists anywhere in the app.
- `Home.js` uses inline styles and has an empty `Home.css` file.
- The admin section has a proper `AdminLayout` with sidebar, top bar, and content area.
- Pages have inconsistent container widths and spacing.

### Implementation Plan

**1. Create `MainLayout.js` component**
- Wraps all non-admin routes with consistent structure: `<header>` + `<main>` + `<footer>`.
- Uses a max-width content container (e.g., `1200px`) with centered alignment.
- Renders the `Navbar` internally rather than it being a sibling in `App.js`.

**2. Create `Footer.js` component**
- Simple footer with copyright, links (e.g., privacy policy placeholder, about).
- Consistent with the Navbar styling.

**3. Update `App.js` routing structure**
- Remove `<Navbar />` from the top level.
- Wrap public and authenticated routes in `<MainLayout>`:
  ```jsx
  <Route element={<MainLayout />}>
    <Route path="/" element={<Home />} />
    <Route path="/events" element={<Events />} />
    {/* ... */}
  </Route>
  ```
- Admin routes keep their own `<AdminLayout>`.

**4. Delete or repurpose `Layout.js`**
- The legacy `Layout.js` is unused and conflicts with the new `MainLayout`. Remove it.

**5. Standardize page containers**
- Each page currently has its own top-level `<div className="my-page">` with different max-widths.
- With `MainLayout` providing the container, pages can focus on their content without redundant wrapper divs.
- Update page CSS files to remove duplicate container/max-width declarations.

**6. Fix `Home.js` styling**
- Currently uses bare inline styles and has no CSS.
- Create proper CSS classes and a styled hero section.

### Potential Issues

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Navbar conditional hiding for admin** | Currently `Navbar` returns `null` on `/admin` paths | `MainLayout` won't wrap admin routes, so this check becomes unnecessary; remove it from Navbar |
| **Session expired banner placement** | `SessionExpiredBanner` is in `App.js` above the navbar | Move it inside `MainLayout` so it appears within the layout structure |
| **Page-specific container styles break** | Pages that rely on their own max-width may look different | Audit each page's CSS and adjust after adding the layout wrapper |
| **Admin routes must not be affected** | Admin has its own layout system | Admin routes are in a separate `<Route>` group; `MainLayout` only wraps non-admin routes |
| **Navbar state & auth** | `Navbar` uses `useAuth()` which requires `AuthProvider` | `AuthProvider` wraps everything in `App.js`, so `MainLayout` has access |

### Files Modified

| File | Change |
|------|--------|
| New: `components/MainLayout.js` | Shared layout with Navbar + content area + Footer |
| New: `components/Footer.js` | Footer component |
| New: `styles/MainLayout.css` | Layout styles |
| `App.js` | Restructure routes to use `MainLayout`, remove top-level `<Navbar />` |
| `Navbar.js` | Remove admin path check (no longer needed) |
| `Layout.js` | Delete (unused legacy component) |
| `Home.js` + `Home.css` | Add proper styling |
| Various page CSS files | Adjust container styles for consistency |

---

## Issue #71 - My Tickets Frontend Page

**Goal:** Implement the My Tickets page for users to view their purchased tickets.

### Current State

**This page already exists and is functional.** `MyTickets.js` (46 lines) provides:
- Fetches tickets from `GET /api/tickets`
- Renders each ticket via `TicketCard.js` (shows event title, venue, date, section/row/seat, status)
- Loading state, error state, empty state with messaging
- CSS styling in `MyTickets.css`

Backend is fully implemented:
- `GET /api/tickets` - list user's tickets
- `GET /api/tickets/{ticketId}` - get single ticket
- `TicketResponse` DTO includes: `id`, `orderId`, `eventTitle`, `eventVenue`, `eventStartTime`, `seatSection`, `seatRow`, `seatNumber`, `price`, `status`, `issuedAt`

### Remaining Enhancements (if any are wanted)

**1. Loading skeleton**
- Currently shows plain text "Loading tickets...". Other pages like `EventDetail` and `MyHolds` use skeleton loaders.
- Add a skeleton card grid for consistency.

**2. Ticket detail view / expandable card**
- Clicking a ticket currently does nothing.
- Could navigate to `/my-tickets/{ticketId}` or expand the card to show order ID, price, issued date.

**3. Sorting/filtering**
- Sort by event date, purchase date.
- Filter by event or status.

**4. Link to event from ticket**
- `TicketCard` doesn't link back to the event detail page. Adding `<Link to={/events/${ticket.eventId}}>` would be useful.
- Requires `eventId` in the `TicketResponse` DTO (need to verify it's included).

**5. QR code / ticket download**
- Generate a unique QR code per ticket for entry scanning.
- PDF download for printing.
- This is a larger feature and likely a separate issue.

**6. Ticket price display**
- `TicketCard.js` doesn't display the ticket price. The data is available in `TicketResponse`.

### Recommendation

Mark this issue as largely complete. Add loading skeletons, price display, and event links as quick wins. QR codes and downloads are separate issues.

### Potential Issues

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **`eventId` might be missing from TicketResponse** | Can't link back to event | Check DTO; add if missing |
| **No pagination** | Users with many tickets load everything at once | Add server-side pagination if ticket count grows; for now the endpoint returns all |

### Files Modified

| File | Change |
|------|--------|
| `MyTickets.js` | Add loading skeleton |
| `TicketCard.js` | Add price display, event link |
| `MyTickets.css` | Skeleton styles |
| (Optional) `TicketResponse.java` | Ensure `eventId` is included |

---

## Issue #74 - Notification / Privacy Considerations

**Goal:** Design and implement notification preferences and privacy controls.

### Current State

- **No notification infrastructure exists** - no email service, no push notifications, no in-app notification system.
- **No privacy controls** - no consent management, no data export, no notification preferences.
- User data: email, password (hashed), orders, tickets, holds. Soft delete exists for accounts.
- No GDPR/privacy page or terms of service.

### Implementation Plan

This issue is a design/documentation task with some concrete implementation. Breaking it into phases:

#### Phase 1: Notification Preferences Schema & API

**1. Database: `notification_preferences` table**
```sql
CREATE TABLE notification_preferences (
    user_id       UUID PRIMARY KEY REFERENCES users(id),
    email_order   BOOLEAN NOT NULL DEFAULT TRUE,
    email_ticket  BOOLEAN NOT NULL DEFAULT TRUE,
    email_hold    BOOLEAN NOT NULL DEFAULT FALSE,
    email_marketing BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**2. Backend: Notification preferences API**
- `GET /api/users/me/notifications` - get current preferences
- `PUT /api/users/me/notifications` - update preferences
- Auto-create default preferences on user registration.

**3. Frontend: Notification settings in Dashboard**
- Add a "Notification Preferences" section to the existing `Dashboard.js` page.
- Toggle switches for each notification type.
- Save button with optimistic UI.

#### Phase 2: Privacy Controls

**4. Privacy policy page**
- Static page at `/privacy` accessible without login.
- Document what data is collected, how it's used, retention period.
- Add route in `App.js`, link in footer (ties into Issue #67).

**5. Data export endpoint**
- `GET /api/users/me/data-export` - returns a JSON dump of all user data (profile, orders, tickets, holds, preferences).
- GDPR Article 20 compliance (data portability).
- Add "Export My Data" button to Dashboard.

**6. Account deletion enhancement**
- `DELETE /api/users/me` already exists (soft delete).
- Add confirmation dialog in Dashboard before deletion.
- Consider: should soft-deleted data be purged after a retention period? Document the policy.

**7. Consent tracking**
- Record when the user accepted terms / privacy policy.
- Add `accepted_terms_at` and `accepted_privacy_at` columns to `users` table (or separate consent table).
- Show consent prompt on signup.

#### Phase 3: Actual Email Notifications (future)

**8. Email service integration**
- Not in immediate scope but worth noting: Spring Boot Starter Mail + an SMTP provider (SendGrid, SES, etc.).
- Event-driven: publish domain events (OrderCompleted, TicketIssued) and have a listener that sends emails if the user's preferences allow it.
- This is a significant infrastructure addition and should be its own issue.

### Potential Issues

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **No email infrastructure** | Can't actually send notifications yet | Phase 1-2 build the preference/privacy framework; actual sending is Phase 3 |
| **GDPR compliance complexity** | Legal requirements vary by jurisdiction | Start with basic data export + deletion + consent tracking; consult legal for full compliance |
| **Marketing consent must be opt-in** | CAN-SPAM / GDPR requires explicit consent | Default `email_marketing` to `false`; require explicit opt-in |
| **Existing users won't have preferences** | Migration needed for existing users | Flyway migration creates default preferences for all existing users |
| **Data export performance** | Large accounts could have many orders/tickets | Paginate or stream the export; set a rate limit on the endpoint |
| **Soft delete vs hard delete** | Soft-deleted users still have data in the system | Document retention policy; add a scheduled job to purge after N days (optional) |

### Files Modified

| File | Change |
|------|--------|
| New: Flyway migration | `notification_preferences` table, consent columns on `users` |
| New: `NotificationPreference.java` | JPA entity |
| New: `NotificationPreferenceRepository.java` | Spring Data repository |
| New: `NotificationPreferenceService.java` | Business logic |
| New: `NotificationPreferenceController.java` | REST API |
| New: `NotificationPreferenceRequest.java` / `Response.java` | DTOs |
| `AuthService.java` | Create default preferences on registration |
| `Dashboard.js` | Add notification preferences section |
| New: `PrivacyPolicy.js` | Static privacy policy page |
| `App.js` | Add `/privacy` route |
| `UserController.java` | Add data export endpoint |
| `Dashboard.js` | Add "Export My Data" button |

---

## Cross-Issue Conflicts & Dependencies

### 1. Issue #49 (Cookies) affects all other frontend issues

The cookie migration changes how `client.js` sends requests (`credentials: 'include'` instead of `Authorization` header). Every frontend page that uses `api.get()`, `api.post()`, etc. will automatically pick up the change, **but:**

- **AuthContext rewrite** affects `Navbar.js`, `ProtectedRoute.js`, `AdminRoute.js`, and every component that uses `useAuth()`. Test all pages after the cookie migration.
- **`/auth/me` on page refresh** adds a network request before any authenticated page renders. Ensure loading states are handled in `MainLayout` (Issue #67) and individual pages.

**Recommendation:** Implement #49 first since it changes the auth foundation everything else relies on.

### 2. Issue #67 (Layout) affects Issue #71 (MyTickets) and #66 (EventDetail)

The layout wrapper changes how pages are nested. If `MainLayout` provides a content container with max-width, pages that define their own container widths may render differently.

**Recommendation:** Implement #67 second, then verify #66 and #71 look correct within the new layout.

### 3. Issue #74 (Privacy) depends on Issue #67 (Layout)

- The privacy policy page needs a route and a footer link. The footer is created in Issue #67.
- Dashboard notification preferences should use the same layout as other pages.

**Recommendation:** Implement #67 before #74.

### 4. Issues #66 and #71 are mostly complete

Both pages already exist and work. The remaining work is polish (loading skeletons, missing data display, links). These can be done in any order and are low-conflict.

---

## Suggested Implementation Order

| Order | Issue | Effort | Rationale |
|-------|-------|--------|-----------|
| 1 | **#49 - HTTP-Only Cookies** | High | Foundational auth change; do first to avoid rework |
| 2 | **#67 - Layout Integration** | Medium | Sets up layout structure that other pages depend on |
| 3 | **#66 - Event Detail Polish** | Low | Quick fix (price column), verify within new layout |
| 4 | **#71 - My Tickets Polish** | Low | Add skeletons + links, verify within new layout |
| 5 | **#74 - Notification/Privacy** | Medium-High | Depends on layout (footer links), adds new backend module |

### Estimated Scope Per Issue

- **#49:** ~10 files changed across backend + frontend. Requires careful testing of all auth flows.
- **#66:** ~1-2 files. Mostly a table column fix.
- **#67:** ~6-8 files. New components + route restructuring + CSS adjustments.
- **#71:** ~2-3 files. Loading skeleton + minor TicketCard enhancements.
- **#74:** ~10-12 files. New backend module (entity, repo, service, controller, DTOs) + frontend settings UI + privacy page.
