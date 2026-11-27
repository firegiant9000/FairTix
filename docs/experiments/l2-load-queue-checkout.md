# L2 load: waiting room → hold → checkout over HTTP (k6)

An on-sale driven end to end over HTTP: many users arrive at once, join the
waiting room of one queued event, are admitted in batches, hold seats, confirm
and pay. It records latency per step, error rates and whether anything was
oversold. Correctness under row-level contention without HTTP is
[L1](l1-contention-correctness.md); in-process queue-join throughput is
`QueueJoinLoadIT` in [the cap-queue runbook](../runbooks/cap-queue-rollout.md).

How to run it: [loadtest/README.md](../../loadtest/README.md).

## Setup

| | |
|---|---|
| Code | `main` at 16955827 |
| Stack | `docker compose` with [`loadtest/docker-compose.loadtest.yml`](../../loadtest/docker-compose.loadtest.yml): one backend (dev profile, default Hikari pool of 10), `postgres:16`, `redis:7-alpine`, MailHog, all on one Docker Desktop (WSL2) VM with 24 vCPUs and 3.8 GiB RAM, on a Windows laptop |
| Load generator | k6 2.4.0 (`grafana/k6` image) in a container on the same Compose network, so no real network in between and all users share one source IP |
| Scenario | [`loadtest/queue-checkout.js`](../../loadtest/queue-checkout.js), `per-vu-iterations`: every VU is one user and starts at t = 0 |
| Event | made by [`loadtest/seed.sh`](../../loadtest/seed.sh): `queue_required = true`, no `queue_capacity` (uncapped waiting room), cap 2 tickets per user, no organisation (so no daily sales cap), one price |
| Per user | login → `POST /queue/join` → poll `GET /queue/status` every 2 s until `ADMITTED` (give up after `MAX_WAIT_S`) → `GET /seats?availableOnly=true` → hold 2 random available seats (up to 3 tries on 409) → confirm each hold → `POST /api/payments/checkout` with `simulatedOutcome: SUCCESS` |
| Overrides | per-IP auth limit raised (one source IP); queue admission every 2 s instead of 30 s, batch 50 (default); mail to MailHog, Stripe and reCAPTCHA off. Per-user rate limits, caps, hold limits, step-up and the Origin check left at their defaults |

## Results (2026-11-27, one run each)

| | Run 1 | Run 2 |
|---|---|---|
| Users (VUs) / seats | 500 / 200 | 2,000 / 1,000 |
| `MAX_WAIT_S` | 300 | 120 |
| Orders completed / tickets sold | 100 / 200 | 500 / 1,000 |
| Seats left | 0 | 0 |
| Admitted but sold out (no seat left, or 3 lost races; split into `sold_out` / `hold_gave_up` after these runs) | 19 | 12 |
| Never admitted (gave up) | 381 | 1,488 |
| Hold 409s (seat taken first, retried) | 78 | 110 |
| HTTP requests / failed | 59,423 / 0 | 100,559 / 0 |
| 5xx / unexpected 4xx | 0 / 0 | 0 / 0 |
| Last order completed after (slowest journey) | 8.9 s | 34 s |

Latency per step, in ms unless marked:

| Step | Run | p50 | p95 | p99 | max | n |
|---|---|---|---|---|---|---|
| Login | 1 | 1,350 | 2,410 | 2,530 | 2,660 | 500 |
| | 2 | 4,500 | 9,980 | 10,450 | 10,610 | 2,000 |
| Queue join | 1 | 1,330 | 2,310 | 2,400 | 2,450 | 500 |
| | 2 | 5,770 | 10,100 | 10,290 | 10,510 | 2,000 |
| Wait for admission (join → `ADMITTED` seen) | 1 | 4.0 s | 6.1 s | 6.1 s | 6.1 s | 119 |
| | 2 | 14.0 s | 20.1 s | 22.1 s | 22.1 s | 512 |
| Hold | 1 | 55 | 118 | 137 | 164 | 178 |
| | 2 | 35 | 98 | 154 | 199 | 610 |
| Confirm hold | 1 | 26 | 54 | 73 | 96 | 200 |
| | 2 | 16 | 48 | 78 | 134 | 1,000 |
| Checkout (simulated payment) | 1 | 52 | 101 | 115 | 116 | 100 |
| | 2 | 30 | 78 | 103 | 136 | 500 |
| Whole journey (login → order) | 1 | 5.9 s | 8.8 s | 8.8 s | 8.9 s | 100 |
| | 2 | 23.6 s | 32.2 s | 32.2 s | 34.1 s | 500 |

