# FairTix — Month 2 (Phase 2) Implementation Plan

_Branch: `docs/m2-implementation-plan` · Drafted 2026-05-22_

Phase 2 of the strategic roadmap: **Organizer self-service & box office**
(Weeks 4–8, likely runs to 5 weeks). Goal: a venue can sign up, list an event,
sell tickets at the door and online, settle the show, pay the artist, and
never talk to us.

This document breaks the roadmap's Phase 2 into concrete issues, what each
ticket actually requires, potential problems, dependencies, and a short list
of high-value extras not in the original scope.

---

## Phase entry checklist (carryover from M1)

Land these before opening M2 feature branches — they unblock honest CI on M2
work:

- [ ] Push `feat/m1-phase-1`, run CI, baseline `jacoco.line.minimum`
- [ ] Stand up Railway staging (`docs/runbook-staging.md`)
- [ ] Cookie domain decision (ADR 0001) — required before any new auth-touching code
- [ ] Run V32–V36 against anonymized prod restore
- [ ] Stripe test secret added to GitHub Actions (unblocks refund integration test + Connect work)

If staging slips past the start of Week 4, do **2A + 2C-prep in parallel**
locally; everything else wants staging available for end-to-end testing.

---

## Issue index

| # | Title | Section | Est. (d) | Depends on |
|---|---|---|---|---|
| M2-01 | Org role model, staff sub-roles, ACL middleware | 2A | 4 | M1 V32–V36 |
| M2-02 | `@OrgScoped` annotation + interceptor + controller annotations | 2A | 2 | M2-01 |
| M2-03 | Organizer dashboard shell (routes, layout, nav) | 2B | 2 | M2-01 |
| M2-04 | Dashboard widgets (today, week, refund queue, velocity) | 2B | 3 | M2-03 |
| M2-05 | Per-event organizer view (sold/held/comped, velocity, attendees) | 2B | 4 | M2-03 |
| M2-06 | Stripe Connect Standard onboarding | 2C | 3 | M1 Stripe test key |
| M2-07 | `application_fee_amount` on PaymentIntents + Connect webhooks | 2C | 3 | M2-06 |
| M2-08 | Connect dashboard panel (balance, payouts, status) | 2C | 2 | M2-06 |
| M2-09 | Box office route (tablet UI, walk-up sales) | 2D | 4 | M2-01, M2-07 |
| M2-10 | Stripe Terminal SDK integration (card reader path) | 2D | 3 | M2-09 |
| M2-11 | End-of-night reconciliation flow | 2D | 2 | M2-09 |
| M2-12 | `tickets.kind` enum + comp issuance UI | 2E | 2 | M2-05 |
| M2-13 | Hold lists (artist / press / house) | 2E | 3 | M2-12 |
| M2-14 | Will-call list + PDF batch print | 2E | 2 | M2-12 |
| M2-15 | Day-of-show report | 2F | 3 | M2-07, M2-12 |
| M2-16 | Settlement report + signable PDF/CSV export | 2F | 4 | M2-15 |
| M2-17 | Payout report (Stripe payouts ↔ events mapping) | 2F | 2 | M2-07 |
| M2-18 | Tax helper (1099-K threshold + state sales tax) | 2F | 3 | M2-16 |
| M2-19 | Per-org branding (logo, color, sender, reply-to) | 2G | 2 | M2-01 |
| M2-20 | Per-event page customization (hero, rich text, doors, age, accessibility) | 2G | 3 | M2-05 |
| M2-21 | SEO scaffolding (JSON-LD, OG, semantic slugs) | 2G | 2 | M2-20 |
| M2-22 | Custom domain CNAME support (org subdomain → custom domain) | 2G | 3 | M2-19 |
| M2-23 | Embed widget (`<script src="embed.js" data-org="...">`) | 2G | 2 | M2-20, M2-21 |
| M2-24 | Org signup wizard + admin approval queue | 2H | 3 | M2-06 |
| M2-25 | New-org sales rate limits ($1k/day → $10k/day → unlimited) | 2H | 2 | M2-24 |

