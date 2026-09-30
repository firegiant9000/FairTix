# FairTix — Strategic Evaluation & 6-Month Solo Roadmap

_Prepared 2026-05-12_

---

## TL;DR

> **2026-09-29:** this TL;DR, Sections 1 to 3 and the Path A recommendation are the original 2026-05-12 analysis, kept as history. The current direction is the **Revision 2026-09-29** section below. Where the original text states a fact the code contradicts, a dated correction is inline. Seat holds are PostgreSQL row locks, not Redis.

FairTix is a **technically strong MVP** that already covers ~80% of the surface area of a real ticketing platform: events, venues, performers, seat holds (Redis), queues (SSE), Stripe payments, refunds, transfers, fraud scoring, audit logging, admin console, deployment pipeline. Code quality is high, tech debt is low, test coverage is respectable.

The hard question is **not whether FairTix can be finished** — it's whether finishing it produces something a market will pay for. As a solo developer, you cannot out-build Ticketmaster, Eventbrite, SeatGeek, AXS, or DICE on the general ticketing front. They have 10–500 person engineering teams, decade-long venue relationships, and exclusive contracts.

**My recommendation: pivot into a narrow vertical where the "fair access" thesis is a real differentiator, and use FairTix as the foundation.** The most viable wedge is **small-to-mid independent venues and community events** (200–2,000 cap) where the dominant pain is bot resale, Eventbrite's fee bite, and lack of a queue/anti-bot story. A secondary option is to repackage the queue + anti-bot layer as an **embeddable widget** other platforms integrate, which is technically interesting but a much harder sale.

The 6-month plan below assumes the **independent-venue vertical**. If you pick differently after reading the analysis, the engineering pieces still apply — only the go-to-market changes.

---

## Revision 2026-09-29: FairTix as a distributed-systems laboratory

_This revision supersedes the framing above. The original text from 2026-05-12 is preserved below as history; each phase heading now carries a status tag. Evidence for every decision here is in [`docs/roadmap-review-2026-09.md`](docs/roadmap-review-2026-09.md), which was verified against `origin/main` at `60ed8901`._

### R1. Decision

FairTix is no longer a ticketing SaaS roadmap. It is **a distributed-systems and production-backend engineering laboratory that uses ticketing as its domain**. The "Path A vs Path C" question in Section 3 is answered: neither. Path A (venue SaaS) is cancelled; the market analysis in Section 2 still holds, and one person cannot run on-sale support, PCI scope and chargebacks. Path C's engineering deliverables (deployable, documented, measured) are adopted; its marketing deliverables (blog, HN) are optional.

The stack does not change. FairTix stays Spring Boot 4, PostgreSQL 16, Redis 7 and React. There is no .NET rewrite and no microservices split. The one thing the old roadmap ruled out that this revision requires is **running two replicas of the monolith**, because multi-instance correctness is the evidence the portfolio lacks.

This repository owns, for the whole portfolio: Terraform, Azure container infrastructure, OpenTelemetry, SLOs, k6 and failure injection. No other personal repository should duplicate them.

### R2. What the code proves today, and what it does not

Verified: sorted `PESSIMISTIC_WRITE` seat locking with `@Version` backstop, hold expiry sweep, audit log in `REQUIRES_NEW`, signature-verified Stripe webhooks, refund execution, 45 forward-only Flyway migrations, 410 `@Test` methods, a CI gate with Trivy and Dependency-Check.

Not proven by anything in the repository: that the locking works on PostgreSQL (all tests run on H2 with Flyway disabled), that any concurrent test exists (none does), any throughput or latency number, any run at more than one replica, any trace or metric, any infrastructure definition, any failure-injection result. The README's "tests cover seat-hold concurrency" and the roadmap's "handles 10k concurrent users" are design claims, corrected in this revision.

Known defects found by inspection, each of which becomes a red-before / green-after test: no unique ticket per seat; `CONFIRMED` holds never expire if payment fails; no Stripe idempotency keys; Stripe calls inside DB transactions; no webhook event ledger; ten `@Scheduled` jobs on every replica; per-JVM SSE registry; fail-open, spoofable rate limiter.

### R3. Status of the 2026-05 roadmap items

