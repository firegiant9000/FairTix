# FairTix — Strategic Evaluation & 6-Month Solo Roadmap

_Prepared 2026-05-12_

---

## TL;DR

FairTix is a **technically strong MVP** that already covers ~80% of the surface area of a real ticketing platform: events, venues, performers, seat holds (Redis), queues (SSE), Stripe payments, refunds, transfers, fraud scoring, audit logging, admin console, deployment pipeline. Code quality is high, tech debt is low, test coverage is respectable.

The hard question is **not whether FairTix can be finished** — it's whether finishing it produces something a market will pay for. As a solo developer, you cannot out-build Ticketmaster, Eventbrite, SeatGeek, AXS, or DICE on the general ticketing front. They have 10–500 person engineering teams, decade-long venue relationships, and exclusive contracts.

**My recommendation: pivot into a narrow vertical where the "fair access" thesis is a real differentiator, and use FairTix as the foundation.** The most viable wedge is **small-to-mid independent venues and community events** (200–2,000 cap) where the dominant pain is bot resale, Eventbrite's fee bite, and lack of a queue/anti-bot story. A secondary option is to repackage the queue + anti-bot layer as an **embeddable widget** other platforms integrate, which is technically interesting but a much harder sale.

The 6-month plan below assumes the **independent-venue vertical**. If you pick differently after reading the analysis, the engineering pieces still apply — only the go-to-market changes.

---

## Section 1: Honest Project Evaluation

### What's actually built (and good)

| Area | State | Notable |
|---|---|---|
| Backend modules | 21 domains, layered cleanly | api/application/domain/infrastructure split is consistent |
| Seat holds | Redis-backed, deadlock-safe (UUID-ordered locks), 10-min TTL, per-user caps | Genuinely correct concurrency — most homegrown ticketing systems get this wrong |
| Queue / waiting room | Redis position + SSE stream + admin admit | Real-time, the hardest UX piece in fair-access ticketing |
| Auth | JWT in HttpOnly cookies + refresh rotation + email verification + reCAPTCHA + login throttle | Mature; better than most side projects |
| Payments | Stripe PaymentIntent + webhook + simulated fallback | Real integration, not a stub |
| Refunds | Auto-approve <$50, manual review otherwise, full audit | Workflow is correct; **Stripe refund API call itself is not wired** |
| Audit | `REQUIRES_NEW` propagation so audit survives rollback | Indicates the author understood transactional pitfalls |
| Fraud | Risk scoring, behavior analysis, step-up gate, manual flag review | Framework exists; not used in real-time blocking |
| Admin console | 9 sub-pages, charts, CRUD across all entities | More complete than expected |
| CI/CD | GitHub Actions + Trivy + OWASP DC, Railway backend, Netlify frontend, GCP Cloud Build prepped | Deployable today |
| Frontend | React 18, no Redux noise, geo search via Leaflet, Stripe Elements | Clean, no abandoned migrations |
| DB | 29 Flyway migrations, all forward-only | Schema discipline is intact |

### What's weak

1. **Notification preferences are not enforced at send time.** The `NotificationPreference` table is queried by users but emails fire regardless. Single small fix, but it's a credibility liability for anyone reviewing the code.
2. **Refund execution is incomplete.** Admin approves a refund and the row flips to `APPROVED` — no Stripe refund call is made. The customer doesn't get money back. This is the single highest-priority bug.
3. **No organizer self-service.** Only admins can manage events. Real organizers need their own dashboard with per-event ACL. This is the biggest *product* gap.
4. **No QR / gate scanning.** Tickets are PDFs/emails with no scannable codes. A "real" ticketing platform must support gate entry, or it's just an online store for entitlements.
5. **No mobile app.** Not necessarily a phase-1 blocker, but expected at production.
6. **Analytics is a single admin dashboard** querying live DB. Fine at MVP scale, will not survive any growth.
7. **Notifications are email-only.** No SMS, no push, no webhooks to organizers.
8. **No public API or webhook surface for organizers**, so integrations are impossible without code changes.
9. **Auth migration is partial.** Some old code still references the bearer-token / sessionStorage path; new code uses cookies. Worth a single cleanup pass.
10. **No accessibility audit.** WCAG 2.1 AA is legally required in some markets (US ADA, EU EAA going live 2025–2026) and effectively required for any government, nonprofit, or university client.
11. **Test coverage gaps:** analytics, rate limiting, notifications, venue sections, performers.
12. **No staging environment.** Prod fixes (Railway, Netlify, Redis NOAUTH) happened directly against prod recently — fine for a school project, not fine for paying customers.

### What's not present and probably shouldn't be

Don't build these yet, regardless of direction:
- Secondary resale market (huge regulatory + fraud surface; not your wedge)
- Multi-currency until you have a non-US customer
- Internationalization until you have a non-English customer
- ML-based real-time fraud blocking (your rule-based scoring is more than enough at MVP scale)
- Native mobile apps (PWA + scanner-only mobile is sufficient for 6 months)
- Microservices / horizontal scaling (single Spring Boot deploy handles 10k concurrent users on Railway)

---

## Section 2: Market Reality Check

### The general ticketing market is brutal

| Player | Position | Why they win |
|---|---|---|
| Ticketmaster / Live Nation | Primary, big venues | Exclusive venue deals, scale |
| AXS | Primary, sports/arena | Exclusive venue deals |
| SeatGeek | Primary + secondary, mid-market | Aggressive sales, MLB deals |
| Eventbrite | Self-serve small events | Frictionless onboarding, brand |
| DICE | Mobile-first, anti-resale | Cool brand, artist relationships |
| Stripe Atlas / Shopify | DIY ticketing via storefront | Distribution |
| Posh, Partiful, Luma | Social-event ticketing | Mobile UX, viral loops |

**The competitive moats in this space are not technical** — they're venue relationships, artist relationships, and brand. You can't beat any of them in a heads-up sales fight.

### Where there's actual room

The interesting gaps are:

1. **Independent venues (200–2,000 cap)** — comedy clubs, jazz clubs, small theaters, all-ages spaces, college venues. They use Eventbrite and hate the fees (3.7% + $1.79). Many have ongoing problems with bot-bought tickets being resold on StubHub. **The "fair access" framing is a real value prop here, not vanity.**
2. **Community / municipal events** — county fairs, park concerts, library events. Often run on spreadsheets or Eventbrite. Low ARPU but extremely sticky and underserved.
3. **Conference / workshop / class ticketing** — universities, makerspaces, training providers. They mostly use Eventbrite or roll their own. Seat maps + waiting rooms are usually overkill here; the value is structured organizer dashboards.
4. **Embeddable fair-access layer** — a SaaS that any existing ticketing platform calls before they release seats: "verify this user, queue them, rate-limit them, score them." Technically interesting; **commercially very hard** because you have to sell to your competitors.
5. **White-label for sports leagues / arts orgs** — single-tenant deployments. High ARR per customer, long sales cycles, not a great solo-founder wedge.

### What "fair access" actually means as a positioning

FairTix's tech advantage isn't "we have a queue." Lots of platforms have queues. The advantage is:
- Redis-backed correctness (you actually hold seats reliably)
- Per-user purchase caps enforced at the DB layer (V12)
- Real-time risk scoring tied to behavior
- Audit-everything posture (V5 + REQUIRES_NEW)

That bundle is genuinely valuable to organizers who got burned by bots — but only if you tell that story. The current README and UI do not.

---

## Section 3: Three Strategic Paths

### Path A — Independent venue SaaS (RECOMMENDED)

**Pitch:** "Eventbrite for indie venues, with the anti-bot story Ticketmaster wishes it had — and the box-office tooling Eventbrite never built."