Total ballpark: ~67 dev-days. At 15–20 hrs/week solo, ~5 calendar weeks
matches the roadmap's "budget conservatively, runs to 5" estimate. **If
slippage exceeds 1.5×, drop 2G's custom domain + embed widget into M3** — they
are isolated.

---

## Section 2A — Role model & ACL (M2-01, M2-02)

### What needs to happen

- New Flyway migration (next free V-number after M1's V37 — probably V38) with:
  - `staff_role` enum: `OWNER`, `MANAGER`, `BOX_OFFICE`, `DOOR`, `MARKETING`, `ACCOUNTANT`
  - `organization_members.role` column (replace any existing single-role column)
  - `organization_invites` (email, org_id, role, token, expires_at, accepted_at)
  - Permission set table OR hardcoded enum→permissions mapping in Java (recommend Java — fewer moving parts, easier to test, no DB round-trip on every request)
- Permissions enumeration: `events.write`, `events.publish`, `sales.read`,
  `payouts.read`, `payouts.initiate`, `scanner.access`, `boxoffice.access`,
  `comps.issue`, `holds.manage`, `members.invite`, `org.settings.write`,
  `reports.read`, `refunds.approve`
- `@OrgScoped` annotation + Spring interceptor:
  - Resolves `orgId` from `@PathVariable` (default) or `X-Organization-Id` header (for collection endpoints)
  - Looks up the requester's role in that org (cached per request via `MDC` context, reuse M1 correlation infrastructure)
  - Asserts requester has the permission(s) declared on the annotation
  - 403 with structured error body on fail; audit-logs the denial
- Backfill: M1 V32–V36 already created one org per legacy organizer with that user as OWNER. Verify V36's invariant still holds after this migration.

### Potential issues

- **Permission explosion.** Each new feature wants a new permission key.
  Constrain to the enumeration above for M2; reject PRs that add ad-hoc strings.
- **Cross-org leak via `EventService`.** Every method that takes an event ID
  must call `verifyOwnership` or be `@OrgScoped`. There are ~30 such methods.
  Easy to miss one. Mitigation: write a unit test that scans
  `EventController` reflectively and asserts every `@*Mapping` handler is
  `@OrgScoped` or explicitly `@PublicEndpoint`.
- **Header vs path-variable confusion.** Interceptor must pick one source of
  truth per request. Document precedence (path > header) and reject ambiguity.
- **Test fixtures.** Most existing tests use a single ad-hoc user/event. They
  will silently start failing or, worse, pass for the wrong reason. Audit
  `@WithMockUser` setups and migrate to a `WithMockOrgMember` helper.

### Extras (not in original scope) — recommended

- **Role-change audit:** when an OWNER promotes/demotes a member, emit an
  `OrganizationMemberRoleChanged` audit event. Cheap, prevents "who gave
  marketing access to my ex-employee" mysteries. **Build.**
- **Per-event ACL overrides:** door staff for tonight's show but not
  tomorrow's. Roadmap notes this is needed by 3D (scanner), so seed the data
  model now — add `event_staff_assignments (user_id, event_id, role,
  starts_at, ends_at)`. **Build the table, defer UI.**
- **Magic-link staff onboarding:** the scanner phase wants this; the token
  generation lives naturally in `organization_invites`. **Build infra,
  defer UI.**

---

## Section 2B — Organizer dashboard (M2-03 → M2-05)

### What needs to happen

- New route tree: `/organizer/{orgSlug}/{dashboard,events,events/:id,sales,attendees,holds,comps,payouts,settings,team,integrations}`
- Layout component with sidebar + org switcher (a user can belong to multiple orgs)
- Dashboard widgets (server endpoints + UI):
  - Today's shows (next 24h, scan progress if event has started)
  - Week revenue (gross, net of fees, comparison vs prior week)
  - Refund queue depth (count pending, oldest age)
  - Recently sold (last 20 orders)
  - Top events by velocity (sales/hour, 7-day)
  - Upcoming hold-release reminders
- Per-event view:
  - Inventory breakdown (sold / held / available / comped) — single SQL aggregate, not 4 queries
  - Sales velocity chart (line chart, sales/hour, last 14 days or since `salesStartAt`)
  - Attendee list with search, export CSV
  - Scan progress (live SSE during event — reuse M3 scanner endpoint when it lands; placeholder div until then)
  - Revenue / fees / payout estimate

### Potential issues

- **Live DB query load.** The roadmap explicitly calls out that analytics
  queries hit prod DB. The same trap applies here. Mitigation:
  - Materialize `event_inventory_stats` view (refreshed on order/refund/hold change)
  - Cache dashboard widgets for 30s per org
  - Add the missing indexes the roadmap flagged (`tickets.user_id`,
    `tickets.event_id`, `orders.organization_id`, `seat_holds.user_id`)
- **Org switcher state.** Persisting "current org" client-side leaks across
  tabs and confuses interceptors. Recommend: encode in URL slug, never in
  localStorage; interceptor reads from URL or `X-Organization-Id` header.
- **Slug collisions.** "Blue Note" exists in 3 cities. Slug must be
  globally unique (`/o/blue-note-nyc` vs `/o/blue-note-tokyo`) — auto-append
  short city/state suffix on collision.
- **N+1 on attendee list.** With seat + order + user + ticket joins it's easy
  to fall into. Use a single DTO projection query.

### Extras — recommended

- **CSV-everywhere:** every list view gets an "Export CSV" button. Accountants
  live in Excel; this single feature drives organizer satisfaction more than
  most. **Build for attendees + sales + payouts.**
- **Saved filters / views:** "show me unscanned VIP attendees for tonight."
  **Defer to M3** unless free time.
- **Dashboard email digest (weekly):** Monday-morning summary of last week's
  sales per org. ~3h of work, big retention lever. **Build if 2A–2C finish on time.**
- **Real-time WebSocket for org dashboard:** SSE is already in the codebase
  for queues — reuse for "new ticket sold" toast. ~half a day. **Build.**

---

## Section 2C — Stripe Connect (M2-06 → M2-08)

### What needs to happen

- Stripe Connect Standard accounts (Stripe-hosted onboarding flow)
  - `POST /api/organizer/connect/onboard` returns the Stripe-hosted onboarding URL
  - Return URL: `/organizer/{slug}/settings/payments?status=connected`
  - Refresh URL: same with `?status=refresh`
- `application_fee_amount` on every PaymentIntent:
  - Pulled from `organizations.plan` → platform fee bps lookup
  - Free: 250 bps, Pro: 150 bps, Scale: 100 bps (cents-precise, not float)
  - `on_behalf_of: connectedAccountId`, `transfer_data.destination: connectedAccountId`
- Webhook handlers:
  - `account.updated` — sync charges_enabled / payouts_enabled / requirements
  - `account.application.deauthorized` — disable the org from creating new events; preserve historical data
  - `payout.paid` / `payout.failed` — surface in dashboard, email on failure
  - `charge.dispute.created` — page on Slack, freeze that organizer's payouts above $X until reviewed
- Reverse fees on refund: M1 wired refunds; add `reverse_transfer: true` and `refund_application_fee: true` to the refund call. **This is a one-line change but make sure to write a test against Stripe test mode.**
- Connect dashboard panel: account status, pending balance, next payout date, payout history table

### Potential issues

- **Onboarding incompleteness.** Stripe Connect Standard requires the
  organizer to finish KYC. Until they do, `charges_enabled` is false. The
  event publish flow MUST block publishing if `charges_enabled=false`, with a
  clear "finish your Stripe setup" CTA. Easy to miss.
- **Multi-currency edge case.** Standard accounts settle in the connected
  account's currency. If the venue is in CA but our platform fee is in USD,
  Stripe handles conversion but fees feel weird in reports. **Hard-block
  non-US accounts in M2** — defer multi-currency per roadmap Section 5.
- **Application fee on refund.** If a partial refund happens and the app fee
  isn't proportionally reversed, the organizer is short. Stripe handles this
  automatically when both flags are set, but it's silently catastrophic if
  flags are off. **Write a partial-refund integration test before relying on this.**
- **Connected account deauthorization.** Don't delete historical event/order
  data; just block new charges. Soft-disable, not hard-delete.
- **Webhook signature verification.** Connect webhooks come from a separate
  signing secret than direct webhooks. Two distinct `Stripe-Signature` checks
  in the codebase. Document which is which.
- **Test-mode Connect accounts** behave subtly differently than live. Plan a
  staging-vs-live smoke matrix before onboarding the first real venue.

### Extras — recommended

- **Stripe Connect Express (instead of Standard):** lower-friction onboarding,
  Stripe handles more of the dashboard. Tradeoff: organizer can't log into
  their Stripe account directly. **Roadmap says Standard. Keep Standard for
  M2; revisit Express in M7 only if onboarding completion rate is <60%.**
- **Manual payout trigger:** organizer presses "pay out now" instead of
  waiting for the daily Stripe schedule. Useful for end-of-show cash flow.
  ~half-day. **Build.**
- **Payout-failure auto-retry with notification:** Stripe retries
  automatically but failure visibility matters. **Build a Slack/email alert,
  ride on the ops-alert webhook from M1-deferred extras.**
- **Statement descriptor per org:** customers see "FAIRTIX*BLUENOTE" instead
  of "FAIRTIX". Set `statement_descriptor_suffix` on PaymentIntent. One
  field, big trust win. **Build.**

---

## Section 2D — Box office mode (M2-09 → M2-11)

### What needs to happen

- New route `/box-office` (or `/o/{slug}/box-office`), tablet-first responsive
  - Required: `BOX_OFFICE` or `OWNER` role
  - Big tap targets, optimized for landscape iPad / Surface
- Walk-up cash sale flow:
  - Pick event from today's shows
  - Pick seat (reuse seat picker, scoped to BO mode — no holds, immediate purchase)
  - "Cash" or "Card" or "Comp"
  - Optional email/SMS for receipt; if blank, ticket prints to attached printer or shows QR on screen
- Stripe Terminal SDK:
  - Register Stripe Terminal location per venue
  - WisePOS E reader recommended (Stripe-sold, no certification hassle)
  - Pair reader once during venue onboarding; persist `terminal_reader_id` on the venue or BO session
  - `PaymentIntent` with `payment_method_types: ['card_present']` and capture on reader confirmation
- End-of-night reconciliation:
  - Start-of-shift cash drawer count input
  - Sales totals (cash / card / comp / refund) by session
  - End-of-shift count → expected vs actual variance
  - Manager (OWNER or MANAGER) sign-off with audit entry
  - Export PDF summary, attach to settlement

### Potential issues

- **Stripe Terminal cert and shipping lead time.** Readers are real hardware.
  Order one for development now; takes 1–2 weeks. **Block this on issue
  creation, not implementation week.**
- **Offline degradation.** Box office must work if internet drops at the door
  for a few minutes. Queue cash sales locally, sync when reconnected. Card
  sales without internet fail hard (Stripe Terminal requires connectivity);
  surface clearly in UI.
- **Receipt printing.** Browser → thermal printer is fiddly. For M2, "show
  QR on screen, optional email" is enough. Defer USB thermal printer
  integration to M3.
- **Concurrent box-office and online sales.** Two staff at the door sell the
  same GA seat. Existing seat-hold infra handles this for online; BO must use
  the same hold path with a short TTL (10s) instead of bypassing it. **Do
  not let BO write tickets without going through `SeatHoldService` — even at
  the door.**
- **Cash drawer UX.** Manager sign-off forces a real person to look at
  variance. Variance >$5 should require a written reason. Cheap fraud
  control.

### Extras — recommended

- **Quick-comp button:** "comp 2 to artist's guest" without leaving the
  events screen. **Build.**
- **Door cash float record:** track which staff opened the drawer with how
  much, audit trail for shortages. **Build.**
- **Tip line on cash sales:** "round up to support the venue." Tiny add,
  fits the tip-the-venue model from Phase 5. **Defer to Phase 5 unless
  trivial.**
- **Multi-event tablet mode:** comedy clubs run 3 shows a night. BO must
  show all today's shows side-by-side, not a single event. **Build into M2-09.**

---

## Section 2E — Comps, holds, will-call (M2-12 → M2-14)

### What needs to happen

- Flyway migration:
  - `ticket_kind` enum: `PAID`, `COMP`, `HOLD_ARTIST`, `HOLD_PRESS`, `HOLD_HOUSE`
  - `tickets.kind` column (default PAID, backfill all existing)
  - `tickets.kind_reason` text (nullable, free-text for "why comped")
  - `tickets.kind_issued_by` user id
- Comp issuance UI:
  - From per-event view → "Issue comp"
  - Pick seats from the seat map
  - Recipient name + email (recipient may not be a user; create a lightweight account or unsalted ticket-only record)
  - Reason dropdown + free text
  - Optional per-event comp limit (counted server-side)
  - Emits a real ticket with QR — scannable like any other
- Hold list management:
  - Per-event "Holds" tab
  - Holds don't appear in sold counts, don't fire payment, don't count against caps
  - Hold list per category (artist/press/house)
  - Convert hold → comp (assigns to a real person)
  - Release hold → inventory (returns to sellable pool)
  - Bulk operations: "release all unclaimed press holds 1hr before doors"
- Will-call list:
  - Filter attendees with "will-call" pickup preference (set at checkout or always-on for box-office tickets)
  - Search by last name
  - "Mark claimed" updates status; shows pickup time + door staff user
  - **Batch PDF print:** one ticket per page, QR + name + seat + event header; use existing PDF generator path
  - Print queue order matches a configurable sort (last-name alpha by default)

### Potential issues

- **Comps in sold counts.** Easy mistake: dashboards include comps in revenue
  numbers. Every aggregate query must filter on `kind = 'PAID'` for revenue
  and split out comps separately. Add a DB view `paid_tickets` to avoid the
  bug pattern.
- **Holds and inventory math.** `available = capacity - sold - held - comped`.
  All four must come from the same query, or the dashboard drifts. Single
  view, single source of truth.
- **Comp ticket abuse.** Staff with `comps.issue` can give the entire show to
  their friends. Add per-event comp cap and audit-log every comp with the
  issuer's user_id. Surface as a report ("comps issued this month per staff").
- **Hold release timing.** "Release all artist holds 24hr before show" is a
  scheduled job. Reuse the existing scheduled-task path (seat hold cleanup).
  Don't introduce a new scheduler.
- **PDF print performance.** 200-attendee will-call list generates a 200-page
  PDF. Stream the PDF; don't buffer in memory. Use existing PDFBox setup.
- **Recipient privacy.** Comp ticket recipient names appear in scanned audit
  logs. Make sure GDPR-style "delete my account" cascades to anonymize, not
  hard-delete (audit trail must survive).

### Extras — recommended

- **Comp templates:** "Press +2 for the Coltrane show" reusable across nights
  in a residency. **Build during 4I (recurring events) — defer.**
- **Hold expiration warnings:** dashboard widget showing holds aging past
  threshold. **Build into M2-04 dashboard widgets.**
- **Will-call SMS pickup notification:** "your tickets are at the door, just
  ask for {name}." Roadmap puts SMS in Phase 3; **defer**, but data model
  supports it now.
- **Door-staff comp accept:** staff can mark a comp as "did not show" for
  reporting (artists like knowing). **Build into scanner flow in M3.**

---

## Section 2F — Settlement & reports (M2-15 → M2-18)

### What needs to happen

This is what an accountant or artist agent expects after every show. **Format
discipline matters more than feature breadth — get numbers to tie out exactly
or none of this is trusted.**

- **Day-of-show (DOS) report**, generated post-doors-open:
  - Sold count by tier (GA / VIP / etc.)
  - Comped count, broken out by reason
  - Held count (unclaimed at show time)
  - Gross revenue (sum of paid ticket face values)
  - Add-on revenue (if any sold; otherwise 0)
  - Sales taxes collected (per state)
  - Refunds issued (pre-show)
  - Platform fee
  - Stripe processing fee
  - **Net to venue** = gross + add-ons − taxes − refunds − fees
  - Export PDF + CSV; both must reconcile to the penny
- **Settlement report**, finalized post-show + 24h refund window:
  - All DOS fields, plus:
  - Post-show refunds
  - Promoter/artist split (configurable per event — e.g., "85% to artist after
    $1000 venue cut")
  - Final artist payout
  - Final venue retention
  - Signable: PDF stub with e-signature placeholder for v1; DocuSign
    integration deferred to M3
  - Both venue and artist (if artist has an account) can pull
- **Payout report:**
  - Stripe payouts mapped to events that contributed
  - 30-day rolling view per org
  - Shows pending → in-transit → paid lifecycle
  - Reconciles to Stripe dashboard exactly
- **Tax helper:**
  - Per-org 1099-K threshold tracking (year-to-date gross, transactions count)
  - Alert at 80% of threshold
  - State sales tax per event, configurable rate per venue address
  - Year-end CSV export for accountant import

### Potential issues

- **The numbers must tie out.** This is non-negotiable. If gross + adjustments
  don't equal payout, the entire feature is worse than not shipping it. Build
  reconciliation tests with property-based fuzzing (generate random
  events/sales/refunds/comps, assert invariants).
- **Stripe fee precision.** Stripe charges 2.9% + $0.30, but rounds at the
  cent. Recompute from `BalanceTransaction` records, not from the formula.
- **Refund timing edge cases.** A refund issued during the show vs after the
  show classifies differently in settlement. Pick a cutoff (show end + 24h)
  and document.
- **Tax law is hard.** Don't try to be TaxJar. For M2: configurable flat rate
  per venue, manual override per event. Defer dynamic tax to M5+.
- **PDF generation cost.** Settlement PDFs can be slow under load. Generate
  async, email when ready, store in S3/R2 with signed URL.
- **Promoter/artist split formulas** can be arbitrarily complex (door
  guarantees vs % of gate, walkout deals, etc.). M2 supports only the two
  simplest:
  - Flat % of net to artist
  - Door deal: venue takes $X off the top, then % split
  - Reject anything else with "contact support" — handle manually until M5

### Extras — recommended

- **Email settlement PDF to artist automatically:** end-of-night flow → next
  morning email with PDF attached. **Build, low effort, high value.**
- **Variance vs forecast:** show "you expected to sell X, sold Y." Needs a
  forecast model — **defer to M5**.
- **Multi-night settlement:** residencies bundle nights for one artist
  payout. **Defer to 4I.**
- **Per-staff sales attribution:** roadmap mentions in stretch goals. Adds a
  `sold_by_user_id` column to tickets — trivially cheap if added now. **Build
  the column in this migration, defer UI.**

---

## Section 2G — Custom branding & event pages (M2-19 → M2-23)

### What needs to happen

- **Per-org branding:**
  - `organizations.logo_url`, `primary_color`, `email_sender_name`, `email_reply_to`
  - Upload logo to S3/R2 with size limit; serve via CDN
  - Color used in event-page hero, email templates, ticket QR background
- **Per-event customization:**
  - Hero image (S3 upload)
  - Rich text description (Markdown stored, sanitized HTML rendered — do **not** allow raw HTML)
  - Set times, doors-open time
  - Age restriction enum (`ALL_AGES`, `18+`, `21+`)
  - Accessibility info (free text + structured tags for wheelchair, ASL, etc. — wire to Phase 5 5B)
  - Parking + public transit info
  - Performer bios (model exists)
- **SEO:**
  - Schema.org `Event` JSON-LD per event page
  - OG cards (image, title, description)
  - Semantic slugs (`/e/{org-slug}/{event-slug}`)
  - `sitemap.xml` regenerated nightly
- **Custom domain:**
  - Default: `tickets.fairtix.io/o/{org-slug}`
  - Pro+ tier: CNAME `tickets.{venuedomain}.com` → us
  - SSL: Caddy with on-demand TLS works for v1; revisit Cloudflare for Platforms at scale
  - Domain ownership verification: TXT record check before activation
- **Embed widget:**
  - `<script src="https://fairtix.io/embed.js" data-org="blue-note"></script>`
  - Renders an iframe with upcoming events for that org
  - Click-through goes to FairTix event page (not the iframe parent — keeps Stripe Elements happy)
  - Auto-resize iframe via `postMessage`

### Potential issues

- **Markdown XSS.** Sanitize aggressively. Use a maintained library; don't
  hand-roll. Test with the OWASP XSS cheatsheet payloads.
- **Image upload size.** Hero images at 4MB destroy mobile load times.
  Server-side resize + WebP/AVIF re-encode on upload. Cache headers right.
- **Custom domain operational load.** Each custom domain is a TLS cert to
  renew, a DNS record to monitor. Caddy on-demand TLS handles renewal but
  silently fails on misconfiguration. Add a health check job that hits
  `https://{custom-domain}/_health/branding` daily and emails the org on
  failure.
- **Slug collisions and rename safety.** Event slug changes break SEO and
  shared links. Keep historical slugs → 301 redirect to current.
- **Embed widget CSP issues.** Many venue websites set strict CSP. Document
  the required allow-list (script-src, connect-src, frame-src for the
  iframe). Provide a copy-pasteable CSP snippet on the embed config page.
- **Email sender domain authentication.** Custom `email_reply_to` requires
  the org to set up SPF/DKIM for our service. For M2, restrict to using our
  domain in the `From` and only customizing reply-to and display name. Full
  custom sender requires a Postmark/Resend signature setup — defer to M5
  when email marketing lands.

### Extras — recommended

- **Dark mode for event pages:** trivially small with CSS variables.
  **Build.**
- **Apple/Google Pay buttons above the fold:** noted for Phase 4 but really
  just a Payment Element flag flip. **Build now, win the mobile conversion
  early.**
- **Open Graph image auto-generation:** render an OG card per event with
  title/date/hero programmatically. Big share-conversion lift. **Build —
  ~half-day with `@vercel/og` or similar.**
- **`robots.txt` and `humans.txt`:** boring but signals professionalism to
  any technical buyer. **Build.**
- **iCal feed per organization:** `https://.../o/{slug}/events.ics`.
  Audiences can subscribe to a venue's calendar in Google/Apple Calendar.
  **Build — half-day, fits perfectly with "saved venues" in Phase 5.**

---

## Section 2H — Onboarding & vetting (M2-24, M2-25)

### What needs to happen

- Signup wizard:
  - Step 1: Email + password (reuse existing auth) + email verify
  - Step 2: Stripe Connect onboarding (redirect, return to step 3)
  - Step 3: Org details — legal name, DBA, address, EIN if tax-collecting, primary contact
  - Step 4: First event wizard with sensible defaults
- Admin approval queue:
  - New orgs land in `PENDING_REVIEW` state
  - Admin view: org details, Stripe Connect status, "approve / reject / request more info"
  - Approval emits `OrganizationApproved` event, unlocks event publishing
  - Reject emits email with reason
- Rate limits per new org:
  - First 30 days: $1k/day sales cap (per-day Stripe gross)
  - After 1 successful payout cycle: $10k/day
  - After 3 successful payout cycles + zero disputes: unlimited
  - Enforced in `PaymentService` before PaymentIntent creation
  - Hit-the-cap response: 429 with structured "contact support to raise limit"
  - Override-by-admin column (`plan_overrides_until`) for manual exceptions

### Potential issues

- **Approval queue latency.** If you're the approver and you're at school for
  3 days, the venue waits 3 days. Add a Slack notification on new org
  signup (already wanted as M1-deferred extra) + a 48h SLA self-imposed.
- **Stripe onboarding redirect chain.** Stripe → us → Stripe again if
  requirements update. Handle gracefully; show "your account needs more
  info" with a re-onboarding button.
- **EIN/PII storage.** EINs are PII-adjacent. Encrypt at rest. Don't log.
  Audit access. **This is the single highest data-risk item in M2.**
- **Cap evasion.** A user creates 5 orgs to evade the $1k/day cap. Defense:
  per-user aggregate cap, not just per-org. Plus device/email/Stripe
  fingerprinting (Stripe Radar handles a lot here).
- **First-event wizard scope creep.** It will be tempting to make it
  perfect. Set a budget: 2 days. Defaults > customization at this stage.

### Extras — recommended

- **Demo account auto-provisioning:** new orgs get a sandbox event
  pre-populated so they can play with the dashboard before publishing.
  **Build — half-day.**
- **Onboarding email drip:** day 0 welcome, day 1 "create your first event",
  day 3 "schedule your test scan," day 7 check-in. **Defer to Phase 6
  marketing work but draft now.**
- **Reference check field:** "what venue referred you?" → drives the
  referral-credit system from the roadmap GTM section. **Build the field
  now, defer the credit-mechanic to Phase 6.**

---

## Cross-cutting concerns

These apply across multiple sections; track as separate small issues or fold
into the most relevant ticket.

| Concern | Action |
|---|---|
| **Audit coverage** | Every mutation in M2 must emit an audit event with `REQUIRES_NEW` (M1 pattern). Reviewing PRs: if there's no audit, reject. |
| **Correlation IDs** | M1's `RequestIdFilter` already propagates. Confirm new Stripe Connect calls put the request ID in Stripe metadata. |
| **JaCoCo gate** | M2's biggest risk is dropping coverage. Set the M2 minimum 1% above the M1 baseline; PR-fail on regression. |
| **Migration discipline** | Track V-numbers in this file as they're claimed. Suggested starting point V38; reserve through ~V55 for M2. |
| **`@OrgScoped` enforcement** | Add a CI lint that fails the build if any new `*Controller.java` has an unannotated mutation endpoint. |
| **Frontend tests** | M1 deferred organizer-route tests because routes didn't exist. They exist now — write them as you go. |
| **Staging smoke** | Each section ends with a real-money-equivalent end-to-end test on staging using Stripe test mode. |

---

## Definition of done for M2

The roadmap's exit criteria, restated as a checklist:

- [ ] A new user can sign up as an organizer
- [ ] Complete Stripe Connect onboarding end-to-end
- [ ] Create a venue + an event + seats
- [ ] Issue 3 comps with reasons
- [ ] Hold 5 seats for the artist
- [ ] Sell 10 tickets including 2 at the box-office tablet (1 cash, 1 card)
- [ ] Scan them at the door (placeholder until M3 — at least verify ticket records are scannable-shaped)
- [ ] Pull a DOS report that ties out to the penny
- [ ] Watch Stripe pay the organizer (minus platform fee) on the test-mode schedule

Plus M2-specific:

- [ ] All M2 PRs merged through CI with coverage gates green
- [ ] Migrations V38 → V?? clean on anonymized prod restore
- [ ] Zero unannotated org-scoped controller methods (CI lint passes)
- [ ] Staging environment exercised end-to-end via the 60s screencap

---

## Highest-leverage extras not in the original roadmap (build if time allows)

Ranked by ROI:

1. **OG-image auto-generation per event** — half-day, dramatic share-conversion lift
2. **iCal feed per org** — half-day, deep integration with Apple/Google Calendar that competitors don't bother with
3. **Dashboard weekly email digest** — ~3h, strong retention lever
4. **Stripe statement descriptor per org** — minutes, real customer trust
5. **Manual "pay out now" button** — half-day, cash-flow win for venues
6. **Quick-comp button in box office** — hours, daily workflow improvement
7. **Real-time WebSocket toast for new sales** — reuse existing SSE, half-day
8. **Demo sandbox event for new orgs** — half-day, first-impression win
9. **Dark mode event pages** — minutes with CSS variables
10. **Slack/ops webhook** (carried over from M1-deferred) — pair with staging cutover

Build any of these only if M2-01 through M2-25 are on schedule. Don't trade
core scope for nice-to-haves.

---

## Risks specific to M2

- **Scope is the largest in the whole 6-month plan.** ~67 dev-days. If you
  slip badly here, M3 (scanner + wallet) compresses dangerously.
- **Stripe Connect is the single biggest external dependency.** Onboarding
  flow problems can stall the entire phase. Spike M2-06 in week 1 to
  de-risk.
- **Settlement reports are unforgiving.** Wrong math destroys credibility
  with the exact buyer (accountants, agents) you're trying to win. Budget
  more test time here than feels necessary.
- **Real venue feedback should start arriving** if Phase 6 outreach started
  early. Mid-M2 customer feedback can change priorities — leave a 20% slack
  budget for that.

---

## Next steps

1. Open the 25 issues from the index table on the GitHub project board
2. Spike M2-06 (Stripe Connect onboarding) in week 1 — biggest external risk
3. Land staging + cookie-domain ADR before any M2 PR opens
4. Order Stripe Terminal reader (1–2 week lead time) so M2-10 isn't blocked on hardware
5. Pick an M2 demo-day target (~end of week 8) and freeze scope 1 week before