| Item | Status | Previous goal | New decision and reason | Effect on usefulness | Effect on evidence |
|---|---|---|---|---|---|
| Phase 0: pick Path A/C, board, domain, positioning README | SUPERSEDED | Commit to a business direction | Decided here: laboratory. No domain purchase. README already repositioned 2026-09. | None | Removes a distraction |
| Phase 0: staging environment on Railway | SUPERSEDED | Railway preview env | Replaced by the Terraform-defined Azure environment (L3). Railway, Cloud Run and Netlify configs stay as unmaintained legacy paths and are marked OPTIONAL. | Same | Turns "staging" into IaC evidence |
| Phase 1 code items (refunds, NotificationGate, correlation IDs, cookie auth, coverage gates, runbook) | CURRENT (done) | Production-blocking fixes | Done and verified. Correlation ID work is extended in L5 (echo `X-Request-Id`, honour inbound). | n/a | Baseline |
| Phase 1 gap 1: rebaseline JaCoCo | CURRENT | Raise floor to baseline−1 % | Do it in L1 once tests run on Postgres and the number is real. | None | Honest coverage gate |
| Phase 1 gap 2: deploy staging (Railway/Neon/Upstash) | SUPERSEDED | Staging on hosted free tiers | Replaced by L3. | Same | IaC and cloud evidence |
| Phase 1 gap 3: cookie-domain ADR | CURRENT | ADR 0002 | Already written (`docs/adr/0002-cross-subdomain-cookies.md`). Revisit only when the ACA hostname is known. | None | None |
| Phase 1 gap 4: prod-restore migration test | SUPERSEDED | Run V32–V36 on an anonymised restore | There is no prod. Replaced by "Flyway V1–V45 runs against real Postgres in CI" (L1) and a PITR restore drill (L3). | None | Migration evidence |
| Phase 1 gap 5: Stripe refund IT (needs secret) | DEFERRED | Run `StripeRefundIntegrationIT` in CI | Keep gated; L4 replaces the money-movement risk with idempotency keys and a replay test that needs no live Stripe. | None | Replaced by a stronger test |
| Phase 1 gaps 6–7: screencap, organizer RTL tests | CANCELLED | Demo polish | No engineering evidence. | Low | None |
| Phase 2 M2-01..M2-25 (done rows) | CURRENT (done) | Organizer self-service | Keep as-is; no further organizer breadth. | n/a | Baseline |
| M2-04 dashboard cache and four indexes | OPTIONAL | Perf fix | Do only if the L6 load run shows these queries as a bottleneck. Measure first. | None | Only if measured |
| M2-05 attendee CSV, velocity chart | DEFERRED | Organizer UI | Feature breadth. | Low | None |
| M2-07 partial-refund IT | DEFERRED | Stripe test-mode IT | Same as gap 5. | None | Replaced |
| M2-10 Stripe Terminal SDK frontend | DEFERRED | Card-present at box office | Hardware-blocked; irrelevant to the laboratory. | None | None |
| M2-22 custom-domain daily health check | SUPERSEDED | Scheduled check | Never implemented despite the ✅. Would add an eleventh replicated scheduler; only build it after L4 leader election, and only if needed. | None | None |
| Phase 2 DoD: scan at door, payout schedule, card path, screencap | CANCELLED | Product completeness | Not evidence-producing. | Low | None |
| Phase 3 (signed QR, scan endpoint, wallet passes, ticket trust) | CANCELLED | Gate entry | No concurrency, cloud or reliability evidence. Revisit only if a load experiment needs a second write path. | Medium for a real venue, zero for this project | None |
| Phase 4 (promo codes, presales, access controls, pricing) | CANCELLED | Monetisation mechanics | Feature breadth for zero customers. | Low | None |
| Phase 5 (attendee UX, marketing site) | CANCELLED | Growth | No evidence. | Low | None |
| Phase 6 (first customers, GTM) | CANCELLED | Business | Path A cancelled. | n/a | n/a |
| Phase 7 week 24: k6 at 1,000 users | SUPERSEDED | One load test | Becomes L6 with defined metrics, an environment definition and a committed results table. | None | The headline number |
| Phase 7: wire `RiskScoringService` into checkout | DEFERRED | Real-time fraud blocking | Adds latency to the path under test without adding evidence. | Low | None |
| Phase 7 stretch: backup and DR runbook | CURRENT | Runbook | Folded into L3 as a PostgreSQL Flexible Server PITR restore drill with recorded counts and time. | None | Recovery evidence |
| Phase 7 stretch: public API keys, Mailchimp, DocuSign, status page, affiliate codes | CANCELLED | Integrations | No evidence. | Low | None |
| Section 5 "no horizontal scaling" | SUPERSEDED | Stay single-instance | Two replicas is the point of L4. The monolith stays a monolith. | None | Multi-instance evidence |
| Section 5 "no stack replacement" | CURRENT | Keep the stack | Unchanged. | n/a | n/a |
| Section 7 next-7-days, Open questions | SUPERSEDED | Business setup | Replaced by the portfolio execution plan. | n/a | n/a |

### R4. Laboratory milestones

Each milestone lists its acceptance criterion, the artifact it produces, a resume bullet with placeholders that must not be filled until the experiment runs, and the interview questions it should prepare you for. Order is fixed; L1 unblocks everything else.

#### L1. Realistic database correctness

**Goal.** Make the test suite run against the real database engine, then prove the seat-hold invariants under concurrency, deterministically.

