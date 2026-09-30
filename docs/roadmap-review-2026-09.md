# FairTix roadmap review, September 2026

_Prepared 2026-09-29 against `origin/main` at `60ed8901`. Read-only audit of the repository, its planning documents and CI; nothing in this document is a measured result. Line references are to that commit._

This review is the evidence base for the 2026-09 revision of [`STRATEGIC_ROADMAP.md`](../STRATEGIC_ROADMAP.md). The roadmap file remains canonical; this file records what was verified, what was found to be claimed but unproven, and why the roadmap was reframed.

## 1. Reframing decision

FairTix was planned (2026-05-12) as an independent-venue ticketing SaaS ("Path A") with a portfolio exit ("Path C") as the fallback. As of this review the project is reframed as **a distributed-systems and production-backend engineering laboratory that uses ticketing as its domain**. Ticketing stays because oversell, double-charge and unfair admission are unambiguous failures that make experiments easy to judge. The product roadmap (Phases 3 to 7) is not deleted; it is classified below.

The reason is evidence, not taste. The codebase has genuinely good concurrency design (sorted pessimistic locks with an optimistic backstop) and a list of known multi-instance defects, but nothing about it has ever been measured or operated. Every hard claim in the README and roadmap is a design claim. The revised roadmap exists to turn those into measured claims.

## 2. Current roadmap goals (as written before this revision)

| Section | Goal | Stated status |
|---|---|---|
| Phase 0 | Decide Path A/C, staging, domain, positioning README | all boxes unchecked |
| Phase 1 | Production-blocking fixes (refund execution, NotificationGate, correlation IDs, cookie auth, coverage gates, staging runbook) | "code complete"; 7 operational gaps listed |
| Phase 2 | Organizer self-service and box office (M2-01..M2-25) | "code complete", 4 partial rows, 4 DoD boxes unchecked |
| Phase 3 | Signed QR, scan endpoint, wallet passes, ticket trust | not started |
| Phase 4 | Promo codes, presales, access controls, pricing mechanics | not started |
| Phase 5 | Attendee experience, marketing site | not started |
| Phase 6 | First customers, go-to-market | not started |
| Phase 7 | Scale (k6 at 1,000 users, real-time fraud blocking) or exit to Path C | not started |
| Section 5 | Explicit "do not" list, including "no horizontal scaling" | guidance |

## 3. Already implemented (verified in code)

- **Seat-hold locking.** `SeatRepository.java:44-46` locks with `PESSIMISTIC_WRITE ... ORDER BY s.id`; `SeatHoldService.java:133` passes sorted IDs inside a `@Transactional` method (`:83`). `@Version` exists on `Seat.java:53` and `Event.java:123`.
- **Hold expiry sweep.** `HoldExpirationScheduler.java:70-78` runs every 30 s and expires `ACTIVE` holds past `expiresAt`, 500 per page.
- **Refund execution.** `RefundService.processRefund` (`:189-223`) calls `Refund.create` through `StripePaymentService`. Webhook signature verification exists in `StripeWebhookController.java:65-89`.
- **Audit log in `REQUIRES_NEW`**, request ID in MDC (`RequestLoggingFilter.java:28-29`), propagated to `audit_logs.request_id` (V30), Stripe refund metadata and an SMTP header.
- **Partial unique indexes** where they matter for refunds: one active refund per order (V20/V28), unique `stripe_refund_id` (V31), unique `payment_records.transaction_id` (V6), unique queue `(event_id, user_id)` (V11), partial unique on `event_holds(seat_id)` (V41).
- **Rate limiting** through Redisson `RRateLimiter` (`RateLimitService.java`), login throttling, step-up gate.
- **CI** (`.github/workflows/ci.yml`): `mvn clean verify`, cookie-auth guard, notification-gate guard, frontend Jest with coverage floors, OWASP Dependency-Check, Trivy image scan (fails on CRITICAL/HIGH).
- **45 Flyway migrations**, V1 to V45, no gaps.
- **410 `@Test` methods across 62 files** in `backend/src/test`; four integration-style flows plus one Stripe IT gated on a secret that CI does not have.

## 4. Claimed but unproven