**Target customer in detail.** Imagine the Blue Note in NYC, The Comedy Cellar, Maple Leaf in New Orleans, your local jazz club, a college-town theater, a 600-cap rock club, a comedy club with three shows a night, an off-Broadway-style 200-seat space. They have between 30 and 300 events a year, sell 50–80% capacity on average, run on Eventbrite or Squarespace+Stripe or Posh or DICE, hate Eventbrite's fees, lose 1–5% of tickets to bot-bought resale on StubHub, manually maintain comp lists on paper, run the door with a clipboard or Excel, and currently have no settlement report that ties paid tickets to artist payouts in one place.

**Why they'd switch.** Three things in priority order:

1. **Fee math.** Eventbrite charges them ~3.7% + $1.79 per ticket; you charge ~1.5% + Stripe fees on Pro. On a $40 ticket, that's $0.60 saved per ticket. Across 10,000 tickets/year that's $6,000 — enough to justify a switch.
2. **Box office tooling Eventbrite doesn't have.** Day-of-show reports, settlement/payout reports per artist, hold lists (artist/press/house holds), box-office mode for walk-up cash, will-call print queue. This is the part most "Eventbrite replacement" attempts miss.
3. **Anti-bot story.** Real queue, per-user caps, identity-locked tickets, verified-fan presales. Demonstrably reduces scalping. Lets the venue tell its artists "we control resale" which matters for artist-side relationships.

**Monetization model.**

| Tier | Price | For | Includes |
|---|---|---|---|
| Free | $0 + 2.5% | Small venues testing the platform | Up to 200 tickets/mo, branded event pages, QR scanner, basic reports |
| Pro | $49/mo + 1.5% | Most working venues | Comp/hold lists, settlement reports, custom branding, Stripe Connect, SMS, webhooks |
| Scale | $199/mo + 1.0% | Multi-room venues, regional promoters | Multiple venues, multi-user staff with roles, API access, priority support, custom domain |
| Enterprise | Custom | Festivals, university arts centers | SSO, data residency, custom integrations, on-call support |

Add a per-ticket Stripe pass-through (2.9% + $0.30 standard) so your margin is clean on the platform fee.

**Optional revenue rails (do not build day-one but design for):**
- **Refund protection:** partner with Booking Protect or sell first-party; 6–10% of ticket price; 20–40% take-rate; venue gets a cut.
- **Add-ons rev share:** merch pre-orders, parking, drink tokens, coat check. Venue keeps revenue, you take 1.5% platform fee.
- **Email/SMS marketing:** $0.01/email, $0.04/SMS, marked up from Postmark/Twilio.
- **Affiliate / promoter codes:** track per-staff sales, optional 2% creator-economy fee.
- **Verified-fan presale:** $99 flat per high-demand event.

**Engineering scope summary (full detail in Section 4).** This is no longer "ship organizer dashboard and QR." Real production indie-venue software needs all of:

- Organizer self-service + ACL
- Stripe Connect (Standard accounts, application fees)
- QR / Apple Wallet / Google Wallet tickets
- Scanner PWA with offline queue
- Box office mode (cash + card at door, walk-up sales)
- Day-of-show report + settlement / payout report
- Comp tickets, hold lists (artist/press/house), will-call list with print queue
- Promoter codes & discount codes (percent, fixed, BOGO, member-only)
- Presale codes & verified-fan registration
- Refund execution (Stripe API), refund-to-credit, donate-back-to-venue refund option
- Group buying / split payment via Stripe
- Add-on items (merch, parking, drinks, coat check) bundled with a ticket
- Ticket gifting & in-platform transfer (already partly built)
- Branded event pages with theming, custom domain support, embed widget for venue's own website
- SMS notifications + day-of geofenced reminders
- Email marketing (basic blast + segmented to past attendees)
- Webhook delivery to organizers (`ticket.sold`, `ticket.scanned`, `refund.issued`, `event.published`)
- Public REST API for organizers
- Tip-the-artist / tip-the-venue at checkout
- Accessibility seat tagging (wheelchair, ASL, companion seats, low-vibration)
- Recurring events / residencies / multi-night series
- Membership / season pass / subscription tickets
- "Drop" mechanic (release at specific timestamp) + lottery mechanic
- Identity-locked tickets (mobile-only, no PDF resale) as anti-scalp option
- Geofenced ticket reveal (QR only shows within N miles of venue) as anti-scalp option
- Apple Pay / Google Pay / BNPL via Stripe
- Schema.org event JSON-LD + OG cards (Google event search ranking)
- SEO-friendly event slugs and sitemap
- Mailing list export + Mailchimp/Klaviyo sync
- Staff roles (owner, manager, box office, door, marketing, accountant)
- 1099-K reporting helper + state sales tax handling
- Audit trail visible to organizer (already partly built; expose to org)

**GTM in detail.**

- **Founding 10:** First 10 venues come from cold outreach. Target by city (start with one — e.g., New Orleans, Austin, Nashville, or wherever you have any network). Find owners via Instagram DMs more than email; venue owners answer Instagram. Offer free first-event pilot.
- **Channel 1 — direct outreach:** 25 venues/week, personalized, naming a recent event. Track in Notion/Airtable.
- **Channel 2 — content/SEO:** Write 1 post/week. Topics: "How to fight scalpers at an indie venue", "The math of switching from Eventbrite", "Day-of-show reports explained". Rank for venue-owner queries.
- **Channel 3 — word of mouth:** Venue owners talk to each other constantly. One happy customer in a city = 3 referrals within 60 days. Make referral easy and reward it (one month free).
- **Channel 4 — talent agents:** A booking agent who likes the queue / anti-scalp story will push it to multiple venues. Long sales cycle, high leverage.
- **Channel 5 — local press:** Each city has 1–2 alt-weeklies that cover local venues. Free PR if you have a real story.

**Risk:** Sales is harder than engineering. You will spend a lot of months 4–6 doing outreach you don't enjoy. Mitigation: timebox sales work; treat it as a learnable skill; first 5 customer calls are *learning*, not selling.

**Why I recommend this.** You already have ~70% of the product surface for a real ticketing platform. The remaining 30% is well-scoped. The wedge is narrow enough that you don't compete with Ticketmaster (different customer) or Eventbrite (you out-feature them on box-office tooling). ARPU is high enough that 10 customers = ~$10k MRR which is meaningful at your stage. The anti-bot story is genuine and defensible — most competitors literally cannot build the queue mechanic correctly.

### Path B — Embeddable fair-access widget

**Pitch:** "Drop our anti-bot queue into your existing ticketing checkout in one line of JavaScript."

- **Customer:** Mid-size ticketing platforms, Shopify-store ticket sellers, festival organizers running their own checkouts
- **ARPU:** Usage-based ($0.01–0.10 per protected request) or $500–5,000/mo enterprise
- **Engineering needed:** Significant refactor. Queue + risk scoring + rate limiting need to be extracted into a standalone service with its own API, JS SDK, embedded widget, multi-tenant data model, customer dashboard. Roughly 4 months of engineering before you can sell.
- **GTM:** Developer marketing (blog, GitHub stars, HackerNews, conference talks). High brand-leverage but slow.
- **Risk:** Selling B2B infra solo is extremely hard. Most successful infra startups have 2–4 founders. The product is also borderline DIY-able with Cloudflare Turnstile + Bull queue + 200 lines of code.
- **Why I don't recommend this first:** The technical lift to extract is large, and the sales motion is uniquely hard for a solo founder with no enterprise track record. Revisit at month 12 if Path A is working.

### Path C — Open-source it as a portfolio + interview asset

**Pitch:** "FairTix is the open-source reference implementation of a fair-access ticketing platform."