Work:
1. Add Testcontainers (PostgreSQL 16 and Redis 7) to the backend test build. Replace the H2 datasource and the `TestRedisConfig` Mockito bean for integration tests with real containers. Let Flyway run V1–V45 in every test run; remove `ddl-auto=create-drop` from the test profile.
2. Keep fast unit tests where they are pure Mockito; only tests that touch a repository, a transaction or Redis move to containers. Split the Maven surefire and failsafe phases so the container suite is `verify`, not `test`.
3. Write a deterministic **contention correctness test**: N virtual clients (start with 200) request overlapping seat sets from a 50-seat section across several rounds, using an `ExecutorService` and a `CountDownLatch` so every thread hits the lock at once. Assert zero seats with more than one active hold, zero oversells, and that every rejected request failed with the expected conflict. Sample `pg_locks` and `pg_stat_activity` during the run. Add a second variant with the sorted-ID ordering removed that asserts a `DeadlockLoserDataAccessException` occurs, proving the ordering matters.
4. Fix the three inventory and payment defects with reproducing tests that are red on the parent commit and green on the fix:
   - Partial unique index on `tickets(seat_id)` for non-cancelled tickets, mapped to a 409. A two-thread checkout test on the same confirmed hold reproduces two tickets before and one after.
   - Expire `CONFIRMED` holds that never reach a paid order: a second expiry window on confirmation, and an explicit release on payment failure. Test that a failed PaymentIntent returns the seat to available.
   - Hold and payment failure paths: enumerate what happens when Stripe succeeds and the DB commit fails, and when the DB commits and Stripe fails; write the test for each, even if the fix lands in L4.
5. CI hygiene in the same milestone: add a `push: main` trigger, pin `Dependency-Check_Action` and `trivy-action` to commit SHAs, give Dependency-Check a `failBuildOnCVSS` threshold, add `mvnw`, rebaseline the JaCoCo floor to the measured number minus one point.

**Acceptance.** CI runs Flyway V1–V45 against a PostgreSQL container on every PR and on push to `main`; the contention test passes with zero oversells and the no-ordering variant demonstrates a deadlock; the three defect tests exist with the red commit hash recorded in their Javadoc; the H2 dependency is gone from the integration path.

**Separation of concerns.** L1 is correctness. It records counts (holds, conflicts, deadlocks, retries), not latency. Latency belongs to L2.

**Evidence produced.** `docs/experiments/l1-contention-correctness.md` with the test names, the commit hash, the container image tags, the counts per round, and links to the red and green commits for each defect.

**Resume potential.** "Verified FairTix seat holds under [N] concurrent clients contending for [M] seats on PostgreSQL 16 with zero oversells and zero duplicate ownership, and demonstrated that removing sorted lock acquisition produces [D] deadlocks; closed a double-sell race with a database uniqueness backstop verified by a concurrent test."

**Interview questions.**
- Why sort seat IDs before acquiring row locks, and what happens if you do not?
- Why is `@Version` still needed when you already hold a pessimistic lock?
- What does H2 get wrong about `SELECT ... FOR UPDATE` compared with PostgreSQL?
- Where should oversell protection live: application code, a lock, or a constraint? Why all three?
- How do you make a concurrency test deterministic enough for CI?

#### L2. Measured contention

**Goal.** Put numbers on the design from L1, in a controlled local environment, with the limitations written down.

Experiment: about 200 concurrent operations against a 50-seat section with overlapping requests, repeated for multiple rounds, on Testcontainers Postgres on a developer machine (not GitHub Actions). Record successful holds, rejected conflicts, oversells (must be 0), deadlocks, optimistic-lock retries, database lock waits from `pg_stat_activity`, and hold-latency p50, p95 and p99. Run at Hikari pool size 10 and one tuned size. Repeat three times and report the spread.

**Acceptance.** A results table in `docs/experiments/l2-contention-measured.md` with the machine spec, JVM flags, container versions, commit hash and the three-run spread. GitHub Actions latency numbers are explicitly excluded from the resume.

**Evidence produced.** The results table and the JMH-free harness (a JUnit test tagged `@Tag("experiment")`, excluded from CI by default).

**Resume potential.** "Measured FairTix seat-hold contention at [N] concurrent clients on PostgreSQL: [X] ms p95 hold latency, [C] rejected conflicts, [R] optimistic-lock retries, zero oversells; identified [bottleneck] as the limiting factor."

**Interview questions.**
- What is the difference between a lock wait and a deadlock, and how did you observe each?
- Why do p50 and p99 diverge under row contention?
- What changed when you changed the connection pool size, and why?
- Why are CI runner numbers not trustworthy for latency?

#### L3. Azure infrastructure in Terraform

**Goal.** A cloud environment that is created, used for an experiment and destroyed, entirely from code, with no long-lived cloud credentials in GitHub.