| Claim | Where claimed | What the code shows |
|---|---|---|
| "Backend tests cover seat-hold concurrency" | `README.md:125` | No `ExecutorService`, `CountDownLatch` or `CompletableFuture` anywhere in `src/test`. `SeatHoldServiceTest` is single-threaded Mockito. |
| "Deadlock-safe" seat holds | `STRATEGIC_ROADMAP.md:26`, README | Design is correct, but every test runs on H2 with `spring.flyway.enabled=false` (`src/test/resources/application.properties:4-16`). H2 lock semantics differ from PostgreSQL; the design has never been exercised on Postgres by a test. |
| "Seat holds are Redis-backed with 10-minute TTL" | `CLAUDE.md:12,37`; `STRATEGIC_ROADMAP.md:26,200` | Holds are PostgreSQL rows with pessimistic locks and a DB expiry column. Redis is used for queue, rate limit and login throttle only. |
| "Single Spring Boot deploy handles 10k concurrent users" | `STRATEGIC_ROADMAP.md:61` | No load test of any kind exists. SSE registry is a per-JVM map; 10 schedulers run on every replica; rate limiter fails open. |
| "439/439 tests pass" / "285 tests across 45 classes" | `STRATEGIC_ROADMAP.md:265`, `README.md:121` | 410 `@Test` across 62 files at this commit. Both counts are stale. |
| "29 Flyway migrations" | `STRATEGIC_ROADMAP.md:36` | 45. |
| "Daily health check" for custom domains (M2-22) | `STRATEGIC_ROADMAP.md:290` | Column and record method exist; no `@Scheduled` job performs the check. |
| "Short-lived JWT bearer tokens" | `README.md:133-134` | ADR 0001 and the code use HttpOnly cookies; CI bans the literal `Bearer` in the frontend. |
| Correlation IDs "propagated" | `STRATEGIC_ROADMAP.md:246` | True for audit, Stripe metadata and SMTP. The ID is never echoed to the client and inbound `X-Request-Id` is ignored. |
| Redis service in CI implies Redis-backed tests | `ci.yml:15-19` | `TestRedisConfig.java:25-64` replaces `RedissonClient` with a Mockito bean; rate limiter always allows, sets are always empty. The CI Redis container is unused. |

## 5. Defects confirmed by inspection (each becomes a red-before / green-after test)

1. **No uniqueness backstop for tickets per seat.** `V1__baseline.sql:61-73` declares `tickets.seat_id NOT NULL` with non-unique indexes only. A race between two checkouts on the same confirmed hold has no database-level defence.
2. **Confirmed-but-unpaid holds never expire.** `confirmHold` (`SeatHoldService.java:266-268`) flips the hold to `CONFIRMED` and the seat to `BOOKED` before payment. The sweep queries only `ACTIVE` (`HoldExpirationScheduler.java:77-78`). A failed or abandoned payment leaves the seat `BOOKED` until a manual release.
3. **No Stripe idempotency keys.** `PaymentIntent.create` (`StripePaymentService.java:93,158`) and `Refund.create` (`:223`) pass no `RequestOptions.setIdempotencyKey`. A retry after a DB rollback moves money twice.
4. **Stripe calls inside DB transactions.** `RefundService.processRefund` and `reviewRefund` are `@Transactional` and call Stripe inside the transaction; `StripeWebhookController` is `@Transactional` around external effects.
5. **No webhook event ledger.** `StripeWebhookController.java:65-89` never records `event.getId()`; `handleChargeRefunded` (`:103-121`) completes the first non-terminal refund on every delivery.
6. **Ten `@Scheduled` jobs with no leader election.** Listed in section 7. `fairtix.scheduling.enabled` (`SchedulingConfig.java:16`) is a global switch, not a lock.
7. **SSE emitters are per-JVM.** `QueueSseService.java:24` holds a `ConcurrentHashMap<UUID, CopyOnWriteArrayList<EmitterEntry>>`. No Redis pub/sub, `RTopic` or message listener exists.
8. **Rate limiter is bypassable.** `RateLimitFilter.java:80-83` trusts the first `X-Forwarded-For` entry; `:55-58` fails open when Redis is down; `:64-71` keys authenticated users by a `Bearer` header that the cookie-auth frontend never sends.
9. **Test schema is not the Flyway schema.** Tests use `ddl-auto=create-drop` on H2. Production uses `ddl-auto=validate`, which only catches drift after deploy.

## 6. Technically useful future work (kept or added)

Everything in the revised laboratory milestones L1 to L6 in `STRATEGIC_ROADMAP.md`. In summary: Testcontainers Postgres and Redis in the test suite; deterministic contention correctness test; the three inventory/payment defects fixed with reproducing tests; a measured contention experiment; a Terraform-defined, destroyable Azure Container Apps environment; multi-instance fixes (leader election, pub/sub fan-out, webhook ledger, idempotency); Micrometer plus OpenTelemetry with two SLIs; k6 load and Toxiproxy failure drills with a postmortem.

Items from the old roadmap that survive because they enable an experiment:
- Dashboard cache and the four missing indexes (M2-04). Kept as an *optional* input to the load milestone: measure first, then add indexes only if the load run shows them as the bottleneck.
- Backup and DR runbook (Phase 7 stretch). Kept; it becomes part of the Azure milestone (Flexible Server PITR restore drill).
- k6 load test (Phase 7 week 24). Promoted from a week-24 stretch to milestone L6 with defined metrics.