- **Customer:** Yourself (job market), the dev community
- **Engineering needed:** Polish, docs, deployable demo, clean architecture writeup
- **GTM:** Blog posts about the queue mechanic, SeatHoldService deep-dive, the Redis-FOR-UPDATE pattern. Submit to HN, /r/programming, lobste.rs.
- **Risk:** Lowest. Worst-case it's a strong portfolio piece that lands you a senior backend role.
- **Why this is a real option:** You're a senior CS student. A polished, public, deployable system with this much depth is **worth more in interviews than a $0 ARR side business**. If you're not sure you want to do sales, Path C is the highest-EV use of 6 months.

### My honest take

If you want to **try to build a business**, do Path A. The market is real, your code is real, and 6 months is enough to get to first paying customer.

If you're **not sure you want to do sales and outreach for 12+ months**, do Path C. Be deliberate about it: package this for the job market. It will probably 2x your offers and recruiter inbound versus an unfinished side project.

**Don't do Path B as a first move.** It's seductive because it's all engineering, but the commercial path is genuinely worse than Path A for a solo person.

The rest of this document assumes **Path A**, with Path C deliverables baked in as a side-effect (polish, public landing, blog content). If Path A fails to find traction by month 5, you exit cleanly with a strong Path C asset.

---

## Section 4: 6-Month Solo Roadmap (Path A)

### Operating assumptions

- ~15–20 hrs/week available outside school + work
- Sonnet for routine coding, Opus for design decisions
- One branch per issue, PR-per-feature, no direct commits to main
- No new dependencies without a written reason
- Every phase ends with a demo and a written reflection — used to decide whether to continue
- Treat the first 3 months as engineering, the last 3 months as 50/50 engineering + go-to-market

### Phase 0 — Decision & setup (Week 0, ~1 week)

**Goal:** Commit to a direction and clear the runway.

