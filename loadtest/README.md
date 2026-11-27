# Load test: queue → hold → checkout (k6)

One k6 scenario, [`queue-checkout.js`](queue-checkout.js), for an on-sale.
Every virtual user is a separate account. It logs in, joins the waiting room of
one queued event, waits to be admitted, then holds seats, confirms the holds
and pays through the dev payment simulation. All users start at once. Results
and how to read them are in
[docs/experiments/l2-load-queue-checkout.md](../docs/experiments/l2-load-queue-checkout.md).

k6 runs from the `grafana/k6` image; nothing is installed into the project.

## Run it

From the repo root, with Docker running:

```bash
# 1. A .env for this stack. The overlay forces MailHog, Stripe off and reCAPTCHA
#    off whatever it says, but it still needs POSTGRES_* and a JWT_SECRET.
cp .env.example .env    # then set JWT_SECRET (64+ characters)

# 2. Stop the dev stack first: this one uses the same host ports (8080, 5432, …).
#    Then start a separate stack: own project name, containers and database volume.
LT="docker compose -p fairtix-loadtest -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml"
$LT up --build -d
curl -s http://localhost:8080/actuator/health    # wait for {"status":"UP"}

# 3. Seed 500 users and a queued event with 200 seats, 2 per user. Prints EVENT_ID.
./loadtest/seed.sh 500 200 2

# 4. Run k6 on the Compose network (BASE_URL=http://backend:8080).
#    In Git Bash on Windows, prefix with MSYS_NO_PATHCONV=1 or /scripts/... is rewritten.
$LT run --rm k6 run --summary-export=/scripts/results/summary.json \
  -e EVENT_ID=<id> -e USERS=500 queue-checkout.js

# 5. Check nothing was oversold. Every "violations" section must be empty.
docker exec -i fairtix-lt-postgres psql -U fairtix_user -d fairtix \
  -v event_id=<id> < loadtest/oversell-check.sql

# 6. Tear down, deleting the load-test database.
$LT down -v
```

`seed.sh` makes a new event every time, so you can run step 3 and 4 again on the
same stack. Users are reused (registering again returns 409).

k6 options (`-e NAME=value`): `USERS` (VUs, one user each; must not exceed the
number seeded), `SEATS_PER_USER` (default 2; keep at or under the event cap),
`POLL_S` (queue status poll interval, default 2), `MAX_WAIT_S` (give up waiting
for admission, default 300).

## What the overlay changes, and why

[`docker-compose.loadtest.yml`](docker-compose.loadtest.yml) only sets
environment variables for this stack. No default in `application.properties`
changes.

| Override | Default | Why |
|---|---|---|
| `RATELIMIT_AUTH=1000000` | 10 per minute | The whole auth tier (`/auth/*`, including login, register, verify, password reset and step-up). Login and register are limited **per IP**, and every k6 user comes from one container IP. Everything else stays limited per user at its default (holds and checkout 20/min, seats 60/min, other 100/min). |
| `QUEUE_ADMISSION_INTERVAL_MS=2000` | 30000 | At 30 s ticks the run mostly measures waiting. The batch size stays at 50. |
| `MAIL_*` → MailHog, `STRIPE_ENABLED=false`, `RECAPTCHA_ENABLED=false` | from `.env` | A load run must never send real mail or touch Stripe. Every registration, and each admission unless the user's notification preferences suppress it, sends a mail. |

Left on deliberately: the per-user rate limits, the per-user ticket cap, the
5-active-holds limit, step-up (fraud) checks, the Origin check and email
verification (the seed marks the load users verified in SQL, as
`scripts/demo-seed.sh` does).

## Things the script has to do

- Send `Origin: http://localhost:3000` on every POST that carries the auth
  cookie, or `OriginCheckFilter` answers 403.
- Send `simulatedOutcome: SUCCESS` at checkout; without it the dev simulation
  fails about 20% of payments at random.
- Poll `GET /queue/status` for admission. The browser uses the SSE stream, but
  stock k6 has no SSE client and the stream stays open after admission; the
  status endpoint returns the same payload.