Architecture (verified against Microsoft Learn and the `azurerm` provider on 2026-09-29):
- Resource group; Azure Container Registry (Basic).
- Azure Container Apps environment with Log Analytics; one backend app with `min_replicas = 2` during experiments and an HTTP scale rule; system-assigned managed identity.
- Azure Database for PostgreSQL Flexible Server, Burstable B1ms, stop/start between experiments; PITR retained 7 days.
- Redis: **Azure Managed Redis** (`azurerm_managed_redis`, smallest Balanced or Memory Optimized SKU, `high_availability_enabled = false` for cost) **or** a Redis container app in the same environment for the cheapest experiments. **Azure Cache for Redis is not an option**: Microsoft blocks creation of new Basic, Standard and Premium caches for existing customers from 1 October 2026 and retires the service in 2028.
- Key Vault with RBAC; secrets referenced from the container app through the managed identity (`secret { key_vault_secret_id, identity }`), never as plain values in Terraform state or workflow files.
- Application Insights workspace-based resource for L5.
- Subscription budget with an alert at a stated monthly amount (`azurerm_consumption_budget_subscription`) applied before the first `apply`.
- GitHub Actions authenticating through OIDC federated credentials on a user-assigned managed identity; no client secret anywhere.

Workflows: `terraform fmt -check` and `validate` on every PR; `plan` on PR with the plan posted as a comment; `apply` only on `workflow_dispatch` with an environment approval; `destroy` on `workflow_dispatch` with the same approval. Remote state in an Azure Storage account created once by hand and documented.

Also in this milestone: harden the Dockerfile (non-root user, `HEALTHCHECK`, explicit JVM memory flags, remove the dead block), delete or mark unmaintained the Railway, Cloud Run and Netlify deploy paths, and run one PITR restore drill on the Flexible Server recording row counts and elapsed time.

**Acceptance.** `apply` and `destroy` both succeed from CI through OIDC; `/actuator/health` answers on the Container Apps hostname with two replicas running; the budget alert exists before the first apply; `docs/infra/cost.md` records the estimated and the actual monthly cost for one experiment window; `terraform destroy` leaves an empty resource group; the restore drill is recorded.

**Estimated cost.** Confirm with the Azure pricing calculator before applying. Expected order of magnitude: ACA consumption plus ACR Basic a few dollars per month idle; Flexible Server B1ms and the smallest Managed Redis add tens of dollars per month while running, less if the Flexible Server is stopped between experiments and Redis runs as a container app. Target: under a stated cap per experiment cycle, written into the budget resource.

**Evidence produced.** The `infra/` Terraform module, the four workflow runs (plan, apply, destroy, restore drill), the cost document, a screenshot of the resource group before and after destroy.

**Resume potential.** "Provisioned a two-replica Azure Container Apps environment (Container Registry, PostgreSQL Flexible Server, Managed Redis, Key Vault, managed identity, Log Analytics) with Terraform and OIDC-federated GitHub Actions; created and destroyed it on demand at about $[C] per experiment cycle."

**Interview questions.**
- Why OIDC federation instead of a service-principal secret, and what does the federated credential actually trust?
- How does a container app read a Key Vault secret without any credential in its configuration?
- What is in Terraform state that you would not want in a public repository, and how did you keep it out?
- Why Container Apps instead of AKS for this workload?
- What did the destroy workflow fail to remove the first time, and why?

#### L4. Multi-instance correctness

**Goal.** Make the service correct at two replicas, with each known single-instance assumption fixed and proven by a reproducing test.

Known assumptions to fix (from the review): ten `@Scheduled` jobs run on every replica; the SSE emitter registry is a per-JVM map; queue admission and expiry run on every replica; webhook processing has no event ledger; Stripe calls have no idempotency keys and run inside DB transactions; checkout has no client idempotency.

Work, each with a red-before / green-after test:
1. Scheduler leader election: ShedLock on PostgreSQL (or an advisory-lock wrapper). Test: two application contexts against one database, one sweep tick, exactly one execution.
2. SSE fan-out through Redis pub/sub (Redisson `RTopic`) so admission on replica A notifies a client connected to replica B. Test: two contexts, one Redis container, client subscribed on B, admission on A, event received on B.
3. Webhook event ledger: a `stripe_events` table written in the same transaction as the side effect; redelivery and out-of-order delivery are no-ops. Test: replay recorded `charge.refunded` payloads twice and out of order; exactly one refund completion.
4. Stripe idempotency keys on `PaymentIntent.create` and `Refund.create`, derived from the order or refund ID; move the Stripe call out of the DB transaction or make the post-call commit failure retry-safe. Test: simulate commit failure after the Stripe call; the retry sends the same idempotency key.
5. `Idempotency-Key` header on checkout with a stored response; duplicate submits return the original result.
6. Retry-safe external calls (Stripe, SMTP) with bounded retries, backoff and jitter.

Two-replica compose profile (two backend containers behind nginx) for local verification before the cloud run.

**Acceptance.** All six tests exist and pass; a two-replica compose run of the waiting room admits 100 queued users split across replicas with zero duplicate admissions and zero missed notifications; the same on Container Apps at two replicas.

**Evidence produced.** `docs/experiments/l4-two-replicas.md` with duplicate counts before and after each fix, the test names, and the compose file.

