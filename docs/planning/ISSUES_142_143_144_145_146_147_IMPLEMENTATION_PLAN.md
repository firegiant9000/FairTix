# M8 Implementation Plan: Issues #142–#147 + Deployment + Module Integration

**Branch:** `feature/issues-142-143-144-145-146-147-m8-implementation`
**Base:** `feature/issues-134-135-136-138-139-m7-implementation`
**Date:** 2026-04-20

---

## Status

| Issue | Title | Status |
|-------|-------|--------|
| #142 | Calendar export (ICS) frontend button | In progress |
| #143 | Leaflet.js map for nearby events | In progress |
| #144 | Restrict payment simulation to non-prod | In progress |
| #145 | Audit and expand email templates | In progress |
| #146 | Resale policy design doc | In progress |
| #147 | Demo polish and checklist | In progress |
| — | Module integration tests | In progress |
| — | Deployment (Netlify + Railway) | In progress |

---

## What Was Already Done (Not Reimplemented)

| Area | Evidence |
|------|---------|
| ICS backend endpoint | `GET /api/tickets/{ticketId}/calendar.ics` in `TicketController.java` (commit 42d1e343) |
| Nearby events backend | Haversine in `VenueRepository`, `GeoSearchServiceImpl`, `GET /api/events/nearby` |
| Nearby events frontend | `EventsNearYou.js`, `useNearbyEvents.js` with Nominatim geocoding, radius selector |
| Email infrastructure | `SmtpEmailService`, `EmailTemplateService` (14 templates), `NotificationPreference` |
| Payment simulation | `PaymentSimulationService`, dev-profile gating via `application-dev.properties` |
| Transfer/resale backend | `TransferService` with velocity limits + face-value enforcement |

---

## Issue #142 — Calendar Export (ICS) for Tickets

**Scope:** Backend already complete. Add "Add to Calendar" download link on the My Tickets page.

**Files changed:**
- `frontend/webpages/src/pages/MyTickets.js` — add anchor tag to existing ticket cards

**Approach:** Simple `<a href="/api/tickets/{id}/calendar.ics" download>` anchor; browser handles the file save. No JS or fetch needed since the backend sets `Content-Disposition: attachment`.

---

## Issue #143 — Map-Based Nearby Events Experience

**Scope:** Add Leaflet.js map to `EventsNearYou.js` showing event pins. Clicking a pin navigates to event detail.

**Dependencies added:** `leaflet`, `react-leaflet` (approved)

**Files changed:**
- `frontend/webpages/src/pages/EventsNearYou.js` — add `<MapContainer>` with `<Marker>` per event
- `frontend/webpages/package.json` — new deps

**Approach:** Map renders below the existing card list. Uses `Venue.latitude`/`Venue.longitude` returned by `GET /api/events/nearby`. Leaflet tiles from OpenStreetMap (no API key).

---

## Issue #144 — Restrict Payment Simulation to Non-Production Profiles

**Scope:** Formally gate `PaymentSimulationService` behind `@Profile("!prod")` so it cannot be instantiated in the `prod` Spring profile.

**Files changed:**
- `backend/src/main/java/com/fairtix/payments/application/PaymentSimulationService.java` — add `@Profile("!prod")`
- `backend/src/main/java/com/fairtix/payments/api/PaymentController.java` — `@Autowired(required=false)` injection + null guard returning 400
- `backend/src/main/resources/application-prod.properties` — new file with prod defaults

---

## Issue #145 — Audit and Expand Transactional Email Templates

**Scope:** Add two missing triggers — event cancellation notification and queue admission notification.

**Files changed:**
- `backend/src/main/java/com/fairtix/notifications/application/EmailTemplateService.java` — add `buildEventCancelledEmail()`, `buildQueueAdmittedEmail()`
- `backend/src/main/java/com/fairtix/events/application/EventService.java` — wire cancellation email on `cancelEvent()`
- Queue admission scheduler — wire admission email on user admit

---

## Issue #146 — Resale Policy Design Doc

See `docs/RESALE_POLICY.md`.

---

## Issue #147 — Demo Polish and Checklist

See `docs/DEMO_CHECKLIST.md`.

---

## Module Integration Tests

**Scope:** Add integration tests for two previously untested cross-module flows:
1. Event cancellation → ticket holder email sent
2. Stripe-path payment → order created → tickets issued

**Files changed:**
- `backend/src/test/java/com/fairtix/` — new or extended integration test class(es)

---

## Deployment — Netlify + Railway

**Frontend → Netlify:**
- `npm run build` output deployed to Netlify
- `frontend/webpages/public/_redirects` added for SPA routing
- `REACT_APP_API_URL` set to Railway backend URL in Netlify env vars

**Backend → Railway:**
- Railway connects to GitHub repo, builds `backend/Dockerfile`
- Railway-provisioned PostgreSQL + Redis plugins
- `SPRING_PROFILES_ACTIVE=prod` set in Railway env vars
- All secrets from `.env.example` set in Railway dashboard

**CORS:** `app.cors.allowed-origins` in `application-prod.properties` set to Netlify domain.

**Files changed:**
- `backend/src/main/resources/application-prod.properties`
- `frontend/webpages/public/_redirects`
- `DEPLOY.md` — updated with Netlify + Railway steps

---

## Verification

| Check | Method |
|-------|--------|
| ICS download | Click "Add to Calendar" on a ticket, verify `.ics` saves and opens in calendar app |
| Map renders | Visit Events Near You, allow location, confirm map with pins |
| Prod simulation blocked | Run with `SPRING_PROFILES_ACTIVE=prod`, attempt sim checkout → expect 400 |
| Cancellation email | Cancel an event via admin, check MailHog for ticket holder emails |
| Queue admission email | Admit a user from queue, check MailHog |
| Netlify loads | Visit Netlify URL, complete login + checkout flow |
| Railway health | `GET /actuator/health` → `{"status":"UP"}` |
| CORS clean | Network tab shows no CORS errors from Netlify → Railway |
