# Month 1 — Implementation Guide

_Companion to [STRATEGIC_ROADMAP.md](STRATEGIC_ROADMAP.md). Covers GitHub issues [#161](https://github.com/firegiant9000/FairTix/issues/161) through [#169](https://github.com/firegiant9000/FairTix/issues/169)._

---

## 0. Before you start

### Corrections to the original issue scope (based on actual code audit)

Three of the M1 issues were written from the strategic doc without reading the code. The audit turned up surprises worth flagging up front:

| Issue | Original framing | Reality |
|---|---|---|
| [#163 Correlation IDs](https://github.com/firegiant9000/FairTix/issues/163) | "Add RequestIdFilter + MDC" | [RequestLoggingFilter.java](backend/src/main/java/com/fairtix/config/RequestLoggingFilter.java) already exists and already does `MDC.put("requestId", ...)`. The real work is **propagating** the existing id into audit log rows, email headers, and Stripe metadata. |
| [#164 Cookie-auth migration](https://github.com/firegiant9000/FairTix/issues/164) | "Remove sessionStorage + Bearer remnants" | Audit found **zero** `sessionStorage` or `Authorization: Bearer` usages in [frontend/webpages/src](frontend/webpages/src/). [api/client.js](frontend/webpages/src/api/client.js) already uses `credentials: 'include'` only. This is a verification + lint-rule issue, not a migration. |
| [#161 Stripe refund execution](https://github.com/firegiant9000/FairTix/issues/161) | "Add the Stripe refund call" | [StripeWebhookController.java](backend/src/main/java/com/fairtix/payments/api/StripeWebhookController.java) **already handles `charge.refunded`** and flips refund records to COMPLETED. What's missing is the **outbound** `Refund.create()` call — the inbound webhook is wired. |

Update each issue's body or close-and-re-open with corrected scope before starting work.

### Suggested order of execution

Don't go strictly issue-by-issue. There are dependencies and grouping wins:

1. **First sprint (Week 1):** [#165 staging env](https://github.com/firegiant9000/FairTix/issues/165) — everything else benefits from having a non-prod target. Then [#166 CI coverage](https://github.com/firegiant9000/FairTix/issues/166) to lock in quality before adding code.
2. **Second sprint (Week 2):** [#161 refund execution](https://github.com/firegiant9000/FairTix/issues/161) (highest credibility win) + [#162 notification prefs](https://github.com/firegiant9000/FairTix/issues/162) (small surface, high signal) + [#163 correlation IDs](https://github.com/firegiant9000/FairTix/issues/163) (lands cleanly alongside the email work). All three touch the email/refund paths so do them together.
3. **Third sprint (Week 3):** [#164 cookie-auth verification](https://github.com/firegiant9000/FairTix/issues/164) (quick) + [#167 org/role model](https://github.com/firegiant9000/FairTix/issues/167) (the big one — start at the start of the sprint).
4. **Fourth sprint (Week 4):** Finish [#167](https://github.com/firegiant9000/FairTix/issues/167) + [#168 organizer route tree](https://github.com/firegiant9000/FairTix/issues/168) + [#169 plan-tier scaffolding](https://github.com/firegiant9000/FairTix/issues/169). The last two are visual + structural and can land in parallel.

### Parallel phase plan

The sequential cadence above assumes one developer. If multiple devs (or multiple Claude agents on isolated worktrees) work the issues concurrently, group by what touches disjoint code paths. Each phase is a "no-merge-conflict" cohort — start everything in the phase at once, gate the next phase on the prior phase landing on `develop`.

#### Phase 1 — Infrastructure & guardrails (fully parallel, no code overlap)

These touch CI config, hosting, and lint rules. Zero overlap with each other or with feature code. Spin all three up in parallel on day one.

| Issue | Surface | Why parallel-safe |
|---|---|---|
| [#165 staging env](https://github.com/firegiant9000/FairTix/issues/165) | Railway/Netlify config, DNS, secrets, `scripts/` | Touches no application code |
| [#166 CI coverage gates](https://github.com/firegiant9000/FairTix/issues/166) | `pom.xml` JaCoCo block, `.github/workflows/ci.yml`, `package.json` thresholds | Pure CI/build config |
| [#164 cookie-auth verification](https://github.com/firegiant9000/FairTix/issues/164) | New ESLint rule, new ADR, CI grep step | No runtime code changes |

**Phase gate:** staging URL reachable, CI coverage failures actually block PRs, ESLint rule live on `develop`.

#### Phase 2 — Transactional flows (parallel with light coordination)

All three touch the email/audit/Stripe seams. Coordinate by **landing #163 first** (or first within the phase) so #161 and #162 can consume the propagated `requestId` cleanly. After #163's audit-log column migration (V30) is merged, #161 and #162 are independent of each other.

| Issue | Primary surface | Coordination |
|---|---|---|
| [#163 correlation IDs](https://github.com/firegiant9000/FairTix/issues/163) | `audit_logs` V30 migration, `AuditService`, `SmtpEmailService`, `MdcTaskDecorator` | **Land first**: provides MDC plumbing the others rely on |
| [#161 Stripe refund execution](https://github.com/firegiant9000/FairTix/issues/161) | `StripePaymentService.createRefund`, `RefundService.processRefund`, `payment_records.stripe_refund_id` (V31 migration) | Independent of #162; consumes #163's MDC for Stripe metadata |
| [#162 NotificationPreference gating](https://github.com/firegiant9000/FairTix/issues/162) | New `NotificationGate` service, `EmailService` overload, 16 send-site call updates | Independent of #161; consumes #163's MDC for suppression audit lines |

**Merge order:** #163 → (#161 ∥ #162). Use distinct Flyway version numbers (#163 = V30, #161 = V31). If #161 lands first, renumber.

**Phase gate:** test refund issues real money in staging, opt-out actually suppresses non-transactional mail, single request id appears in audit log + email header + Stripe metadata.

#### Phase 3 — Organizations foundation (single critical path)

[#167 org/role model](https://github.com/firegiant9000/FairTix/issues/167) is the only issue in this phase. It's the longest single piece of work (~15–20 hours) and it blocks Phase 4 entirely. Don't try to parallelize within it — the schema migrations (V32 + V33 + V34), entity layer, and ACL interceptor must land as one coherent unit. Use the buffer to also have a second dev start the visual/scaffolding work for Phase 4 against stub data.

**Phase gate:** two-user, two-org permission acceptance test passes end-to-end on staging; `events.organization_id` backfilled; `Role.ADMIN` still bypasses org ACL cleanly.

#### Phase 4 — Organizer surface & plan scaffolding (fully parallel, both depend on Phase 3)

Once #167 is on `develop`, these two land in parallel — one frontend-heavy, one a thin backend migration with a stub enforcer.

| Issue | Surface | Why parallel-safe |
|---|---|---|
| [#168 `/organizer` route tree](https://github.com/firegiant9000/FairTix/issues/168) | `OrganizerLayout.js`, `OrganizerRoute`, ~9 new routes, `useOrganization()` hook | Frontend-only; consumes existing org APIs from #167 |
| [#169 plan tier scaffolding](https://github.com/firegiant9000/FairTix/issues/169) | V35 migration on `organizations`, `Plan` enum, `PlanEnforcementService` stub, scheduled reset stub | Backend-only; touches `organizations` columns only (no row writes that would race #168) |

**Phase gate:** new org owner signs up → lands on `/organizer` → sees dashboard widgets; migration V35 deployed; `PlanEnforcementService.checkCanIssueTicket()` returns true unconditionally as designed.

#### Worktree hygiene for parallel execution

If running multiple agents simultaneously, isolate with `git worktree add` per issue branch. The high-collision file pairs to watch:

- `backend/src/main/resources/db/migration/V*.sql` — Flyway version numbers collide silently. Reserve a range per phase up front (#163=V30, #161=V31, #167=V32–V34, #169=V35).
- `backend/pom.xml` — #166 adds JaCoCo; any other backend change touching plugins will conflict. Land #166 first or rebase.
- `frontend/webpages/src/api/client.js` — touched by #163 (request id header) and #164 (lint rule). Coordinate via #164 landing first as it's lint-only.
- `.github/workflows/ci.yml` — touched by #165 (staging deploy step) and #166 (coverage step). Land #166 first; #165 rebases.

### Branch strategy

Each issue gets a branch named `feat/m1-<issue-number>-<slug>`. Open PRs into `develop`. After CI passes and you self-review, merge to `develop` → auto-deploy to staging → smoke test → cherry-pick or fast-forward to `main` when ready for prod.

Don't sit on long-lived branches. Rebase daily if a branch lives more than 3 days.

### Solo-dev cadence reminder

15–20 focused hours / week. M1 is 4 weeks = ~70 hours total. The plan below assumes ~7–10 hours of engineering per issue, with [#167](https://github.com/firegiant9000/FairTix/issues/167) being a clear outlier at ~15–20 hours.

---

## Issue #161 — Wire Stripe refund API call

### What's actually broken

Today: an admin clicks "Approve" on a refund. [RefundService.reviewRefund()](backend/src/main/java/com/fairtix/refunds/application/RefundService.java) at L155–180 flips the status to APPROVED, then [processRefund()](backend/src/main/java/com/fairtix/refunds/application/RefundService.java) at L182–218 sets the order to REFUNDED, marks tickets REFUNDED, releases seats, and emails the user "your refund is complete."

**Nowhere in that flow is Stripe called.** The user receives a "refund complete" email but no money. The first time this happens with a real customer, you lose the customer and probably get a chargeback that costs more than the refund.

### Files to touch

- [RefundService.java](backend/src/main/java/com/fairtix/refunds/application/RefundService.java) — add the Stripe call
- [StripePaymentService.java](backend/src/main/java/com/fairtix/payments/application/StripePaymentService.java) — add `createRefund(paymentIntentId, amountCents)` method
- [StripeWebhookController.java](backend/src/main/java/com/fairtix/payments/api/StripeWebhookController.java) — already handles `charge.refunded` but verify the linkage works with the new outbound call
- [PaymentRecord](backend/src/main/java/com/fairtix/payments/domain/PaymentRecord.java) — confirm refund records can store the Stripe refund id (may need a column)
- New Flyway migration if `payment_records` doesn't have a `stripe_refund_id` column

### Step-by-step

1. **Add the Stripe call.** In `StripePaymentService`, add:
   ```java
   public Refund createRefund(String paymentIntentId, long amountCents, String reason) {
       Map<String, Object> params = new HashMap<>();
       params.put("payment_intent", paymentIntentId);
       params.put("amount", amountCents);
       params.put("metadata", Map.of("reason", reason, "requestId", MDC.get("requestId")));
       // For Stripe Connect orders, set reverse_transfer=true (this lands in M2 #170)
       return Refund.create(params);
   }
   ```
2. **Wire it into `processRefund()`.** Before flipping the DB state, call `stripePaymentService.createRefund(...)`. Wrap in try/catch — if Stripe throws, the entire refund must roll back to APPROVED (or to a new state like REFUND_FAILED). Do **not** mark the order REFUNDED if the Stripe call failed.
3. **Confirm the webhook closes the loop.** The existing `charge.refunded` handler at L103–119 of `StripeWebhookController` finds the refund by PaymentIntent ID and marks it COMPLETED. Make sure the lookup still works when you store the new `stripe_refund_id`.
4. **Add idempotency.** If `RefundRequest.stripeRefundId` is non-null, do not call Stripe again — return the existing refund. Prevents double refunds on retry.
5. **Test in staging.** Use a Stripe test card that supports refunds (`4242 4242 4242 4242`). End-to-end: place order → admin approves refund → check Stripe dashboard shows the refund → check user receives money on test card.

### Potential issues / gotchas

- **Refund amount mismatch.** Today `RefundService` stores `refundAmount`; make sure it matches the original PaymentIntent amount (Stripe will reject otherwise unless you use partial refund).
- **Stripe Connect.** If the original PaymentIntent was created with `application_fee_amount` (lands in M2), refunds must use `reverse_transfer: true` and may need `refund_application_fee: true`. Until M2 lands, you're refunding from your own balance — fine for now, but note it.
- **Race with the webhook.** `Refund.create()` returns synchronously with status `pending` or `succeeded`. The `charge.refunded` webhook will arrive *separately* with the final status. Don't email the user "refund complete" until the webhook fires; instead email "refund initiated."
- **Currency.** Stripe takes amounts in the smallest currency unit (cents for USD). Make sure your stored `refundAmount` is in dollars and you multiply by 100.
- **Auto-approved refunds under $50.** [RefundService](backend/src/main/java/com/fairtix/refunds/application/RefundService.java) auto-approves these. Verify auto-approval path also calls Stripe.
- **Refund window.** Stripe allows refunds up to 180 days after the original charge. Add a guard in `RefundService` that rejects approval if the order is older than 180 days, with a helpful error.

### Extras worth bundling in (small, on-theme)

- **Refund initiated email template** (separate from refund completed). Use the audit timeline: "Your refund was approved on X, initiated on Y, completed on Z."
- **Refund status visibility for users.** Currently `/refunds` shows admin's state — add a clearer banner that distinguishes "Stripe is processing" vs "Money is in your account."
- **Slack webhook for refund failures.** A failed Stripe refund is rare but high-severity; a one-line Slack/Discord webhook beats checking logs.
- **Configurable platform-fee retention.** Stripe Connect lets you choose whether the platform fee is refunded or not on partial refunds. Pick a policy now (recommend: refund proportional fee) and document it.

### Acceptance test

In staging: admin approves a refund for a $25 test order → Stripe dashboard shows a $25 refund → test card receives the refund (Stripe test mode simulates this) → DB row has `stripe_refund_id` populated → user receives "refund initiated" email immediately and "refund complete" email after webhook fires.

---

## Issue #162 — Enforce NotificationPreference at every email send site

### Current state

The `NotificationPreference` entity at [domain/NotificationPreference.java](backend/src/main/java/com/fairtix/notifications/domain/NotificationPreference.java) has 5 boolean columns: `email_order`, `email_ticket`, `email_hold`, `email_marketing`, `email_support`. Users can toggle these via [GET/POST /api/notifications/preferences](backend/src/main/java/com/fairtix/notifications/application/NotificationPreferenceService.java).

**None of the 16 email send sites consult these preferences.** Opting out does nothing.

### The 16 send sites

| # | File | Line | Category |
|---|---|---|---|
| 1 | [EventService.java](backend/src/main/java/com/fairtix/events/application/EventService.java) | 196 | event cancelled (transactional — do not gate) |
| 2 | [EmailVerificationService.java](backend/src/main/java/com/fairtix/auth/application/EmailVerificationService.java) | 62 | account verification (security — never gate) |
| 3 | [HoldExpirationScheduler.java](backend/src/main/java/com/fairtix/inventory/scheduler/HoldExpirationScheduler.java) | 129 | hold expiring (category: email_hold) |
| 4 | [TransferService.java](backend/src/main/java/com/fairtix/tickets/application/TransferService.java) | 114 | transfer requested (email_ticket) |
| 5 | TransferService | 152 | transfer accepted (email_ticket) |
| 6 | TransferService | 178 | transfer rejected (email_ticket) |
| 7 | TransferService | 253 | transfer expired (email_ticket) |
| 8 | [QueueAdmissionScheduler.java](backend/src/main/java/com/fairtix/queue/scheduler/QueueAdmissionScheduler.java) | 76 | queue admitted (transactional — do not gate; user is mid-purchase) |
| 9 | [PasswordResetService.java](backend/src/main/java/com/fairtix/auth/application/PasswordResetService.java) | 103 | password reset (security — never gate) |
| 10 | [OrderService.java](backend/src/main/java/com/fairtix/orders/application/OrderService.java) | 303 | order confirmation (email_order) |
| 11 | [RefundService.java](backend/src/main/java/com/fairtix/refunds/application/RefundService.java) | 280 | refund requested (email_order) |
| 12 | RefundService | 292 | refund completed (email_order) |
| 13 | RefundService | 304 | refund rejected (email_order) |
| 14 | [SupportTicketService.java](backend/src/main/java/com/fairtix/support/application/SupportTicketService.java) | 80 | support received (email_support) |
| 15 | SupportTicketService | 130 | support reply (email_support) |
| 16 | SupportTicketService | 188 | support closed (email_support) |

### Recommended approach: a gate helper, not 16 if-statements

Add a `NotificationGate` service:

```java
@Service
public class NotificationGate {
    private final NotificationPreferenceService prefs;

    public boolean shouldSend(UUID userId, Category category) {
        if (category.isTransactional()) return true;  // security + cancellations always send
        return prefs.getPreferences(userId).isEnabledFor(category);
    }
}
```

Update `EmailService.sendEmail(...)` to accept the gate context, or — cleaner — wrap each call site with `if (gate.shouldSend(userId, EMAIL_ORDER)) emailService.sendEmail(...)`.

Cleanest pattern: add an overload `EmailService.sendEmail(userId, category, to, subject, body)` that internally consults the gate. Then all call sites stay 1 line and the gate logic lives in one place.

### Potential issues / gotchas

- **Don't gate security mail.** Password reset, email verification, queue admission, payment receipts (legal requirement in some jurisdictions). Code a `Category.isTransactional()` flag for the bypass.
- **Default values for existing users.** Existing rows have whatever V8 set as defaults. Verify what they default to — if everything defaults to `true` you're fine; if anything defaults to `false`, existing users will silently stop getting mail when this lands. **Audit V8 before merging.**
- **What about the SMS channel?** The Notification Preference table is email-only today. M5/M6 adds SMS. Make the `Category` enum future-proof: each category should answer "is email opted in?" and "is SMS opted in?" separately, not a single flag.
- **Audit log it.** When a mail is suppressed by preference, write an info log line with `requestId`, `userId`, `category`. This is the only way you'll be able to debug "I didn't get the email" support tickets without storing the email content.
- **Don't break unsubscribe.** Add a one-click unsubscribe link to every marketing email (CAN-SPAM compliance, and Gmail/Yahoo now require it for bulk senders).

### Extras worth bundling in

- **One-click unsubscribe links** in every marketing email — signed token in URL, hits `/api/notifications/unsubscribe?token=...` and toggles the flag. Big trust signal and a compliance requirement.
- **Preferences page UX upgrade.** The current screen shows raw boolean toggles. Add a description for each category and an "all marketing" master switch.
- **Email frequency cap.** Even with prefs respected, no user should get more than N emails per hour from the system (prevents pathological loop bugs from spamming users). One Redis counter per user per hour.
- **Mail send audit table.** Capture every send attempt: userId, category, suppressed (bool), subject, requestId. Small table, huge support-debugging payoff. (Becomes the foundation for the M5 email-marketing tool.)

### Acceptance

Unit test per category: opt out → call the send site → verify no email fired. Opt back in → verify it does. Manual test: opt out of marketing, send yourself a refund request — refund email arrives (correctly), then a marketing blast — it does not.

---

## Issue #163 — Correlation IDs (already half-built)

### Current state

[RequestLoggingFilter.java](backend/src/main/java/com/fairtix/config/RequestLoggingFilter.java) already runs `MDC.put("requestId", UUID.randomUUID().toString())` at line 27–29 and logs the request/response with duration. The log format presumably includes `%X{requestId}` somewhere (verify in `logback-spring.xml` or `application.properties`).

What's missing:

1. The request id is **not propagated to audit log rows** — V5 schema has no `request_id` column.
2. The id is **not in outbound email headers** — support can't correlate "I didn't get the order email" to a server log.
3. The id is **not in Stripe metadata** — when a refund or chargeback comes back, you can't trace it to the originating request.

### Step-by-step

1. **New Flyway migration V30:**
   ```sql
   ALTER TABLE audit_logs ADD COLUMN request_id VARCHAR(36);
   CREATE INDEX idx_audit_logs_request_id ON audit_logs(request_id);
   ```
2. **AuditService writes MDC.get("requestId")** into the new column on every audit row. Use the existing `REQUIRES_NEW` propagation pattern.
3. **Email send helper adds `X-Request-Id` header.** Modify `SmtpEmailService.sendEmail()` to add the MDC value as a SMTP header.
4. **Stripe metadata.** In `StripePaymentService.createPaymentIntent()` and the new `createRefund()`, add `params.put("metadata", Map.of("requestId", MDC.get("requestId")))`. Stripe surfaces this in the dashboard and webhooks.
5. **Cross-thread propagation.** Anywhere a request handler spawns an async task (`@Async`, `CompletableFuture`, the schedulers), the MDC context is lost. Wrap with `MdcTaskDecorator` on the Spring `TaskExecutor`. Scheduler-initiated mail (queue admission, hold expiry) doesn't have a request id — use `"sched-<schedulerName>-<uuid>"` instead.

### Potential issues / gotchas

- **MDC + virtual threads (Java 21).** If you ever switch to virtual threads (you're on Spring Boot 4 + Java 21, so it's tempting), MDC behavior changes. Stick with platform threads for now; revisit if perf demands it.
- **Log format must include the id.** Check `application.properties` for `logging.pattern.console`. If `%X{requestId}` isn't there, the id is in MDC but never appears in logs. Common oversight.
- **The schedulers run with no request id.** Inject a synthetic id at the top of each `@Scheduled` method so downstream audit logs and emails are still correlatable.
- **Tests.** Existing tests probably don't set MDC — make sure the new audit column accepts NULL or your unit tests will fail.

### Extras worth bundling in

- **Distributed tracing setup (OpenTelemetry).** Spring Boot 4 has first-class OTel support. If you set up tracing now, every log line, every Stripe call, every SQL query gets a trace id linking them. Small effort, massive observability win. Recommend [Tempo](https://grafana.com/oss/tempo/) on Grafana Cloud's free tier.
- **`/api/admin/audit` UI filter by requestId.** One field on the existing admin audit page; lets support pull every action across modules tied to a single user click.
- **Surface the requestId on error pages.** Frontend shows it on 500 / 404 / unexpected error screens: "If you contact support, include this id: abc-def." Compresses support cycle time dramatically.
- **Forward `X-Request-Id` from the client if present.** Browser sends a UUID → server uses it instead of generating one. Lets the frontend tie its own logs to backend logs. Pattern: respect the header if shaped like a UUID, otherwise generate.

### Acceptance

Place an order. Find the order id. Search `audit_logs` for that order — every row from that single user action shares the same `request_id`. Find the corresponding line in `kubectl logs` (or Railway logs). Pull up the Stripe dashboard for the PaymentIntent — same id in metadata.

---

## Issue #164 — Cookie-auth verification (already done)

### Reality check

The audit found **zero** `sessionStorage` usages for auth and **zero** `Authorization: Bearer` headers in [frontend/webpages/src](frontend/webpages/src/). [api/client.js](frontend/webpages/src/api/client.js) uses `credentials: 'include'` exclusively. [tokenUtils.js](frontend/webpages/src/auth/tokenUtils.js) hits `/auth/me` with cookies.

The migration is complete. The remaining work is **preventing regression**, not migrating.

### Step-by-step (re-scoped)

1. **Grep one more time to be absolutely sure.** `grep -ri "sessionStorage\|Bearer " frontend/webpages/src` — confirm clean.
2. **Add an ESLint rule** banning `sessionStorage` and `Authorization.*Bearer` in the frontend. Use `no-restricted-syntax` or `no-restricted-globals`:
   ```js
   "no-restricted-globals": ["error", { "name": "sessionStorage", "message": "Use HTTP-only cookie auth via /auth/me. See api/client.js." }]
   ```
3. **Add an ADR (architecture decision record)** at [docs/adr/0001-cookie-auth.md](docs/adr/0001-cookie-auth.md) documenting the choice. Future-you and any contractor needs context.
4. **Add a CI grep step** that fails the build if those tokens reappear: `grep -ri "sessionStorage" frontend/webpages/src || true` with a check on result count.

### Potential issues / gotchas

- **`localStorage` is fine for non-auth state** (saved seat picker preferences, last-viewed venue). Don't accidentally ban that — only ban for auth use.
- **CSRF.** Cookie auth without CSRF protection is a vulnerability. Verify [SecurityConfig](backend/src/main/java/com/fairtix/config/SecurityConfig.java) has CSRF enabled (or document why it's disabled and what mitigation is in place — typically SameSite=Strict on the cookie + custom header check).
- **SameSite + cross-subdomain.** If staging is `staging.fairtix.io` and prod is `fairtix.io`, the cookie scope matters. Document it.

### Extras worth bundling in

- **Session expiry banner UX.** The `auth:session-expired` custom event in `api/client.js` fires when the cookie expires. The frontend should show a clean "your session expired, please log in" banner with a one-click re-auth path, not just a 401 modal.
- **Sliding session refresh.** If the user is active, the access cookie should refresh in the background before the 15-min expiry hits. Today [api/client.js](frontend/webpages/src/api/client.js) auto-refreshes on 401, which means the user gets a stutter on the failed request. A proactive refresh at T-2min from expiry eliminates the stutter.
- **"Logout everywhere" button.** Revokes all refresh tokens for the user. The `refresh_tokens` table from V13 already supports this — needs a UI.
- **Audit "session started from new device" emails.** When a refresh token is issued from a new IP/UA combo, email the user. Standard practice; uses existing audit infra.

### Acceptance

CI fails if someone re-introduces sessionStorage. ADR is committed. Manual: open DevTools → Application → Cookies on a logged-in session, confirm `fairtix_token` and `fairtix_refresh` are present with `HttpOnly`, `Secure`, `SameSite=Strict` (or `Lax`).

---

## Issue #165 — Stand up staging environment on Railway

### Why first

Every other M1 issue is safer to ship with a staging target. The refund work (#161) is dangerous to verify directly in prod. Do this in week 1.

### Step-by-step

1. **Create `develop` branch.** Default branch stays `main` for prod. Devs merge to `develop`, which auto-deploys to staging. Cherry-pick or fast-forward `main` from `develop` when ready for prod.
2. **Railway: duplicate the prod service.** Use Railway's preview environments OR create a separate service named `fairtix-staging`.
3. **Separate Postgres.** Either a Railway-managed Postgres pinned to staging, or a Neon free-tier DB pointed at by the staging service. Critically: **do not point staging at prod DB.**
4. **Separate Redis.** Free Railway Redis or Upstash free tier.
5. **Stripe test keys.** Set `STRIPE_SECRET_KEY` to the test key in staging. Webhook endpoint configured in Stripe to point at `staging.fairtix.io/api/payments/stripe/webhook`.
6. **Subdomain.** `staging.fairtix.io` CNAME → Railway provided URL. SSL via Railway.
7. **Frontend (Netlify).** Add a Netlify environment per branch. `develop` branch builds with `REACT_APP_API_BASE_URL=https://staging-api.fairtix.io` and deploys to `staging.fairtix.io`.
8. **Cookie domain.** `SameSite=None; Secure` only works cross-site if the cookie is set on the right domain. Either run frontend + backend on same apex domain (`staging.fairtix.io` for both, with `/api` reverse-proxied) or set up the CORS + cookie scope carefully.
9. **Mailhog or Mailtrap for staging email.** Don't use SMTP that sends real mail in staging — point at Mailtrap free tier so dev emails are quarantined.
10. **Seed data.** Reuse `scripts/demo-seed.sh` on first boot.

### Potential issues / gotchas

- **Flyway running on a fresh DB.** All 29 migrations will run on first staging deploy; takes a minute, watch for any that assume data exists.
- **Stripe webhook secret differs per env.** `STRIPE_WEBHOOK_SECRET` must be the staging webhook's secret, not prod's. Mixing these silently breaks webhooks.
- **SameSite cookies.** Cross-subdomain (api.staging.fairtix.io ↔ staging.fairtix.io) needs cookie domain set to `.fairtix.io` and `SameSite=None` + `Secure`. Easier path: same subdomain for both via reverse proxy.
- **Railway resource limits.** Free tier hits 500hr/month per service. Sleep-when-idle config helps. Monitor or upgrade to hobby ($5/mo).
- **Costs.** Plan to spend ~$15/mo on staging (Railway hobby + Neon free + Stripe test free + Mailtrap free + Netlify free). Budget this.

### Extras worth bundling in

- **A "reset staging" script.** Wipes the staging DB and re-runs seeds. Invaluable when staging gets into weird states.
- **Daily prod → staging DB anonymized restore.** Most useful long-term thing you can build. PG dump of prod → strip PII (emails, names, payment refs) → restore to staging. Means staging actually looks like prod and the bugs you reproduce there are real.
- **Per-PR preview environments.** Railway supports these. Each PR gets a temporary URL with its own DB. Excellent for sharing demos before merge.
- **A `/_health/deep` endpoint** that checks Stripe connectivity, Redis ping, DB roundtrip, mail server reachable. Surface in the status page later (M6).
- **Feature flags.** Even simple env-var flags. Means staging can have things prod doesn't (e.g., new refund flow) without separate branches.

### Acceptance

`git push origin develop` → 2 minutes later, `staging.fairtix.io` reflects the change. Stripe test-card payment works end-to-end. Logs accessible via `railway logs`.

---

## Issue #166 — Add JaCoCo + Jest coverage gates

### Current state

- Backend: only `maven-surefire-plugin` configured. **No JaCoCo.**
- Frontend: thresholds set in `package.json` at branches 30 / functions 35 / lines 40 / statements 40.
- `.github/workflows/ci.yml` runs `mvn clean verify` and `npm test -- --coverage --watchAll=false`. Neither fails on coverage today.

### Step-by-step

1. **Add JaCoCo to [backend/pom.xml](backend/pom.xml).** Plugin config with `prepare-agent` + `report` + `check` goals. Start the `check` rule at the **current measured coverage**, not aspirationally — get a baseline first.
2. **Measure baseline.** Run `mvn clean verify` locally, open `target/site/jacoco/index.html`, read the overall line/branch coverage. That's your floor.
3. **Set the gate.** In the `<check>` rule, fail under `current_coverage - 1%`. Don't try to immediately raise it; lock the floor.
4. **Frontend gate already exists.** Verify it fails the CI step — `npm test -- --coverage --watchAll=false --passWithNoTests` should exit non-zero when thresholds are breached.
5. **Wire both into the CI `test` job.** Already there; ensure failure propagates (no `continue-on-error: true`).
6. **Publish reports as artifacts.** GitHub Actions: `actions/upload-artifact@v4` with the `target/site/jacoco/` and `frontend/webpages/coverage/` directories. Lets you click into the report from the PR check.

### Potential issues / gotchas

- **Generated code (Lombok, DTOs).** Exclude from JaCoCo to avoid false floor: `<excludes><exclude>**/dto/**</exclude><exclude>**/domain/**Generated*</exclude></excludes>`.
- **Integration tests.** `mvn verify` runs integration tests; `mvn test` runs unit only. Keep `verify` in CI.
- **Test execution time.** As coverage tests grow, CI gets slower. Set a hard limit; if `verify` exceeds 8 minutes, start parallelizing.
- **`@SpringBootTest` overhead.** Each Spring context boot is 5–10s. Reuse contexts where possible.

### Extras worth bundling in

- **Coverage diff comment on PRs.** A bot that reports "this PR changes coverage by -0.4%". [Codecov](https://about.codecov.io/) free for open source, ~$0 for private if you stay light.
- **Mutation testing with Pitest** for critical modules (seat-hold, refund, fraud). Run nightly, not per-PR — too slow. Catches the kinds of bugs coverage misses.
- **Snapshot tests in frontend for critical UI** (checkout, seat picker). Cheap to add.
- **A `coverage-required` list.** A file listing modules where coverage must be ≥80% (seat-hold, payments, refunds, fraud). Different rule than the global floor.
- **Test naming convention enforcement.** Use [ArchUnit](https://www.archunit.org/) to enforce that every `@Service` class has a corresponding `*Test` class. Catches "I forgot to write any tests for this."

### Acceptance

Open a PR that removes a test. CI fails with a clear coverage-regression message. Add the test back; CI passes.

---

## Issue #167 — Organization + role model with ACL middleware

### The biggest single issue in M1

Budget 15–20 hours. This unblocks M2 entirely and is the foundation for everything organizer-related.

### Current state

- [Role.java](backend/src/main/java/com/fairtix/users/domain/Role.java) has exactly two values: `USER`, `ADMIN`.
- Ownership today: [Event.organizerId](backend/src/main/java/com/fairtix/events/domain/Event.java) at L44, checked by [EventService.verifyOwnership()](backend/src/main/java/com/fairtix/events/application/EventService.java) at L269.
- Admin enforcement: `@PreAuthorize("hasRole('ADMIN')")` scattered across controllers; `SecurityConfig` line 75 has `/api/admin/**` → `hasRole("ADMIN")`.
- **No `organizations` table.** Venues exist (V14) with `organizer_id`, but that's not the same concept.

### Step-by-step

1. **New Flyway migration V30:**
   ```sql
   CREATE TABLE organizations (
     id UUID PRIMARY KEY,
     name VARCHAR(255) NOT NULL,
     slug VARCHAR(100) NOT NULL UNIQUE,
     contact_email VARCHAR(255),
     status VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING, ACTIVE, SUSPENDED
     created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
   );
   CREATE TABLE organization_members (
     id UUID PRIMARY KEY,
     organization_id UUID NOT NULL REFERENCES organizations(id),
     user_id UUID NOT NULL REFERENCES users(id),
     role VARCHAR(32) NOT NULL,  -- OWNER, MANAGER, BOX_OFFICE, DOOR, MARKETING, ACCOUNTANT
     created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
     UNIQUE(organization_id, user_id)
   );
   CREATE TABLE organization_invites (
     id UUID PRIMARY KEY,
     organization_id UUID NOT NULL REFERENCES organizations(id),
     email VARCHAR(255) NOT NULL,
     role VARCHAR(32) NOT NULL,
     token VARCHAR(64) NOT NULL UNIQUE,
     expires_at TIMESTAMPTZ NOT NULL,
     accepted_at TIMESTAMPTZ
   );
   ```
2. **Backfill migration V31:** for each existing distinct `organizer_id` on `events`, create an `organizations` row + an `organization_members` row with role `OWNER`.
3. **Schema migration V32:** `ALTER TABLE events ADD COLUMN organization_id UUID REFERENCES organizations(id);` Backfill `events.organization_id` from `events.organizer_id` → new org. Keep `organizer_id` column for now; remove in M2 after all code is migrated.
4. **JPA entities:** `Organization`, `OrganizationMember`, `OrganizationInvite`, `OrgRole` enum. Add to a new `organizations` module: `backend/src/main/java/com/fairtix/organizations/`.
5. **Permission set per role:**
   ```java
   public enum OrgRole {
       OWNER(Set.of(ALL)),
       MANAGER(Set.of(EVENTS_WRITE, SALES_READ, REFUNDS_WRITE, COMPS_WRITE, TEAM_READ)),
       BOX_OFFICE(Set.of(EVENTS_READ, SALES_READ, COMPS_WRITE, BOX_OFFICE_SELL)),
       DOOR(Set.of(SCANNER_USE)),
       MARKETING(Set.of(EVENTS_READ, ATTENDEES_READ, EMAIL_SEND)),
       ACCOUNTANT(Set.of(SALES_READ, REFUNDS_READ, PAYOUTS_READ, REPORTS_READ));
   }
   ```
6. **`@OrgScoped` annotation + interceptor.** Annotation marks controller methods that take a resource id; interceptor extracts the resource → looks up its org → checks the requester is a member with the required permission.
7. **Refactor `EventService.verifyOwnership()`** to consult org membership instead of `organizerId` direct match.
8. **Keep `Role.ADMIN` separate from org roles.** ADMIN is a platform-level role (you, the FairTix operator). It bypasses org ACL. Org roles are scoped per organization.

### Potential issues / gotchas

- **Backfill ambiguity.** Some existing events might have `organizer_id = NULL`. Decide policy: orphan them under a "Legacy" org, or hard-block the migration on non-null check.
- **A user can be in many orgs.** A booking agent might work for 3 venues. Make sure the UI surfaces an org switcher.
- **N+1 risk on ACL checks.** Every request to `/organizer/...` does a member lookup. Cache the member set per request in MDC.
- **OWNER must be invariant.** Always at least one OWNER per org. Block deletion of the last owner with a friendly error.
- **`organization_invites` security.** Token must be one-time-use, expires in 7 days, single-purpose. Use the same token generator as password resets.
- **Soft delete an org.** Don't hard-delete — venues with sold tickets need to retain history. Status SUSPENDED handles this.
- **Test coverage.** This is a security boundary; aim for >85% coverage on the org module specifically.

### Extras worth bundling in

- **Slug generator with collision handling.** `blue-note`, `blue-note-2`, etc. Critical for clean public URLs from day one.
- **Org-level audit trail.** Every action (invite sent, member promoted, member removed) goes in audit log with the org id. Becomes visible to org owners in M2.
- **Org-scoped rate limits.** Different rate-limit buckets per org, not just per user. Prevents one runaway org from impacting the platform.
- **An `OrgContext` thread-local** that holds the current resolved org id. Cleaner than passing `orgId` through every service method.
- **Platform-admin "impersonate org" capability.** You log in as platform admin → enter org context "as if you were OWNER of Blue Note" → can debug their issues from inside their view. Big support win. Log every impersonation aggressively.
- **A "transfer ownership" workflow.** Owner-to-owner transfer with 7-day cooling-off + email confirmation. Don't have to ship today but design the schema for it.

### Acceptance

Sign up two users. Create org A; user 1 is owner. Invite user 2 as MANAGER. User 2 accepts. User 2 can list/edit events in org A but not in org B. User 1 demotes user 2 to ACCOUNTANT; user 2 can no longer edit events. Audit log shows every step.

---

## Issue #168 — `/organizer` route tree skeleton

### Current state

App.js at L51–111 has public, authenticated, and admin route trees. No `/organizer`.

### Step-by-step

1. **New layout component:** `OrganizerLayout.js` mirroring `AdminLayout.js` — sidebar with the planned routes, header showing the current org with a dropdown to switch orgs.
2. **New protected-route wrapper:** `OrganizerRoute` checks the user has at least one org membership; redirects to `/onboarding/create-org` if not.
3. **Routes (within `OrganizerLayout`):**
   - `/organizer` → dashboard
   - `/organizer/events` → events list
   - `/organizer/events/:eventId` → event detail (sales, attendees, holds, comps, scan-progress)
   - `/organizer/events/new` → create wizard
   - `/organizer/sales` → cross-event sales
   - `/organizer/payouts` → Stripe Connect status, payout history
   - `/organizer/settings` → org details, branding
   - `/organizer/team` → members + invites
   - `/organizer/integrations` → API keys, webhooks (skeletons; wired in M6)
4. **Reuse admin components** where possible (event form, seat editor, refund queue) with the ACL guard checking org membership instead of admin role.
5. **Dashboard widgets (data wiring can be stubbed):**
   - Today's shows
   - This week's revenue
   - Refund queue depth
   - Recent sales feed
   - Top events by velocity
6. **Org switcher** in header — only shown if user belongs to >1 org. Stores selected org in localStorage (note: this is *not* auth-sensitive state, localStorage is fine).
7. **Empty states** for every page. Real venues hate hitting a blank screen.

### Potential issues / gotchas

- **The admin Material-UI components may not match a public-facing organizer aesthetic.** Spend a small effort tightening the visual design now; first impressions with venue owners matter.
- **Org context propagation in the frontend.** Use React context or a Zustand store. Don't put it in URL params; users will email each other links.
- **`useOrganization()` hook.** Returns the currently selected org. Every API call from the organizer panel should send `X-Organization-Id` header so the backend can verify scope.
- **Loading states.** The dashboard hits 5+ queries. Show skeleton states; don't block on all of them.

### Extras worth bundling in

- **Onboarding checklist widget.** "Welcome to FairTix! 3 of 7 done." Items: complete Stripe Connect, create first event, invite team member, customize branding, upload logo, etc. Drives engagement and feature adoption.
- **"Try it with a demo event" button** that creates a sandbox event so they can see the full flow before committing real data.
- **In-app tour** (intro.js or react-joyride) for first-time organizers. Annoying if overused; valuable on first visit.
- **Recent activity feed** on the dashboard — pulls from the new audit log, scoped to the org. Shows "Sarah issued 4 comps to Blue Note 8/12" etc.
- **Quick actions sidebar widget:** New event, Issue comp, View tonight's show, Email attendees.
- **A "Run a show tonight" big button** for box-office venues. Takes them straight to box-office mode (M2) for the next upcoming event.

### Acceptance

Sign up as a new org owner → land on `/organizer` → see a dashboard with empty-state widgets and clear next-steps. Switch to a different role (manager) → see the same view but with restricted actions hidden.

---

## Issue #169 — Plan tier scaffolding

### Why now and not in M5

Adding the columns now means M5 billing has a place to land without reshaping the `organizations` table after data is in it. Costs ~2 hours to scaffold and saves a painful migration later.

### Step-by-step

1. **New Flyway migration V33:**
   ```sql
   ALTER TABLE organizations ADD COLUMN plan VARCHAR(32) NOT NULL DEFAULT 'FREE';
   ALTER TABLE organizations ADD COLUMN ticket_credits_remaining INT;
   ALTER TABLE organizations ADD COLUMN ticket_credits_reset_at TIMESTAMPTZ;
   ALTER TABLE organizations ADD COLUMN stripe_customer_id VARCHAR(64);
   ALTER TABLE organizations ADD COLUMN stripe_subscription_id VARCHAR(64);
   ```
2. **`Plan` enum** with monthly ticket caps:
   ```java
   public enum Plan {
       FREE(200, new BigDecimal("0.025")),
       PRO(null, new BigDecimal("0.015")),    // unlimited tickets
       SCALE(null, new BigDecimal("0.010")),
       ENTERPRISE(null, null);                 // custom
   }
   ```
3. **A `PlanEnforcementService`** with one method: `checkCanIssueTicket(orgId)`. Called from [TicketService.issueTickets()](backend/src/main/java/com/fairtix/tickets/application/TicketService.java) at L25–37.
4. **Default behavior:** all existing orgs default to FREE with `ticket_credits_remaining = NULL` interpreted as unlimited (until the M5 billing issue caps them). Documented in the enum.
5. **Stub the reset job.** A `@Scheduled` method that runs daily and on the 1st of each month resets `ticket_credits_remaining` per plan. Wire it but it's a no-op until M5 enforces the cap.

### Potential issues / gotchas

- **Don't actually enforce the cap yet.** M5 will turn it on. Today the check returns true unconditionally — just lays the wiring. If you enforce now, existing free-tier orgs (which is all of them, since none have paid) start hitting limits.
- **Tickets-per-month vs all-time.** Decide now: caps are monthly. Document it. (Otherwise an org that runs one big concert burns its credits forever.)
- **Stripe Customer vs Stripe Connect Account.** Two different objects. `stripe_customer_id` is the org's billing customer (who pays you). `stripe_account_id` (in M2) is who you pay. Don't conflate.
- **Comp tickets.** Should comps count against the credit limit? Recommend: no — comps are free for both attendee and platform, mostly. Document the policy.

### Extras worth bundling in

- **An admin view of plan distribution.** "FREE: 12 orgs, PRO: 3, SCALE: 1." Pulls into the analytics dashboard.
- **Plan-change audit trail.** When you (admin) bump an org from FREE to PRO, log it. Important for support and billing dispute resolution later.
- **A "promo plan" mechanic.** Admin can give an org PRO free for N months. New column `plan_overrides_until`. Useful for closing pilot deals.
- **A `Plan.allowsFeature(Feature)` matrix.** As you add features in M2–M6, gate them with `if (org.plan.allowsFeature(CUSTOM_DOMAIN))`. Better than scattered `if (plan == PRO || plan == SCALE)` checks.
- **A `/pricing` API endpoint** that returns the current plan structure. The marketing site (M5) consumes it; means pricing changes don't require a frontend deploy.

### Acceptance

Migration runs cleanly against staging DB. Existing orgs default to FREE. `TicketService.issueTickets()` calls `PlanEnforcementService.checkCanIssueTicket()` which currently returns true unconditionally. New scheduled job exists and runs daily without errors.

---

## Cross-cutting concerns

### Testing

Every M1 issue should add tests covering the success path and one failure. Don't aim for 100% coverage; aim for "I would catch a regression."

Specifically:
- [#161 refund](https://github.com/firegiant9000/FairTix/issues/161): integration test with Stripe in test mode (use `stripe-mock` or real test keys in CI — Stripe test keys are free and rate-limit-friendly).
- [#162 notifications](https://github.com/firegiant9000/FairTix/issues/162): unit test per category.
- [#163 correlation IDs](https://github.com/firegiant9000/FairTix/issues/163): integration test that the same id appears in audit log + email header + Stripe metadata.
- [#167 org model](https://github.com/firegiant9000/FairTix/issues/167): the largest test surface — permission matrix per role.

### Performance

None of M1 should regress performance. The audit log gets a new indexed column ([#163](https://github.com/firegiant9000/FairTix/issues/163)) and a new join ([#167](https://github.com/firegiant9000/FairTix/issues/167) for the org check). Both should be sub-ms with indexes. Verify with `EXPLAIN ANALYZE` on the org-membership query under realistic data volume.

### Documentation

Three documents to add as you go:
- `docs/adr/0001-cookie-auth.md` — why HttpOnly cookies, why HS256 JWT, threat model
- `docs/adr/0002-org-and-role-model.md` — why org + member + invite, why platform admin is separate from org roles
- `docs/runbook-refunds.md` — how refunds work end-to-end (Stripe API → DB state → email → webhook), what failure modes look like, how to manually resolve

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| The org model refactor breaks existing event ownership | High | Keep `organizer_id` column for the entire M1; remove only in M2 after extensive testing. Run V31 backfill on a copy of prod data first. |
| Stripe refund call fails in prod for unforeseen reason | Med | Implement only after staging is up. Add a feature flag (`stripe.refunds.enabled`) so you can disable without redeploying. |
| Notification gating accidentally suppresses transactional mail | Med | Treat the `Category.isTransactional()` bypass as the highest-test-priority code path. |
| Staging cost overruns | Low | Set Railway hobby budget alerts; sleep services when idle. |
| Coverage gates block legitimate refactors | Low | Set the floor at baseline-minus-1%, not aspirational. |

### What's deliberately out of scope for M1

These belong to later months — don't scope-creep into them:
- **Stripe Connect integration** ([#170](https://github.com/firegiant9000/FairTix/issues/170)) — M2. The refund work in M1 uses the existing direct-charge model.
- **Box office mode** ([#171](https://github.com/firegiant9000/FairTix/issues/171)) — M2.
- **Apple Wallet** ([#181](https://github.com/firegiant9000/FairTix/issues/181)) — M3.
- **Billing enforcement** ([#209](https://github.com/firegiant9000/FairTix/issues/209)) — M5. M1 only adds the columns.
- **The actual marketing site** ([#208](https://github.com/firegiant9000/FairTix/issues/208)) — M5.

---

## Bonus track — "M1.5" candidate issues

These didn't make the original M1 cut but are small enough they could land alongside the main work without disrupting the plan. If you finish ahead of schedule (you won't, but in case):

1. **Slack/Discord webhook for ops events** — refund failures, payout failures, new org signups, fraud flags. ~2 hours. Massive operational comfort even before there are customers.
2. **A `/version` endpoint** — returns commit SHA + build date. Surfaces in the frontend footer. Cheapest support tool ever built. ~30 min.
3. **README rewrite** — replace the school-style setup steps with a positioning paragraph + a "for venue owners" / "for developers" split. ~1 hour, mentioned in the strategic roadmap's Section 7.
4. **`scripts/setup-dev.ps1`** — Windows-friendly one-command bootstrap (docker compose up + run seed). You're on Windows; future-contractor will be too. ~1 hour.
5. **A `CHANGELOG.md`** with the M1 changes documented. Become customer-visible later. ~30 min.
6. **Sentry (or equivalent error tracking)** wired into backend + frontend. Free tier covers tiny projects forever. Catches the first prod bugs before customers report them. ~2 hours.
7. **An `/api/admin/_internal/refund/manual` endpoint** for manually issuing a refund against a Stripe charge id, gated to platform admin. Helps you unstick weird states without a deploy. ~1 hour.

---

## Definition of done for M1

- All 9 issues closed
- All PRs merged through `develop` → smoke-tested in staging → merged to `main`
- Refund integration test passes in CI with real Stripe test API
- Coverage baseline locked in JaCoCo + Jest
- Staging URL is real and a real Stripe test refund has been performed there
- One end-to-end smoke test recorded in a video (literally: screen-record the flow once for your own reference; future-you in M3 will want it)
- This file revisited and updated with what actually happened vs what was planned

---

_Last updated 2026-05-12. Update at end of each issue with actual time spent, blockers hit, and decisions made — those become inputs for the M2 guide._