**Resume potential.** "Made FairTix safe to run at [R] replicas: added scheduler leader election, replaced per-JVM SSE fan-out with Redis pub/sub, and made Stripe webhooks and checkout idempotent (event ledger, idempotency keys), each verified by a reproducing test that showed [K] duplicates before the fix."

**Interview questions.**
- What breaks when you run this service at two replicas? List everything.
- Why is a database lock a reasonable leader-election primitive here, and when would it not be?
- What does an idempotency key protect against that a unique constraint does not?
- Why must the webhook ledger row be written in the same transaction as the side effect?
- How do you test "exactly once" without flakiness?

#### L5. Observability and SLOs

**Goal.** One trace across the whole hold path, structured logs with correlation, metrics, two SLIs, an SLO, an alert and a runbook.

Work: Micrometer with the OpenTelemetry exporter (or the Application Insights Java agent; choose one and record why) sending to Application Insights through the Container Apps managed OpenTelemetry agent (traces and logs; metrics go to Azure Monitor through Micrometer directly because the ACA agent's App Insights destination does not accept metrics). Propagate one trace across HTTP request, application logic, seat locking, database commit, the scheduler tick where relevant, the SSE notification and the Stripe boundary. Switch to JSON logs, honour inbound `X-Request-Id`, echo it in every response and put the trace ID in every log line.

SLIs: hold-request success rate (non-5xx, non-timeout responses to hold requests divided by all hold requests) and waiting-room admission notification latency (time from admission decision to SSE event delivered). Derive one initial SLO for each with a stated window, one alert rule each, and one runbook each.

**Acceptance.** A single trace in Application Insights shows request → lock → commit → SSE for one hold; both SLIs are visible on a dashboard; both alerts fire during the L6 drill; both runbooks exist and were followed once.

**Evidence produced.** Trace screenshot, dashboard screenshot, alert rule definitions in Terraform, `docs/runbooks/`.

**Resume potential.** "Instrumented FairTix with OpenTelemetry (traces, JSON logs with correlation IDs, Micrometer metrics) into Azure Monitor; defined [k] SLIs and SLOs with alerts and runbooks that fired within [T] s during fault-injection drills."

**Interview questions.**
- How does trace context cross the scheduler and the SSE boundary, where there is no HTTP request to carry it?
- Why is "hold-request success rate" a better SLI than "error rate"?
- How did you choose the SLO target without historical data, and what would you change after a month?
- What is the difference between the OTel agent approach and the SDK approach, and why did you pick one?

#### L6. Load and failure testing

**Goal.** External load numbers on the cloud environment and documented failure behaviour, with one postmortem.

k6 scripts for queue join, admission wait, hold and checkout. Ramp VUs against the Container Apps environment at two replicas, then with the scale rule active. Record VUs, throughput, p50/p95/p99 per endpoint, error rate, oversells (must be 0), DB pool exhaustion events, lock waits, replica count over time, and queue admission behaviour. Repeat at Hikari 10 and a tuned size.

Failure injection with Toxiproxy locally (Redis outage for 60 s, Redis restart, PostgreSQL latency at 50 ms and 200 ms) and on Container Apps by stopping a replica mid-hold, replaying webhooks and failing an external dependency. Observe the fail-open rate limiter, queue position resets, sweep drift, SSE reconnects and time-to-alert. Fix the rate limiter's trust of `X-Forwarded-For` before the load run, or the load numbers are invalid.

**Acceptance.** `docs/experiments/l6-load.md` and `docs/experiments/l6-faults.md` with tables, the k6 script version, the Terraform commit and the replica timeline; one postmortem-style document for one drill; the environment destroyed afterwards. No number appears on the resume until it appears in these files.

**Evidence produced.** k6 summary JSON committed, results tables, the postmortem.

**Resume potential.** "Load-tested FairTix's waiting-room and seat-hold pipeline on Azure Container Apps to [N] concurrent clients at [Y] req/s and [Z] ms p95 with zero oversells under PostgreSQL row contention; ran fault-injection drills (Redis loss, database latency, replica restart, webhook replay) and documented failure modes and recovery in runbooks and a postmortem."

**Interview questions.**
- What was the first bottleneck and how did you find it?
- What did the system do when Redis disappeared, and was that the right behaviour?
- How does the waiting room behave under a replica restart, and what did you change?
- Walk me through the postmortem: detection, impact, root cause, action items.

### R5. Do not do (2026-09 revision)

Marketing site, customer acquisition, wallet passes, QR ticket expansion, promotion engine, consumer-growth features, additional organizer breadth, Kubernetes, a .NET rewrite, microservices, CodeQL or security scanning beyond the existing Trivy and Dependency-Check (Hacker Tracker owns security assurance), and any number on the resume that is not in a `docs/experiments/` file.

---

## Section 1: Honest Project Evaluation — HISTORICAL (2026-05-12; see R2 for the 2026-09 verified state)

### What's actually built (and good)

| Area | State | Notable |
|---|---|---|
| Backend modules | 21 domains, layered cleanly | api/application/domain/infrastructure split is consistent |
| Seat holds | ~~Redis-backed~~ PostgreSQL rows under sorted `PESSIMISTIC_WRITE` locks with `@Version` (corrected 2026-09-29), deadlock-safe by design, expiry column swept every 30 s, per-user caps | Design is correct; never exercised on PostgreSQL or under concurrency by a test (L1) |
| Queue / waiting room | Redis position + SSE stream + admin admit | Real-time, the hardest UX piece in fair-access ticketing |
| Auth | JWT in HttpOnly cookies + refresh rotation + email verification + reCAPTCHA + login throttle | Mature; better than most side projects |
| Payments | Stripe PaymentIntent + webhook + simulated fallback | Real integration, not a stub |
| Refunds | Auto-approve <$50, manual review otherwise, full audit | Workflow is correct; **Stripe refund API call itself is not wired** |
| Audit | `REQUIRES_NEW` propagation so audit survives rollback | Indicates the author understood transactional pitfalls |
| Fraud | Risk scoring, behavior analysis, step-up gate, manual flag review | Framework exists; not used in real-time blocking |
| Admin console | 9 sub-pages, charts, CRUD across all entities | More complete than expected |
| CI/CD | GitHub Actions + Trivy + OWASP DC, Railway backend, Netlify frontend, GCP Cloud Build prepped | Deployable today |
| Frontend | React 18, no Redux noise, geo search via Leaflet, Stripe Elements | Clean, no abandoned migrations |
| DB | 29 Flyway migrations at the time (45 as of 2026-09-29), all forward-only | Schema discipline is intact; the chain has never run against PostgreSQL in CI (L1) |

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
- Microservices / horizontal scaling (single Spring Boot deploy handles 10k concurrent users on Railway) — _2026-09-29: the 10k figure was never measured; horizontal scaling of the monolith is now milestone L4, and microservices stay out._

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

### Path A — Independent venue SaaS (recommended 2026-05; CANCELLED 2026-09-29, see R1)

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

## Section 4: 6-Month Solo Roadmap (Path A) — SUPERSEDED 2026-09-29 by the laboratory milestones L1–L6 (per-phase statuses in R3)

### Operating assumptions

- ~15–20 hrs/week available outside school + work
- Sonnet for routine coding, Opus for design decisions
- One branch per issue, PR-per-feature, no direct commits to main
- No new dependencies without a written reason
- Every phase ends with a demo and a written reflection — used to decide whether to continue
- Treat the first 3 months as engineering, the last 3 months as 50/50 engineering + go-to-market

### Phase 0 — Decision & setup (Week 0, ~1 week) — SUPERSEDED 2026-09-29 (see R3)

**Goal:** Commit to a direction and clear the runway.

- [ ] Pick Path A vs C explicitly, write it down in this file
- [ ] Create GitHub project board with the milestones below
- [ ] Stand up a staging environment (Railway has free preview envs)
- [ ] Buy domain (fairtix.com or fairtix.io if available, else pivot the name — "fairtix" + niche keyword)
- [ ] Replace the school-style README with a positioning README (one-liner, who it's for, how to run)
- [ ] Write one paragraph in this file under "Customer hypothesis": who is the venue, what do they currently use, what do they pay, why would they switch

### Phase 1 — Production-blocking fixes (Weeks 1–3) ✅ code complete — CURRENT (done); remaining gaps reclassified in R3

_Audited 2026-05-22. M1 code work landed in `feat/m1-phase-1` (18 commits, issues #161–#169). Verified files exist: V30 (audit request_id), V31 (refund.stripe_refund_id), V32–V36 (org tables + backfill), `RequestLoggingFilter` (MDC), `RefundService` (Stripe `Refund.create`), `NotificationGate`, `jacoco` in `backend/pom.xml`, `docs/runbook-staging.md`._

| Item | Status |
|---|---|
| ✅ Stripe refund execution (`RefundService` + `charge.refunded` webhook + `V31` stripe_refund_id) | done |
| ✅ `NotificationGate` enforces `NotificationPreference` at every send site | done |
| ✅ Correlation IDs via `RequestLoggingFilter` + MDC, propagated to audit + Stripe metadata | done |
| ✅ Cookie-auth migration — sessionStorage / bearer paths removed from frontend | done |
| ✅ JaCoCo + jest coverage gates wired in CI (`jacoco.line.minimum` in pom) | done |
| ✅ Staging runbook authored (`docs/runbook-staging.md`) | done |

**Remaining M1 gaps** (operational, not code — pair with first M2 staging sprint):

1. **Push `feat/m1-phase-1` and open PRs.** CI has not yet exercised the new gates against real artifacts. Capture the real JaCoCo number and baseline `jacoco.line.minimum` to baseline-minus-1%. _~1h._
2. **Deploy staging.** Railway + Neon + Upstash + Mailtrap + Stripe test webhook per the runbook; verify `/_health/deep`. _~4–6h._
3. **Cookie-domain ADR.** Decide between `.fairtix.io` cookie scope (`SameSite=None; Secure`) vs reverse-proxying `/api` through Netlify; document in ADR 0001. _~1h._
4. **Prod-restore migration test.** Run V32–V36 against an anonymized prod restore before merging to `main`; V36 fails loudly if any org lacks an OWNER. _~2h._
5. **Stripe refund integration test in test mode.** Blocked on `STRIPE_TEST_SECRET_KEY` in GitHub Actions secrets; unit math is already locked. _~2h._
6. **60-second smoke screencap** (signup → create org → order → refund) once staging is live. _~30m._
7. **Frontend RTL tests for organizer routes** — defer until M2 wizard surface settles. _~3h._

**Exit criteria** (unchanged): refund test in staging returns money; email opt-outs respected; CI fails on coverage regressions. Items 1–6 are the path to closing the criteria.

### Phase 2 — Organizer self-service & box office (Weeks 4–8) ✅ code complete — CURRENT (done); partial rows DEFERRED or OPTIONAL per R3

_Audited & remediated 2026-05-22 on branch `feat/m2-main`. **22 done / 3 partial / 0 blocker.** Backend full-suite: **439 / 439 pass.** Full per-issue status is in [`M2_IMPLEMENTATION_PLAN.md`](M2_IMPLEMENTATION_PLAN.md); summary below._

| Section | Item | Status |
|---|---|---|
| 2A Role/ACL | ✅ M2-01 6-role `OrgRole` + 19 `OrgPermission` keys + `OrgScopeInterceptor` | done |
| 2A Role/ACL | ✅ M2-02 `@OrgScoped` lint covers all 17 M2 controllers (caught missing annotation) | done |
| 2B Dashboard | ✅ M2-03 `/organizer` shell + sidebar + route guard | done |
| 2B Dashboard | 🟡 M2-04 Widgets render; **gap:** 30s per-org cache + missing indexes (`tickets.user_id`, `tickets.event_id`, `orders.organization_id`, `seat_holds.user_id`) — perf, not correctness | partial |
| 2B Dashboard | 🟡 M2-05 Per-event aggregation + `paid_tickets` view; **gap:** attendee CSV export + velocity chart UI | partial |
| 2C Connect | ✅ M2-06 Standard onboarding, US-only gate, account fields persisted | done |
| 2C Connect | 🟡 M2-07 Plan→bps (Free 250 / Pro 150 / Scale 100), `on_behalf_of`, refund passes `reverse_transfer`. **Gap:** partial-refund Stripe-test-mode integration test (needs `STRIPE_TEST_SECRET_KEY` in GHA) | partial |
| 2C Connect | ✅ M2-08 Connect panel: account status + payouts list | done |
| 2D Box office | ✅ M2-09 `BoxOfficeController` + tablet UI; uses `SeatHoldService` (does not bypass); V40 sessions/sales | done |
| 2D Box office | 🟡 M2-10 Server-side Terminal flow complete (connection-token, CardPresent PaymentIntent). **Gap:** load Stripe Terminal JS SDK + reader pairing — blocked on WisePOS E hardware | partial |
| 2D Box office | ✅ M2-11 End-of-night reconciliation: variance, sign-off audit, close flow | done |
| 2E Comps/holds | ✅ M2-12 `TicketKind` enum + V41 check constraint + `CompService` + `paid_tickets` view | done |
| 2E Comps/holds | ✅ M2-13 `EventHold` + `HoldReleaseScheduler` + bulk operations UI | done |
| 2E Comps/holds | ✅ M2-14 Will-call `/will-call/print` with `sort` + `filter` params; browser print-to-PDF | done |
| 2F Reports | ✅ M2-15 DOS report; `ReportRendererReconciliationTest` enforces CSV-vs-HTML parity to the penny (caught a real label drift) | done |
| 2F Reports | ✅ M2-16 Settlement + signable export + exhaustive `switch` (build fails on new `SplitType` without handling) | done |
| 2F Reports | ✅ M2-17 Payout report: `stripe_payouts` cache + webhook sync + 30-day view | done |
| 2F Reports | ✅ M2-18 `TaxReportService.threshold/yearlyExport` + `TaxThresholdAlertScheduler` (daily 02:15 UTC, dedupes 80%/100% crossings) + `EinCipher` AES-256-GCM | done |
| 2G Branding | ✅ M2-19 Org branding: logo, primary color, sender, reply-to, statement descriptor (22-char check), dark mode | done |
| 2G Branding | ✅ M2-20 Event page customization + `MarkdownRenderer` (escape-then-whitelist) + 17-payload OWASP XSS regression suite | done |
| 2G Branding | ✅ M2-21 SEO: JSON-LD `Event` + OG/Twitter card + sitemap + 301 redirects + robots.txt | done |
| 2G Branding | ✅ M2-22 Custom domain CNAME via `JndiDnsTxtResolver` + uniqueness + daily health check (Caddy/TLS is infra) | done |
| 2G Branding | ✅ M2-23 Embed widget `/embed.js` + iframe postMessage auto-resize + origin check | done |
| 2H Onboarding | ✅ M2-24 4-step signup wizard + admin approval queue; new orgs land `PENDING` (bug fix) | done |
| 2H Onboarding | ✅ M2-25 `OrgSalesCapService` tier progression ($1k → $10k → unlimited) + `org_sales_ledger` + `SalesCapExceededException` → 429 | done |

**Cross-cutting:** audit coverage ✅ (every mutation `REQUIRES_NEW`), correlation IDs ✅, migration discipline ✅ (V42 collision resolved → V42/V43/V44), `@OrgScoped` lint ✅ (17 controllers), JaCoCo gate ⏭ (rebaseline on first staging CI), frontend RTL 🟡, staging smoke ⏭.

**M2 exit criteria** — DoD status:

- [x] Sign up as organizer, complete Stripe Connect onboarding, create venue+event+seats, issue 3 comps, hold 5 seats, sell 10 tickets (cash + comp + card path)
- [x] DOS report ties to the penny — enforced by automated CSV-vs-HTML reconciliation test
- [ ] **Scan at the door** — placeholder; `qr_payload` is scannable-shaped, real endpoint is M3 / Phase 3
- [ ] **Watch Stripe pay the organizer on test-mode schedule** — needs staging webhooks live
- [ ] **Card path at box office** — server-side ready, frontend Terminal SDK awaits hardware (M2-10)
- [ ] **Staging end-to-end 60s screencap** — owed once staging is up

**Carryover into M3 (none of these are M2 feature gaps; all are operational or hardware):** Stripe test-mode partial-refund integration test, Terminal SDK frontend wiring, dashboard 30s cache + missing indexes, attendee CSV + velocity chart, frontend RTL tests for organizer routes, staging cutover screencap.

### Phase 3 — Gate entry, wallet passes & ticket trust (Weeks 9–12) — CANCELLED 2026-09-29 (no engineering evidence; see R3)

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

### Phase 4 — Monetization mechanics & access controls (Weeks 13–15) — CANCELLED 2026-09-29 (see R3)

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

### Phase 5 — Attendee experience & marketing site (Weeks 16–18) — CANCELLED 2026-09-29 (see R3)

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

### Phase 6 — First customers & GTM (Weeks 19–22) — CANCELLED 2026-09-29 (Path A not pursued; see R1)

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

### Phase 7 — Scale or exit cleanly (Weeks 23–24, with longer if continuing) — SUPERSEDED 2026-09-29 (k6 and DR items promoted to L6 and L3; the rest CANCELLED or DEFERRED per R3)

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

_2026-09-29: the "no horizontal scaling" guidance below is SUPERSEDED by L4 (two replicas of the monolith). Everything else in this section still stands; see R5 for the current list._

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

## Section 7: Concrete next 7 days — SUPERSEDED 2026-09-29 (next work is L1; see the portfolio execution plan)

If you agree with this plan:

1. Decide Path A vs C, write the choice in Section 3 of this file.
2. Open three GitHub issues: "Wire Stripe refund execution", "Enforce NotificationPreference at send time", "Stand up staging env".
3. Buy a domain.
4. Replace the README's first 10 lines with a positioning sentence and a "who this is for" paragraph.
5. Create a `customers.md` file (gitignored) and start listing target venues.
6. Block 8 hours on the calendar this week for Phase 1 work. Treat them as immovable.
7. Re-read this file at the end of the week and update the open questions below.

---

## Open questions (fill in as you go) — ANSWERED 2026-09-29

_Path chosen: neither A nor C; laboratory (R1). Customer hypothesis, domain, target venues, blog titles and quit criteria: not applicable to a laboratory. Left blank below as history._

- Customer hypothesis (1 paragraph):
- Path chosen (A or C):
- Domain name:
- First 10 target venues:
- First three blog post titles:
- What would make you quit by week 22:

---

## M1 deferred items — picked up early in M2 or as needed — RECLASSIFIED 2026-09-29

_Every row below now has a status in R3. In short: JaCoCo rebaseline is CURRENT (in L1); staging on Railway, Neon, Upstash and Mailtrap is SUPERSEDED by L3; the cookie-domain decision is done (ADR 0002); the prod-restore migration test is SUPERSEDED (no prod exists; L1 runs Flyway on Postgres and L3 runs a PITR drill); the Stripe test-mode IT is DEFERRED in favour of L4's replay and idempotency tests; the screencap is CANCELLED; the ops webhook is DEFERRED until L5 alerting exists. The original table is kept below._

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

## Appendix A: Full Feature Catalog (priority-tagged) — HISTORICAL

_2026-09-29: the priority tags below belong to the cancelled Path A plan. No item here is scheduled unless it appears in L1–L6. Appendices B and C are also historical._

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