## 7. Feature work that should stop

| Item | Old phase | Decision | Reason |
|---|---|---|---|
| Signed QR, scan endpoint, wallet passes | Phase 3 | CANCELLED for this roadmap | Produces no concurrency, cloud or reliability evidence. Revisit only if a load experiment needs a scan endpoint as a second write path. |
| Promo codes, presales, tiered access, dynamic pricing | Phase 4 | CANCELLED | Feature breadth for zero customers. |
| Attendee experience polish, marketing site | Phase 5 | CANCELLED | No technical evidence. |
| First customers, GTM, domain purchase, pricing | Phase 6, Phase 0 items 4 and 6 | CANCELLED | Path A is not being pursued (see 2026-09 audit: saturated market, PCI and on-sale support burden for one person). |
| Real-time fraud blocking in checkout | Phase 7 | DEFERRED | Fraud module is fine as-is; wiring it into checkout adds latency to the path under test without adding evidence. |
| Stripe Terminal SDK frontend, WisePOS hardware | M2-10 | DEFERRED | Hardware-blocked and irrelevant to the laboratory. |
| Attendee CSV export, velocity chart UI | M2-05 | DEFERRED | UI breadth. |
| Public REST API keys, Mailchimp, DocuSign, status page, affiliate codes | Phase 7 stretch | CANCELLED | Integrations with no engineering evidence. |
| Custom-domain daily health check scheduler | M2-22 | SUPERSEDED | Would add an eleventh replicated scheduler. Fold into the leader-election milestone if ever built. |

## 8. Roadmap contradictions

- Section 5 says "Don't refactor into microservices" and Section 1 says "single deploy handles 10k users", while Phase 7 plans a k6 run and the README admits the waiting room is single-instance. The revision keeps the monolith (no microservices) but explicitly targets **two replicas of the monolith**, which the old roadmap ruled out.
- Path A vs Path C was never decided (Section 3 "Open questions" is blank). The revision records the decision: neither. Path C's *deliverables* (deployable, documented, measured) are adopted; its *marketing* items (blog posts, HN submission) are optional.
- CLAUDE.md contradicts the code on Redis-backed holds and on per-user hold caps ("5" vs the test config's 2). CLAUDE.md is corrected in the same PR as this review.
- Phase 1 exit criteria require a staging deployment on Railway, Neon, Upstash and Mailtrap. The revision replaces that target environment with the Terraform-defined Azure environment; Railway, Cloud Run (`cloudbuild.yaml`) and Netlify configs stay in the repo as OPTIONAL legacy deploy paths and are not maintained.

## 9. Current risks

- **Three unmaintained deploy paths** (Railway, Cloud Build to Cloud Run, docker-compose) plus a fourth planned (Azure). The Azure milestone must delete or mark the others.
- **Dockerfile runs as root, no `HEALTHCHECK`, no JVM memory flags**, and `-DskipTests` in the image build.
- **`Dependency-Check_Action@main` and `trivy-action@master` are floating refs**; Dependency-Check has no fail threshold.
- **CI has no `push: main` trigger**, so nothing runs after a merge.
- **Fail-open rate limiter with a spoofable key** means the on-sale protection can be bypassed trivially; this is a security defect and also invalidates any load test that assumes rate limiting works.
- **JaCoCo floor of 15 % line / 5 % branch** is labelled provisional and has never been rebaselined.
- **Bare `mvn`** in CI rather than a committed wrapper; there is no `mvnw` in the repo.
- **Frontend is Create React App** (`react-scripts 5.0.1`), which is unmaintained. Not a laboratory concern, but it will eventually block dependency updates.

## 10. Evidence gaps (what does not exist yet, in the order the revision closes them)

1. Any test that runs the Flyway chain against PostgreSQL.
2. Any multi-threaded test.
3. Any number: throughput, latency percentile, lock wait, deadlock count.
4. Any infrastructure as code.
5. Any trace, metric, dashboard, SLO or alert.
6. Any run at more than one replica.
7. Any failure-injection result or postmortem.

## 11. Repository housekeeping observed (no action taken)

- Duplicate test packages `event/` and `events/` and `venue/` and `venues/`, each containing an `EventControllerTest` or equivalent.
- `backend/Dockerfile` ends with commented-out duplicate code.
- Local clone `main` on the author's machine is 56 commits behind `origin/main`; use `origin/main` or a fresh worktree.
- Two open Dependabot PRs (#247, #248) touching the same group; one is stale.