Admission-wait and journey times are measured by the client and include the
2 s poll interval, so they are up to 2 s late.

## Zero oversell

After each run [`loadtest/oversell-check.sql`](../../loadtest/oversell-check.sql)
returned no rows for every violation check:

- no seat with more than one live ticket, and no more live tickets than seats.
  The first cannot happen in the table at all: `uk_tickets_seat_active` (V46)
  rejects a second live ticket, so a double sale would surface as a failed
  checkout instead, which the k6 thresholds (`server_errors`,
  `unexpected_responses`, both 0) would have caught;
- no `SOLD` seat without a live ticket, and no live ticket on a seat that is not `SOLD`;
- no seat with two live (`ACTIVE`/`CONFIRMED`) holds;
- no user over the per-user cap;
- every buyer has an `ADMITTED`/`COMPLETED` queue entry. The script never
  tries to skip the queue, so this shows admission bookkeeping is consistent,
  not that a bypass is refused (that is covered by the queue tests).

The check for a `COMPLETED` order with fewer live tickets than holds, the
`hold_gave_up` counter and the `checks`/`iterations` thresholds were added after
runs 1 and 2, on review. A short check run with the final script (300 users,
100 seats, `MAX_WAIT_S=30`) passed every threshold: 50 orders, 100 tickets,
6 `sold_out`, 6 `hold_gave_up`, 238 not admitted, 0 failed requests, and every
violation query, the new one included, returned no rows.

The database agreed with the client in both runs: run 1 had 200 `SOLD` seats,
200 live tickets and 100 `COMPLETED` orders; run 2 had 1,000, 1,000 and 500.

## Reading it

- **The waiting room is the throttle, as designed.** Admission lets in at most
  `min(available seats − already admitted, 50)` users per tick, so with 2 s
  ticks at most about 25 users a second reach the seat map. Behind it, hold,
  confirm and checkout stay under about 150 ms at p99 in both runs. Only users
  who had been admitted ever reached those endpoints, and none of them saw a
  5xx.
- **The burst cost is at the front door.** With every user arriving in the
  same second, login (bcrypt) and queue join queue up on the request threads
  and the 10-connection pool. At 2,000 arrivals, p95 login and join are about
  10 s. Nothing failed, but a real browser would show a spinner for that long.
  A ramp, not one instant, would be the gentler and more realistic arrival
  pattern.
- **Admission tracks free seats.** A few more users were admitted than could
  buy (119 for 100 orders in run 1; 512 for 500 in run 2). The extra users found the seats
  gone (sold out) or lost a race for a seat (409, retried). Once every seat was
  held or sold, admission stopped, so the other users waited until they gave
  up, which is what an empty sale should look like. `GET /queue/status` polling
  is most of the request count (about 100 k in run 2).

## What this does not show

- **Production capacity.** It is one laptop, one backend instance, no real
  network, the load generator on the same VM, and two runs. The numbers are for
  comparing changes on this setup, not for sizing.
- **The SSE stream.** The browser waits on `GET /queue/stream`; k6 polls
  `GET /queue/status` instead (stock k6 has no SSE client, and the stream stays
  open after admission). Pushing to thousands of open SSE connections is not
  measured.
- **More than one instance.** The SSE registry and the scheduled jobs are per
  JVM; see the README's known limitations.
- **Real payments.** Checkout uses the dev payment simulation; Stripe's
  latency, webhooks and failures are not in it.
- **A capped waiting room** (`queue_capacity`), the organisation daily sales
  cap, failed or cancelled payments, hold expiry and release, or the fraud
  rules firing. The users are well-behaved and only pay successfully.
- **Many source IPs.** All users share one IP, so the per-IP auth limit was
  raised; real traffic from many IPs would keep the default.
- **Sustained load.** Each run is one burst of a couple of minutes; nothing
  runs long enough to show leaks, pool exhaustion over time or Redis growth.
