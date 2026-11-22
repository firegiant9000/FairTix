# FairTix Demo Checklist

## Environment Setup

### 1. Start services

```bash
docker compose up --build
```

Services started:
- Backend (Spring Boot, dev profile): http://localhost:8080
- PostgreSQL 16: localhost:5432
- Redis 7: localhost:6379
- MailHog (email capture): http://localhost:8025

Flyway migrations run automatically on backend startup. Wait for the log line:
`Successfully applied N migrations to schema "public"` before proceeding.

### 2. Verify health

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

### 3. Seed demo users (and events, if missing)

From the repo root, with `.env` in place:

```bash
./scripts/demo-seed.sh          # seed users, print the Bayou Pelicans vs Harbor Foxes ids
./scripts/demo-seed.sh reset    # between runs: release that event's seats, delete its holds and tickets
```

It registers `admin@fairtix.demo` (promoted to ADMIN), `userA@fairtix.demo` and
`userB@fairtix.demo`, all with the password `Demo#2026!`, and marks them email-verified.
It refuses any API other than localhost: the password is published. The demo events
come from Flyway (`V27__seed_demo_events.sql`, `V29__seed_extra_demo_events.sql`), and
`V55__fictional_demo_seed_names.sql` gives them and their venues fictional names. V29
seeds nothing on a database that has no users yet, so on a fresh stack the script
re-applies V29 and then V55. V55 leaves a seed venue unrenamed while an event that is not
a seed event uses it. No other seed step is needed.

Seeded events used below:
- **Bayou Pelicans vs Harbor Foxes**: no queue, cap 6
- **Crescent Jazz Weekend**: no queue, cap 4
- **Nova Lane - Arena Tour Finale**: queue required, cap 2
- **Sunfield Festival - Day 1**: queue required, cap 1

To create an admin by hand instead (any account, for example one made at `/signup`):

```bash
# psql user and database come from .env (POSTGRES_USER, POSTGRES_DB)
docker compose exec postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "UPDATE users SET role = '"'"'ADMIN'"'"' WHERE email = '"'"'you@example.com'"'"';"'
```

Log in again afterwards so the session picks up the role. Later admins are promoted
from `/admin/users` (`PATCH /api/admin/users/{id}/promote`). The deployment variant is
in [DEPLOY.md](../DEPLOY.md).

### 4. Start the frontend

```bash
cd frontend/webpages && npm start
# Runs on http://localhost:3000
```

---

## Works-on-My-Machine Verification

- [ ] `docker compose ps` shows all 4 services as `running`
- [ ] `/actuator/health` returns `{"status":"UP"}`
- [ ] Flyway applied every migration: the count in the backend log matches
      `ls backend/src/main/resources/db/migration | wc -l`
- [ ] `SELECT count(*) FROM events;` returns ≥ 3
- [ ] `SELECT count(*) FROM seats;` returns ≥ 50
- [ ] MailHog UI loads at http://localhost:8025
- [ ] Frontend loads at http://localhost:3000 without console errors
- [ ] Admin user exists with `role = 'ADMIN'` in the database

---

## User Journey

### Register → Browse → Queue → Hold → Checkout → Ticket → ICS Export

1. **Register** — navigate to `/signup`, create a new account (or log in as `userA@fairtix.demo`)
2. **Browse events** — navigate to `/events`, verify the seeded events appear
3. **Event without queue (Bayou Pelicans vs Harbor Foxes)**
   - Open event detail at `/events/:id`
   - Select a seat
   - Confirm hold is created (10-min TTL); navigate to `/my-holds` to verify
4. **Event with queue (Nova Lane - Arena Tour Finale)**
   - Open event detail
   - Join the queue; observe SSE-powered position updates in the UI
   - Wait for admission (or reduce queue in admin panel to trigger admission)
   - After admission, proceed to seat selection
5. **Checkout**
   - Navigate to `/checkout`
   - Use simulated payment (dev profile only): set outcome to `SUCCESS`
   - Complete order
6. **Ticket**
   - Navigate to `/my-tickets/:ticketId`
   - Verify ticket status is `VALID`
7. **ICS export**
   - Click "Add to Calendar" on the ticket detail page
   - Verify `.ics` file downloads and opens in a calendar app
   - File served from `GET /api/tickets/:id/calendar.ics`

---

## Admin Journey

### Publish Event, View Fraud Audit, Manage Refunds

1. **Admin dashboard** — navigate to `/admin`
2. **Event management**
   - Go to `/admin/events`
   - Create a new event or inspect a seeded event
   - Verify seat map at `/admin/events/:id/seats`
3. **View fraud audit** — navigate to `/admin/fraud`
   - Verify audit log entries appear for transfer, payment, and hold events
   - Check risk tier display (LOW / MEDIUM / HIGH / CRITICAL)
4. **Manage refunds** — navigate to `/admin/refunds`
   - Submit a refund request as a user (`/refunds`), then approve/deny it as admin
   - Verify that HIGH/CRITICAL risk score blocks auto-approval (manual review required)
5. **User management** — navigate to `/admin/users`
   - Verify user list; test promote endpoint if needed

---

## Edge Cases to Demo

### Transfer

1. Log in as User A, complete a purchase on Bayou Pelicans vs Harbor Foxes
2. Go to ticket detail, initiate a transfer to User B's email
3. Log in as User B, accept the transfer at `/my-tickets` (incoming transfers)
4. Verify ticket ownership changed; check MailHog for transfer notification emails
5. **Velocity limit:** attempt 6 transfers from the same user within 24h — expect `429` on the 6th

### Rate limit hit

1. Rapidly call any rate-limited endpoint (e.g., seat hold) more than the configured limit
2. Observe `429 Too Many Requests` response
3. Wait for the window to reset; verify access resumes

### Fraud score block

1. In the database, set a user's risk tier to CRITICAL:
   ```sql
   UPDATE risk_scores SET tier = 'CRITICAL' WHERE user_id = '<uuid>';
   ```
2. Attempt to initiate a ticket transfer as that user
3. Verify the request is rejected with an appropriate error and an audit log entry is written

### Purchase cap enforcement

1. On Sunfield Festival - Day 1 (`max_tickets_per_user = 1`), buy one ticket, then attempt a second
2. Verify the API rejects the request (the cap counts held, bought and comped tickets)

---

## Known Limitations for Demo

- Payment is simulated whenever Stripe is off, in any profile except `prod`, which refuses it. Choosing the outcome (`SUCCESS`, `FAILURE`, `CANCELLED`) works only in the `dev` profile, the one Compose uses; elsewhere a chosen outcome returns `400 Bad Request`.
- Queue admission is triggered by a scheduler; during a live demo, lower the queue capacity or manually advance the queue via the database to speed it up.
- ICS calendar export requires a ticket in `VALID` status — cancelled or transferred tickets will not have an active export link.
- MailHog captures all outbound email in dev. No real emails are sent.

---

## Route Accessibility Quick Check

| Route | Auth required | Admin only |
|-------|--------------|-----------|
| `/events` | No | No |
| `/events/near-me` | No | No |
| `/events/:id` | No | No |
| `/my-holds` | Yes | No |
| `/checkout` | Yes | No |
| `/my-tickets/:id` | Yes | No |
| `/refunds` | Yes | No |
| `/support` | Yes | No |
| `/admin` | Yes | Yes |
| `/admin/events` | Yes | Yes |
| `/admin/fraud` | Yes | Yes |
| `/admin/refunds` | Yes | Yes |
| `/admin/users` | Yes | Yes |
| `/admin/venues` | Yes | Yes |
| `/admin/performers` | Yes | Yes |