- [ ] Pick Path A vs C explicitly, write it down in this file
- [ ] Create GitHub project board with the milestones below
- [ ] Stand up a staging environment (Railway has free preview envs)
- [ ] Buy domain (fairtix.com or fairtix.io if available, else pivot the name — "fairtix" + niche keyword)
- [ ] Replace the school-style README with a positioning README (one-liner, who it's for, how to run)
- [ ] Write one paragraph in this file under "Customer hypothesis": who is the venue, what do they currently use, what do they pay, why would they switch

### Phase 1 — Production-blocking fixes (Weeks 1–3)

**Goal:** Stop shipping a product with known correctness bugs.

| Week | Work | Why |
|---|---|---|
| 1 | Wire Stripe refund API call from `RefundService.approveRefund()` and Stripe webhook for `charge.refunded` | Currently approving a refund does not actually refund the customer. This is the #1 credibility bug. |
| 1 | Enforce `NotificationPreference` in every email send site | Users can opt out but still receive mail today |
| 2 | Add correlation IDs (request ID → audit log → email → Stripe metadata) via `MDC` and a `RequestIdFilter` | Required for any future support workflow |
| 2 | Finish the cookie auth migration — remove remaining sessionStorage and bearer-token paths in the frontend | Half-state is a recurring bug source |
| 3 | Stand up staging env on Railway, point a `staging.` subdomain at it, automate deploy on `develop` branch | All future work goes through staging first |
| 3 | Add jest + JUnit coverage to the existing CI gate (fail PR if coverage drops) | Lock in current quality before adding features |

**Exit criteria:** A refund test in staging actually returns money to the test card. Email opt-outs are respected. CI fails on coverage regressions.

### Phase 2 — Organizer self-service & box office (Weeks 4–8)

**Goal:** A venue can sign up, list an event, sell tickets at the door and online, settle the show, pay the artist, and never talk to you. This is the largest single piece of work in the plan and the part that genuinely differentiates you from Eventbrite. Budget conservatively — likely runs to 5 weeks.

#### 2A. Role model & ACL

- Add `ORGANIZER` role plus staff sub-roles: `OWNER`, `MANAGER`, `BOX_OFFICE`, `DOOR`, `MARKETING`, `ACCOUNTANT`. Each role maps to a permission set (events.write, sales.read, payouts.read, scanner.access, etc.).
- New tables (Flyway): `organizations`, `organization_members` (userId, orgId, role), `organization_invites` (email, role, token, expiresAt).
- ACL middleware: every existing controller method that takes an event/venue id must verify the requester belongs to that org. Add `@OrgScoped` annotation + interceptor pattern.
- Refactor existing `organizer_id` on events to `organization_id` (organizations can have multiple users); keep a backfill migration that creates a 1-person org per existing organizer.

#### 2B. Organizer dashboard

- `/organizer` route tree: dashboard, events list, event detail, sales, attendees, holds, comps, payouts, settings, team, integrations.
- Dashboard widgets: today's shows, week's revenue, refund queue depth, recently sold, top events by velocity, upcoming hold release reminders.
- Per-event view: sold/held/available/comped breakdown, sales velocity chart, attendee list, scan progress (live during event), revenue + fees + payout estimate.

#### 2C. Stripe Connect

- Stripe Connect Standard accounts (organizer onboards via Stripe-hosted flow).
- `application_fee_amount` on every PaymentIntent = platform fee per tier.
- Webhook handlers: `account.updated`, `account.application.deauthorized`, `payout.paid`, `payout.failed`, `charge.dispute.created`.
- Connect dashboard view inside organizer panel: account status, pending balance, next payout date, payout history.
- Reverse fees on refund (Stripe handles automatically via `reverse_transfer: true`).

#### 2D. Box office mode (in-person sales)

This is what Eventbrite doesn't do well and is a strong wedge.

- `/box-office` route, optimized for tablet (iPad/Surface) used at the venue door or ticket window.
- Walk-up cash sales: pick event → pick seat or GA → "Cash" or "Card" → email/SMS receipt optional → ticket emitted.
- Card sales via Stripe Terminal SDK (BBPOS WisePOS or Stripe-built reader). Requires Stripe Terminal location setup.
- Comp tickets issued on-the-spot.
- Will-call: search attendee by last name, mark "claimed," print or display QR.
- End-of-night reconciliation: cash drawer total, card total, expected vs actual, manager sign-off.

#### 2E. Comps, holds, will-call

- `tickets.kind` enum: `PAID`, `COMP`, `HOLD_ARTIST`, `HOLD_PRESS`, `HOLD_HOUSE`. New Flyway migration.
- Comp issuance UI: pick seats → reason → recipient name/email → optional comp limit per show.
- Hold lists: artist holds (configurable per artist contract), press holds, house holds. Holds don't fire payment, don't appear in sales count, can be converted to comps or released back to inventory.
- Will-call print queue: PDF generator (one ticket per page, QR + name + seat + event), batch print at door.

#### 2F. Settlement & day-of-show reports

Industry-standard reports artists and accountants expect.

- **Day-of-show (DOS) report:** sold count by tier, comped count, held count, gross revenue, taxes collected, refunds, fees, net to venue.
- **Settlement report:** ticket counts, gross revenue, less platform fee, less Stripe fee, less venue's promoter cut, less taxes, equals artist payout. Exportable PDF + CSV. Signable (e-signature stub for v1, DocuSign integration later).
- **Payout report:** rolling 30-day view of Stripe payouts mapped to events.
- **Tax helper:** 1099-K threshold tracking per organization, state sales tax breakdown per event (configurable rate per venue), end-of-year export.

#### 2G. Custom branding & event pages

- Per-org branding: logo, primary color, custom email sender, reply-to address.
- Per-event page customization: hero image, description (rich text), performer bios (already have model), set times, doors-open time, age restriction (`21+`, `18+`, `All Ages`), accessibility info, parking info.
- SEO: schema.org `Event` JSON-LD on each page, OG cards, semantic slug (`/e/blue-note/coltrane-tribute-2026-08-12`).
- Custom domain support (CNAME to `yourvenue.fairtix.io` → eventually `tickets.yourvenue.com`). Cloudflare for Platforms or a simpler Caddy reverse-proxy works for v1.
- Embed widget: `<script src="fairtix.io/embed.js" data-org="blue-note"></script>` renders upcoming events on the venue's own website.

#### 2H. Onboarding & vetting

- Signup → email verify → Stripe Connect onboarding → org details (name, address, EIN if collecting) → first event wizard with sensible defaults.
- Admin sees an "Organizations" approval queue. Manual approval for the first ~50 customers to prevent fraud (someone signs up, sells fake tickets, vanishes with the money before payouts complete).
- New-org rate limits: $1k/day in sales for first 30 days, then auto-raised to $10k/day, then unlimited after first successful payout.

**Exit criteria:** You sign up as a fresh organizer, complete Stripe onboarding, create a venue + an event + seats, issue 3 comps, hold 5 seats for the artist, sell 10 tickets including 2 at the box-office tablet, scan them at the door, pull a DOS report that ties out to the penny, and watch Stripe pay the organizer (minus platform fee) two days later.

### Phase 3 — Gate entry, wallet passes & ticket trust (Weeks 9–12)

**Goal:** A venue can actually use FairTix at the door, and attendees get the modern wallet-pass experience they expect from a 2026 ticketing platform.

#### 3A. Signed QR & scan endpoint

- Add `qr_code` column to `tickets`. Payload is a signed JWT: `{ticketId, eventId, holderUserId, issuedAt, nonce}`, signed with a per-event HMAC secret stored in the event row (rotate per event = stolen secrets only burn one event).
- Generate on issuance; render as PNG in confirmation email and as inline SVG on the My Tickets page.
- `PATCH /api/tickets/scan` endpoint: validates JWT signature, checks scan count and event time window (no scans before doors-1hr or after end+2hr), marks scanned, idempotent under the nonce.
- Response states: `VALID`, `ALREADY_SCANNED` (returns when/where it was first scanned), `INVALID_SIGNATURE`, `WRONG_EVENT`, `REFUNDED`, `TRANSFERRED_AWAY`, `OUTSIDE_WINDOW`.
- Audit every scan (door staff user, device, timestamp) — feeds the live dashboard and fraud module.

#### 3B. Apple Wallet & Google Wallet passes

This is table stakes in 2026 and a major attendee-side delight.

- Apple Wallet (`.pkpass`): use `passkit-generator` or a Java equivalent. Pass type = `eventTicket`. Include event title, date, time, doors, seat, organizer logo, QR code as barcode. Relevant date triggers Lock Screen reminder. Geofence triggers Lock Screen reveal when within 1km of venue.
- Google Wallet (Event Ticket): use Google Wallet API. Same content. Server-to-server JWT for adding.
- "Add to Apple Wallet" / "Add to Google Wallet" buttons on order-confirmation email and ticket detail page.
- Pass update endpoint: when a ticket is refunded or transferred, push pass update via APNs / GW API so the pass on the device updates or is invalidated.

#### 3C. Scanner PWA

- Separate route `/scan`, registered as a PWA (installable to home screen, runs offline).
- Camera-based scanning via `BarcodeDetector` API on Chrome/Edge/Android, fallback to `zxing-js` on iOS Safari.
- Manual entry fallback (last 6 digits of ticket id).
- Offline-tolerant queue: scans recorded to IndexedDB if offline, synced when connection returns, conflict-resolved server-side (first scan wins, subsequent are `ALREADY_SCANNED`).
- Audio + haptic feedback (green chime on valid, red buzz on rejected) — critical for noisy venue doors.
- Multi-device sync: 3 door staff scanning simultaneously, all see live attendance count and can re-verify each other's scans.

#### 3D. Door staff role & multi-event assignment

- Door staff is a sub-role under organization. Assignable per-event (a person can work the door for tonight's show but not tomorrow's).
- Magic-link login: organizer sends staff a one-click login that grants scanner access for one event for 12 hours, no password needed. Lowest-friction onboarding for casual staff.

#### 3E. Live attendance dashboard

- SSE-driven counter on the organizer event view: total sold, total scanned, % attendance, scan rate per minute, last scan timestamp.
- Heat-map of seat sections by scan time (which sections filled first, useful for next show's staffing).
- Late-arrival list: tickets unscanned past doors+45min — useful for SMS reminder send.

#### 3F. Anti-scalp identity-locking (optional per event)

- Per-event toggle: `identityLocked: true`. When on, the ticket is bound to the purchaser's account and the QR only renders inside the FairTix app (no email PDF), preventing screenshot resale.
- Optional ID check at door: organizer mode that requires door staff to verify name matches an ID. Scanner UI surfaces the purchaser's name prominently.
- Optional geofenced reveal: QR only shows when the user is within N km of the venue (uses device geolocation in the PWA). Defeats most screenshot resale.

#### 3G. End-to-end demo

- Run a real event in staging, scan 50 fake tickets across 3 phones, validate that Apple Wallet pass updates fire when one ticket is refunded mid-event.

**Exit criteria:** A real test event runs end-to-end. Apple Wallet passes work. Scanner PWA works offline. Refunds invalidate the wallet pass within 30 seconds. The organizer's live attendance dashboard agrees with the count from the door scanners to the unit.

### Phase 4 — Monetization mechanics & access controls (Weeks 13–15)

**Goal:** Add the revenue-shaping features venues use to actually sell out shows. Most of this is what turns FairTix from "an online ticket form" into "a tool a promoter cares about."

#### 4A. Discount codes & promo engine

- New `discount_codes` table: code, organization scope (org-wide or event-specific), value (percent / fixed / BOGO), max uses, max-per-user, valid window, audience tag (e.g., "newsletter", "student").
- Stackable vs exclusive flag.
- Auto-tracking: who used, when, on which order. Powers per-code conversion reporting for the organizer.
- UI: organizer creates codes; attendee enters at checkout; live "discount applied" feedback.

#### 4B. Presale codes & verified-fan registration

- Presale code campaigns: organizer creates a code valid for a window (e.g., "ARTIST_FAN_CLUB" works 24hr before public sale).
- Verified-fan registration: attendees register interest before tickets go on sale; organizer reviews bot-score; clean registrants get a personal presale code by email. Reuses your existing fraud scoring.
- Member presale: an organization can mark certain users as "members" (CSV import, opt-in form, or integration with their existing mailing list); members get access N hours before the public.

#### 4C. "Drop" mechanic & scheduled releases

- Per-event `salesStartAt` and `salesEndAt`. Until `salesStartAt`, the event page shows a countdown + "remind me when on sale" email opt-in.
- At the drop time, the queue auto-engages if `queue_required=true`. Existing waiting room SSE handles this — just gate based on time.
- Drop-time SMS / email blast to interested users (reuse the new SMS infra).

#### 4D. Lottery / drawing mechanic

- Alternative to first-come-first-served for very high-demand shows. Attendees enter a window, lottery runs at deadline, winners get a "claim within 24hr" link.
- New `event_lottery` table: registrations, status (PENDING/WON/LOST), claimed status.
- Useful for residencies, intimate shows, charity events where fair access matters more than speed.

#### 4E. Group buying & split payment

- Attendee can invite up to N friends to a group order. Each friend pays their share via Stripe (no Venmo round-trip).
- Seats are held in a soft-hold while friends pay; group completes when last person pays or expires.
- Single QR per attendee but linked group ID for organizer.
- Reduces social-coordination friction — a real conversion lift on high-price tickets.

#### 4F. Add-ons & bundles

- Per-event optional add-ons: parking pass, drink tokens, coat check, meet & greet upgrade, branded merch (T-shirt sizes), VIP pre-show access.
- Inventory-tracked add-ons (e.g., only 50 parking passes available).
- Add-ons appear in cart, in QR-attached ticket metadata, in organizer settlement report as a separate line.
- Per-add-on fulfillment status (e.g., "shipped" for merch, "redeemed" for drink token).

#### 4G. Refund options menu

Three refund paths per request, organizer chooses which to offer:
- **Cash refund** (Stripe API, default).
- **Refund to credit** (issues a credit on the user's FairTix account, usable at the same venue. Higher retention, zero Stripe fee, organizer keeps the money).
- **Donate back to venue** (user waives refund, venue keeps amount as donation — generates a tax-receipt email. Surprisingly popular for indie venues with fan loyalty).

#### 4H. Ticket gifting & enhanced transfers

- Already have transfer; add a "Gift this ticket" flow: send via email with personal note, optional reveal-at-time-X (birthday/holiday).
- Bulk gifting: an organizer can gift tickets to a list (mailmerge).
- Optional transfer fee (organizer-configurable %) — useful as a soft-resale governor.

#### 4I. Recurring events & residencies

- Event templates: clone an event with new date (set times, performers, pricing). Critical for venues that run Tuesday Jazz Night every week.
- Multi-night residencies: parent event with child shows, single hero page, combined ticket bundle ("buy all 4 nights, 20% off").
- Series subscription: subscribe to a venue's recurring show series, auto-charge for each new instance, opt-out anytime.

#### 4J. Membership / season passes

- New `memberships` table: org, user, tier, validFrom, validTo, perks (auto-comp, pre-sale access, discount %).
- Sells like a subscription via Stripe. Auto-renews. Member dashboard shows benefits.
- For comedy clubs: "$25/mo gets you 4 shows" → strong retention lever.

#### 4K. Apple Pay, Google Pay & BNPL

- Enable Apple Pay and Google Pay in the Stripe Payment Element (one-line change).
- Enable Klarna and Affirm for orders >$50 (one-line, gated by Stripe). Useful for high-priced shows; mobile checkout conversion ~+15%.

**Exit criteria:** A test organizer can run a presale window with codes, gate by member status, sell a $150 ticket bundle with Apple Pay + Klarna, offer parking add-on, run a lottery on the next show, and have all of it tie out in the settlement report.

### Phase 5 — Attendee experience & marketing site (Weeks 16–18)

**Goal:** Make FairTix delightful for the buyer side, not just usable. Build the public-facing site so cold venues can discover and self-serve.

#### 5A. Attendee delight features

- **Seat-view photos:** upload photos from each section ("here's what the stage looks like from row M"). Shown when picking seats. Standard in modern arena ticketing; trivially good for venues you photo-walk once.
- **Friends going / social proof (opt-in):** show "12 people you may know are going" if user opts in. Optional and off by default for privacy.
- **Calendar add 1-click:** Google / Apple / Outlook, with pre-filled details and a reminder.
- **Pre-show info card:** auto-emailed 24hr before the event. Doors, set times, parking, public transit, weather forecast, "what to bring." Generated from event metadata.
- **Day-of geofenced reminder:** if the user opts in, push notification fires when they're within 30min commute of the venue ("Doors in 90 minutes — your seat is M-14").
- **Post-show feedback:** NPS-style 1-tap rating + optional comment, emailed 12hr after the event. Feeds organizer analytics.
- **"Going" badge on profile:** lightweight social signal users can share.
- **Tip the artist / tip the venue at checkout:** optional, configurable amounts, accounted in settlement. Surprisingly common — fans want to tip, venues like the optionality.
- **Personalized recommendations:** "you went to the Comedy Cellar last month, here are 3 upcoming shows there + 2 similar at other venues." Simple collaborative-filter based on past attendance.
- **Saved venues / "follow":** users follow venues, get email/SMS when new shows are listed. Drives repeat purchase.
- **Wallet pass updates with set times / gate info:** push pass updates the day-of with the latest schedule.

#### 5B. Accessibility & inclusion

- **Accessibility seat tagging:** organizers tag seats as `wheelchair`, `companion`, `ASL_view`, `low_vibration`, `low_light_sensory`. Filter on the seat picker; clearly surfaced.
- **Sensory-friendly performance mode:** event-level toggle showing accommodations (lower volume, lights left on, designated quiet space).
- **WCAG 2.1 AA pass on the checkout flow** (axe-core in CI + manual screen reader pass): proper labels, focus order, contrast, keyboard nav. Required for any government, university, or nonprofit customer.
- **Spanish-language checkout flow** (single locale, not full i18n yet): big conversion win in many US markets without the full i18n cost.

#### 5C. SEO & discovery

- Schema.org `Event` JSON-LD per event page (gets you in Google's event-search carousel — significant free traffic).
- Auto-generated sitemap, refreshed nightly.
- OG cards with event hero image, date, venue.
- Semantic URLs (`/e/{org-slug}/{event-slug}`).
- Public venue pages with upcoming + past events.
- Public city pages ("Shows in New Orleans this weekend") — generated from your geo data.
- Image CDN (Cloudflare R2 or BunnyCDN) for event hero images; cuts load times noticeably.

#### 5D. Public marketing site

- New landing page at root: hero ("Indie ticketing that fights the bots"), 3-feature explainer, comparison table vs Eventbrite (fees + features), customer logos (none yet — that's fine, replace with "small batch of venues piloting now"), pricing page, blog, careers placeholder, status page link, login.
- Built as static pages (can be the existing CRA build or a separate Next.js / Astro micro-site) — backend not required for marketing pages.
- Honest pricing page with explicit numbers (Free / Pro $49 / Scale $199 / Enterprise contact).
- "How FairTix works" explainer page: queue mechanic, anti-bot story, Stripe Connect flow, scanner. Animated GIFs > static screenshots.
- "Compare vs Eventbrite" page with a side-by-side fee calculator (input: ticket price + monthly volume → output: dollar savings).

#### 5E. Billing for organizers

- Stripe subscription on organization. Tier stored in `organizations.plan`.
- Free tier enforced server-side via a `ticket_credits_remaining` counter, reset monthly.
- Self-serve upgrade / downgrade in organizer settings.
- Invoice history download (Stripe-hosted).
- Trial: first 30 days on Pro free, no card required — reduces signup friction enormously.

#### 5F. Email marketing (basic)

- Organizer can email their attendee list (past attendees of their events). Segments: all, last-90-days, by-event, by-tier-purchased, by-zip-code.
- Template editor with simple visual builder.
- Send-time scheduling, A/B subject line testing.
- Compliance: forced unsubscribe link, double opt-in for new contacts, suppression list shared across all orgs.
- Send via Postmark or Resend (transactional infra you'd want anyway); mark these messages as marketing not transactional for deliverability.

**Exit criteria:** Public marketing site is live at the root domain. A new visitor can land, understand what FairTix does, see a pricing page, sign up, complete Stripe Connect onboarding, publish an event, and email their (imported) past-customer list — all without your involvement.

### Phase 6 — First customers & GTM (Weeks 19–22)

**Goal:** Get the first paying venue. Then the second and third.

| Week | Work |
|---|---|
| 19 | Compile target list: 100 venues across your chosen seed city. Filter by ticketed-paid-events (skip free-event venues). Find an Instagram + email contact for each. |
| 19 | Build a one-page case for switching: ROI calculator (input: their estimated yearly ticket volume + Eventbrite fees → output: yearly savings on FairTix). Send as part of outreach. |
| 20 | Outreach week 1: 30 Instagram DMs + 30 cold emails. Personalized — name a recent event. Goal: 3 calls scheduled. |
| 20 | "Why FairTix" content: write the 3 anchor blog posts (queue mechanic, anti-bot post-mortem from a public Ticketmaster failure, "the actual math of switching from Eventbrite"). Publish on the marketing site, syndicate to Medium / Hashnode for SEO. |
| 21 | Outreach week 2: iterate based on response. Send next 30. Demo any takers using staging — show the organizer dashboard, then the scanner PWA on your phone, then the DOS report. |
| 21 | Webhook delivery to organizers (full surface: `ticket.sold`, `ticket.scanned`, `refund.issued`, `event.published`, `event.sold_out`, `order.created`). Async delivery with exponential retry. Lets you say "yes" if asked about integration. |
| 22 | Onboard first pilot venue. Be on call. Document every friction point. Decide pricing concession (first month free, no platform fee for first event, etc.). |
| 22 | Mid-pilot retro: did anything break under real load? Were the box office and scanner UX good enough that the venue's staff used them without you in the room? |

**Exit criteria for continuing:** At least one venue is in production. At least one of: (a) they pay you, (b) they refer another venue, (c) they're running real ticket volume on the platform.

### Phase 7 — Scale or exit cleanly (Weeks 23–24, with longer if continuing)

Decision point at week 22.

#### Scenario A — pilot worked, scale wedge

| Week | Work |
|---|---|
| 23 | Onboard 2–3 more pilot venues from the existing outreach pipeline. Build whatever the first pilot surfaced as friction (commonly: reserved-seating UX, comp issuance flow, will-call list rendering). |
| 24 | Performance pass: k6 load test the queue + checkout under 1,000 concurrent users. Fix the first three bottlenecks. (Likely candidates: analytics queries, audit log writes, missing DB indexes on `tickets.user_id` and `seat_holds.user_id`, N+1 on event-list page.) |
| 24 | Real-time fraud blocking: wire `RiskScoringService` into checkout. Score ≥ 70 triggers step-up (extra reCAPTCHA + email confirmation). Score ≥ 90 blocks with appeal flow. Reduces chargebacks. |
| 24 | End-of-6mo postmortem: revenue, customers, MRR, lessons, year-2 plan. Write it as a public blog post — the marketing flywheel keeps spinning. |

Stretch goals if you have the time (or for months 7–9):
- Public REST API + API key management for organizers (low effort, big "we have integrations" credibility).
- Mailchimp / Klaviyo sync (push attendee lists out, pull suppression lists in).
- Stripe Terminal Card Reader for box office (physical reader, not just app).
- Customer-facing status page (statuspage.io subscription, or a simple static `/status` page).
- Backup & DR runbook: daily PG dumps to S3, weekly restore test, Redis AOF, "Railway is down" playbook.
- DocuSign or Dropbox Sign integration for signed settlements.
- Affiliate / promoter tracking codes (per-staff sales attribution).

#### Scenario B — pilot didn't work, harvest to Path C

| Week | Work |
|---|---|
| 23 | Write the architecture deep-dive: SeatHoldService walkthrough, the queue mechanic, the fraud framework. Publish on a personal blog. |
| 23 | Polish the local-dev experience: one-command `docker compose up`, seed data, demo accounts pre-created, walkthrough script. |
| 24 | Deploy a permanent public demo at `demo.fairtix.io`. Pre-populated events, demo organizer account, no real Stripe. |
| 24 | Open-source MIT. Push to GitHub. Write a great README with screenshots, architecture diagram, and an honest "why this exists" section. |
| 24 | Submit blog post to Hacker News, `/r/programming`, lobste.rs. Engage in comments. |
| 24 | Use the project actively in job applications. Rewrite resume around it. Reach out to 20 companies whose stack overlaps (Spring Boot + React + Postgres + Redis). |

In Scenario B you exit with: a polished portfolio asset, public proof of senior-level engineering work, blog content with traffic, and concrete interview ammunition. This is a **success** outcome, not a failure outcome.

---

## Section 5: What I'm explicitly telling you NOT to do

- **Don't add features that aren't on this list** during the 6 months. Every "wouldn't it be cool if" idea is a tax on the things that matter.
- **Don't refactor the modules into microservices.** The monolith is correct at your scale and for the next 50x.
- **Don't write a mobile native app.** The scanner PWA is enough. A native app is a 3-month project on its own.
- **Don't internationalize until a non-English customer asks.** Same for multi-currency.
- **Don't open-source until you've decided between Path A and Path C** (week 22). Open-sourcing in month 2 forecloses Path A pricing.
- **Don't take VC meetings.** At this scale and ARPU, you don't have a story they'll fund, and the time cost is large.
- **Don't replace the stack.** Spring Boot 4 + React 18 + Postgres 16 + Redis 7 is a fine stack. Resist the urge to rewrite anything in Go/Rust/Next.js/etc.
- **Don't compete with Eventbrite on free events.** Eventbrite is free for free events. You will lose. Target paid-ticket venues only.

---

## Section 6: Solo-developer operating notes

- **Cadence:** Two-week sprints. Each sprint has 1 ship goal and 1 learn goal. End each sprint by writing 5 lines: what shipped, what slipped, why, what's next, what surprised you.
- **Scope discipline:** If a task takes more than 1.5x its estimate, stop and reassess. Solo devs blow timelines by sliding silently, not by missing a single deadline.
- **AI assistance:** Default to Sonnet for implementation, Opus for design decisions and tricky debugging. Don't let either model rewrite parts of the codebase you didn't ask it to touch.
- **Sales as a skill:** If you choose Path A, the engineering is the easy part. Spend 30 minutes a week reading [Patio11's writing on B2B sales](https://www.kalzumeus.com/) and [Jason Cohen's content](https://longform.asmartbear.com/). Sales for engineers is a learnable skill; treat it like a new framework.
- **Customer interview rule:** First five customer conversations are *learning*, not selling. Don't pitch. Ask: "What do you currently use? What annoys you about it? Last time it failed, what happened? How much did that incident cost?"
- **Failure exits:** If by week 20 you have not had a single meaningful conversation with a real venue, exit to Path C without guilt. The cost of continuing is higher than the cost of pivoting.

---

## Section 7: Concrete next 7 days

If you agree with this plan:

1. Decide Path A vs C, write the choice in Section 3 of this file.
2. Open three GitHub issues: "Wire Stripe refund execution", "Enforce NotificationPreference at send time", "Stand up staging env".
3. Buy a domain.
4. Replace the README's first 10 lines with a positioning sentence and a "who this is for" paragraph.
5. Create a `customers.md` file (gitignored) and start listing target venues.
6. Block 8 hours on the calendar this week for Phase 1 work. Treat them as immovable.
7. Re-read this file at the end of the week and update the open questions below.

---

## Open questions (fill in as you go)

- Customer hypothesis (1 paragraph):
- Path chosen (A or C):
- Domain name:
- First 10 target venues:
- First three blog post titles:
- What would make you quit by week 22:

---

## M1 deferred items — picked up early in M2 or as needed

Recorded 2026-05-21 after the M1 commit train (`feat/m1-phase-1`, 18 commits).
The code work for #161–#169 is in; the items below are work that was either
deferred deliberately, blocked on a human decision, or not worth the M1 budget.
Each carries an owner-action and a rough effort estimate so M2 picks them up
without rediscovery.

> **Explicit deferrals (logged 2026-05-21):** the staging infra deploy and the
> Stripe refund integration test are **intentionally scheduled late**, not
> forgotten. Staging only becomes valuable once at least one outside reviewer
> needs to look at the work; until then the local docker-compose stack is
> faster to iterate against. The Stripe integration test depends on a test
> secret being added to GitHub Actions, which is a one-time operational step
> that pairs cleanly with the staging Stripe webhook setup. Plan to land both
> together in the first M2 sprint, not as part of the M1 PR train.

### Operational follow-ups (block staging cutover, not code)

| Item | Owner action | Effort |
|---|---|---|
| Push `feat/m1-phase-1` to remote and open the 6 per-issue PRs (or one umbrella PR). CI hasn't actually exercised the new coverage gates, cookie-auth guard, or notification-gate guard yet. | Push and observe the first CI run; capture the real JaCoCo number from the artifact and bump `jacoco.line.minimum` in `backend/pom.xml` to baseline-minus-1%. | 1h |
| Deploy staging per [`docs/runbook-staging.md`](docs/runbook-staging.md). | Provision Railway + Neon + Upstash + Mailtrap + Stripe test webhook; populate `.env.staging.example` into Railway env vars; verify `/_health/deep` returns UP. | 4–6h |
| Cookie-domain decision for cross-subdomain staging (api.staging.fairtix.io ↔ staging.fairtix.io). | Either set cookie domain to `.fairtix.io` with `SameSite=None; Secure`, or reverse-proxy `/api` through Netlify so both share an origin. Document in ADR 0001. | 1h |
| Run V32–V36 against an anonymized prod data restore before merging to `main`. V36 will fail loudly if the backfill leaves any org without an OWNER. | Restore a copy of prod into a throwaway DB; run migrations; inspect `events WHERE organization_id IS NULL AND organizer_id IS NOT NULL` (should be empty); inspect orgs with no OWNER (should be empty). | 2h |
| Record a 60-second end-to-end smoke screen-cap (signup → create org → place order → refund) once staging is live. | Plan calls for it as part of M1 definition-of-done. | 30m |

### Tests deferred (code paths exist, coverage doesn't)

| Item | Reason deferred | Effort |
|---|---|---|
| Stripe refund integration test using Stripe's test mode. | Requires `STRIPE_TEST_SECRET_KEY` in GitHub Actions secrets, plus a `@SpringBootTest` gated by `@EnabledIfEnvironmentVariable`. The synchronous path is already locked by `RefundServiceTest`; this would catch a Stripe SDK upgrade regression. | 2h |
| Frontend tests for organizer routes (`OrganizerRoute`, `OrganizerLayout`, `useOrganization`). | CRA + RTL is set up but the organizer flow is mostly placeholders until M2. Manual smoke is sufficient at the M1 surface. Revisit once M2 fills in the wizard. | 3h once M2 lands routes |
| `EventService.verifyOwnership` end-to-end test via `MockMvc` (currently service-layer only). | Existing tests at `EventServiceOrgAccessTest` cover the logic at service level. Controller-level test would also exercise `OrgScopeInterceptor` once M2 annotates `EventController`. | 2h paired with M2 controller work |

### Feature gaps surfaced during M1 (parked, not lost)

| Item | Notes |
|---|---|
| Organizer-scoped event create endpoint. | M1 plan deliberately scoped event creation to admins; organizer create-event wizard lands in M2 [#171](https://github.com/firegiant9000/FairTix/issues/171). `EventService.createEvent` now resolves a default organization, so it's wire-ready. |
| `X-Organization-Id` header injection from the frontend. | Not needed in M1 — all current `@OrgScoped` endpoints take `{orgId}` as a path variable. Add the header path when M2 introduces collection endpoints under `/api/organizer/...`. |
| Plan-tier enforcement on `TicketService.issueTickets`. | `PlanEnforcementService.checkCanIssueTicket` returns true unconditionally per the plan. M5 flips the switch. |
| Slack/Discord webhook for ops alerts (refund failure, new org signup, fraud flag). | Listed in M1.5 bonus. ~2h of work. Worth landing alongside the staging cutover so failures actually page someone. |
| Plan-overrides-until column for "free PRO for 3 months" sales mechanic. | Listed in #169 extras. Defer until first PRO conversion. |
| Mail send audit table. | Listed in #162 extras. Would become the basis for M5 marketing tooling. Defer to M5. |

### Schema-table note

The M1 Flyway numbering took V30–V37 (audit request_id, refund stripe id, org tables, org backfill, plan tier, backfill verifier, email_hold backfill). Appendix B below was written speculatively before M1 was scoped and assumes V32–V49 for **different** features; treat its V-numbers as advisory only. Future migrations should follow the live tree, not the appendix.

---

## Appendix A: Full Feature Catalog (priority-tagged)

Every feature considered, with rationale and priority. Use this as a backlog after the 6-month plan completes, or pull from it if a customer specifically requests something. **Tags:** `P0` = in the 6-month plan, must-have; `P1` = strong nice-to-have for months 7–12; `P2` = consider only after PMF; `SKIP` = not worth building yourself.

### Attendee-facing — purchase & access

| Feature | Tag | Why / why not |
|---|---|---|
| QR ticket | P0 | Table stakes |
| Apple Wallet pass | P0 | 2026 attendee expectation; major UX delight |
| Google Wallet pass | P0 | Same, for Android share |
| Saved venues / "follow" | P0 | Drives repeat purchase, cheap to build |
| Pre-show info email (24hr) | P0 | High delight, low effort |
| Post-show NPS feedback | P0 | Feeds organizer analytics, helps retention |
| Calendar add 1-click | P0 | iCal partly built; finish Google/Outlook |
| Day-of geofenced reminder | P0 | Push notification, big delight, low cost |
| Group buy / split payment | P0 | Real conversion lift on >$50 tickets |
| Add-ons (parking, merch, etc.) | P0 | New revenue, retention lever |
| Tip the artist/venue | P0 | Trivially small build, surprisingly popular |
| Refund-to-credit option | P0 | Retention + organizer cashflow |
| Donate-back-to-venue refund | P0 | Nonprofit angle, fan loyalty |
| Ticket gifting (with note) | P0 | Holiday season conversion |
| Seat-view photos | P0 | Differentiator vs Eventbrite |
| Accessibility seat tags | P0 | Compliance + inclusion |
| Spanish-language checkout | P0 | Big conversion in many US markets |
| Friends-going (opt-in) | P1 | Social proof; privacy-sensitive, build carefully |
| Personalized recommendations | P1 | Drives discovery; needs minimum data |
| Ticket insurance (3rd party) | P1 | Booking Protect partnership; ~10% rev share |
| AR/VR seat preview | P2 | Cool but expensive and rarely used |
| Identity-verified faceprint at door | SKIP | Privacy nightmare, no venue wants it |

### Attendee-facing — payment options

| Feature | Tag | Why |
|---|---|---|
| Apple Pay / Google Pay | P0 | One-line Stripe enable; mobile conversion ++ |
| BNPL (Klarna, Affirm) | P0 | Stripe-native; useful >$50 |
| Multiple cards / wallets per user | P1 | Stripe handles |
| Bank transfer (ACH) | P2 | Slow settlement, not useful for events |
| Crypto | SKIP | Volatility + regulatory; no real demand |
| Multi-currency | P2 | Build when first non-US customer asks |

### Organizer-facing — event management

| Feature | Tag | Why |
|---|---|---|
| Multi-user org with roles | P0 | Real venues have multiple staff |
| Organizer dashboard | P0 | Self-service is the wedge |
| Custom branding (logo, colors, sender) | P0 | Looks professional, cheap |
| Custom event pages with rich content | P0 | Differentiator vs Eventbrite |
| Custom domain (CNAME) | P0 | Pro-tier feature |
| Embed widget for their website | P0 | Critical — they own customer relationship |
| Recurring events / templates | P0 | Residencies, weekly shows |
| Multi-night series / festival mode | P0 | Bundle pricing |
| Membership / season pass | P0 | Strong retention |
| Comp tickets | P0 | Industry standard |
| Hold lists (artist/press/house) | P0 | Industry standard, Eventbrite doesn't do well |
| Will-call list with print queue | P0 | Box office reality |
| Discount codes | P0 | Promo engine |
| Presale codes | P0 | Anti-bot + member benefit |
| Verified-fan registration | P0 | Anti-bot, leverages your fraud module |
| "Drop" mechanic | P0 | Pre-announced sale time builds hype |
| Lottery / drawing | P0 | Fair-access wedge — uniquely on-brand |
| Promoter / affiliate codes | P1 | Per-staff sales attribution |
| Bulk seat import (XLSX) | P1 | Already have CSV; extend |
| A/B pricing experiments | P2 | Need volume to use |
| Auto-cancel low-sales policy | P2 | Some venues want it; build on demand |
| Smart pricing recommendations | P2 | Needs data; build later |
| Mobile organizer app | P2 | PWA is enough early on |

### Organizer-facing — box office & day-of

| Feature | Tag | Why |
|---|---|---|
| Box office mode (tablet) | P0 | Big Eventbrite gap |
| Cash + card walk-up sales | P0 | Required for indie venues |
| Stripe Terminal integration | P0 | Card reader for box office |
| Will-call (search by name, mark claimed) | P0 | Industry standard |
| Scanner PWA | P0 | Required |
| Magic-link door staff login | P0 | Lowest-friction casual staff |
| Live attendance dashboard | P0 | Lets organizer breathe |
| Late-arrival list with SMS | P0 | Recover no-shows |
| Cash reconciliation report | P0 | Manager sign-off |
| Multi-device scanner sync | P0 | Multiple doors |
| Hardware printer integration | P1 | Some venues print all tickets |

### Organizer-facing — reporting & finance

| Feature | Tag | Why |
|---|---|---|
| Day-of-show (DOS) report | P0 | Industry standard |
| Settlement / payout report | P0 | Industry standard |
| Per-event sales dashboard | P0 | Live data, organizer use daily |
| Tax helper (1099-K, state sales tax) | P0 | Compliance |
| Invoice download | P0 | Stripe-hosted, easy |
| Revenue forecasting | P1 | Useful once you have data |
| Cohort analysis (repeat buyers) | P1 | Marketing lever |
| ASCAP/BMI/SESAC reporting | P2 | Niche but valued by music venues |
| Signed settlements (DocuSign) | P2 | Larger venues only |

### Organizer-facing — marketing & integrations

| Feature | Tag | Why |
|---|---|---|
| Basic email blast tool | P0 | Re-engage past attendees |
| Segmentation (past attendees, top buyers) | P0 | Useful, cheap |
| SMS notifications | P0 | Standard table stakes |
| SMS marketing blasts | P1 | Higher engagement than email |
| Webhook delivery | P0 | "Yes" answer to integration questions |
| Public REST API + API keys | P1 | Integration credibility |
| Mailchimp / Klaviyo sync | P1 | Most venues have an existing list |
| Meta Pixel / Google Tag | P1 | Their ad attribution |
| Affiliate / influencer tracking | P1 | Per-link attribution |
| Mailing list export (CSV) | P0 | Trivial; required for trust |
| Push notifications (web) | P1 | Higher engagement than email for power users |
| Native push (mobile app) | P2 | Don't build app yet |

### Trust, safety, anti-scalp

| Feature | Tag | Why |
|---|---|---|
| Rate limiting (already built) | P0 | Keep it |
| Per-user purchase caps (already built) | P0 | Keep it |
| Real-time fraud blocking | P0 | Wire existing scoring into checkout |
| Identity-locked tickets | P0 | Anti-scalp option per event |
| Geofenced QR reveal | P0 | Strong anti-screenshot |
| Mobile-only delivery | P0 | Anti-PDF-resale option |
| In-platform-only resale (face value) | P1 | Some venues want a controlled secondary |
| Chargeback dispute helper | P1 | Stripe data → form, saves time |
| ML-based fraud model | P2 | Rule-based scoring is enough early |
| Face match at door | SKIP | Privacy and bad PR |

### Operations & scale

| Feature | Tag | Why |
|---|---|---|
| Staging environment | P0 | Foundational |
| Correlation IDs in logs | P0 | Foundational |
| Coverage gates in CI | P0 | Quality lock-in |
| WCAG 2.1 AA pass | P0 | Compliance, inclusion |
| Schema.org + sitemap | P0 | Free SEO traffic |
| Image CDN | P0 | Speed |
| k6 load testing | P1 | Test before scale |
| Customer-facing status page | P1 | Trust |
| Backup & DR runbook | P1 | Required as you scale |
| Multi-region deployment | P2 | When EU customer appears |
| Full i18n | P2 | Same |

### Things SKIP for now

| Feature | Why skip |
|---|---|
| Native iOS/Android app | PWA enough; 3-mo project |
| Microservices | Monolith fine for 50x |
| Crypto / NFT tickets | No real demand, bad PR |
| Face-match at door | Privacy nightmare |
| Secondary resale market (StubHub clone) | Massive fraud + legal surface |
| Decentralized / blockchain ticketing | Solves no real problem |
| VR concerts / livestream tickets | Different business entirely |
| AI chatbot for support | Email is fine at your scale |
| Custom mobile SDKs for organizers | Webhooks + API is enough |

---

## Appendix B: Schema-impact summary

Every Flyway migration this plan implies, ordered:

| New migration | Purpose | Phase |
|---|---|---|
| V30 | Add `tickets.qr_code` + per-event `hmac_secret` on events | 3 |
| V31 | Add `tickets.scanned_at`, `tickets.scanned_by_user_id` | 3 |
| V32 | `organizations`, `organization_members`, `organization_invites` | 2 |
| V33 | Rename `events.organizer_id` → `events.organization_id`, backfill 1-person orgs | 2 |
| V34 | `tickets.kind` enum (PAID/COMP/HOLD_*), `tickets.gifted_from_user_id` | 2 + 4 |
| V35 | `wallet_passes` (ticketId, provider, providerPassId, lastUpdatedAt) | 3 |
| V36 | `discount_codes`, `discount_code_uses` | 4 |
| V37 | `presale_codes`, `verified_fan_registrations` | 4 |
| V38 | `event_lotteries`, `lottery_entries` | 4 |
| V39 | `event_add_ons`, `order_add_ons` | 4 |
| V40 | `group_orders`, `group_order_members` | 4 |
| V41 | `memberships`, `recurring_event_templates` | 4 |
| V42 | `outbound_webhooks`, `webhook_deliveries` | 6 |
| V43 | `marketing_campaigns`, `email_send_log`, `suppression_list` | 5 |
| V44 | `organizations.plan`, `organizations.ticket_credits_remaining`, `subscriptions` | 5 |
| V45 | `seats.accessibility_tags`, `seats.view_photo_url` | 5 |
| V46 | `event_favorites`, `venue_follows` | 5 |
| V47 | `tips` (orderId, beneficiary [artist/venue], amount) | 4 |
| V48 | `tax_rates_by_jurisdiction`, `event_tax_overrides` | 2 |
| V49 | Indexes: `tickets(user_id)`, `seat_holds(user_id)`, `audit_log(organization_id, created_at)` | 7 |

---

## Appendix C: "What does FairTix do that Eventbrite doesn't?" — the elevator answer

Use this whenever a venue asks. Practice saying it out loud.

> "We're built for the box office and the door, not just the online checkout. Eventbrite gives you a sales page and a payout. FairTix gives you that plus the queue tech the big arenas use to fight scalpers, settlement reports that actually tie out to an artist payout, comp and hold lists, walk-up cash sales on a tablet, Apple Wallet passes, and a real scanner app for your door staff. And we charge about a third of what Eventbrite charges you."

---

_Last updated 2026-05-12. Revisit at the end of every phase and rewrite freely — this file is a working document, not a contract._
