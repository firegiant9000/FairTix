# Implementation Plan: Issues #109, #110, #111, #116, #117, #118 (M4 Completion)

**Branch:** `feature/issues-109-111-116-118-m4-implementation`
**Updated:** 2026-04-18

---

## Status: All work complete. Ready to merge.

| # | Title | Implementation | Tests |
|---|-------|----------------|-------|
| #109 | Calendar export (iCal) | ✅ | ✅ |
| #110 | QR code / downloadable ticket | ✅ | ✅ |
| #111 | Stripe integration | ✅ | ✅ |
| #116 | Fraud admin dashboard UI | ✅ | ✅ |
| #117 | Admin performer management UI | ✅ | ✅ |
| #118 | City/zip fallback for Near Me | ✅ | ✅ |

---

## Post-issue additions (found during investigation)

| Item | Files | Status |
|------|-------|--------|
| Venue lazy-load fix (iCal N+1 query) | `TicketRepository`, `TicketService`, `TicketController` | ✅ |
| Performer delete (backend + frontend) | `PerformerRepository`, `PerformerService`, `PerformerController`, `AdminPerformersPage.js` | ✅ |
| Stripe webhook handler | `StripeWebhookController`, `SecurityConfig`, `StripeWebhookControllerTest` | ✅ |

---

## Open Questions

None — all resolved.
