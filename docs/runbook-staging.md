# Staging runbook

Companion to issue [#165](https://github.com/firegiant9000/FairTix/issues/165). Walk through this once per environment; revisit when a piece breaks.

## What lives where

| Component | Provider | Notes |
|---|---|---|
| Backend service | Railway (hobby plan ~$5/mo) | Spring Boot container built from `backend/Dockerfile` |
| Frontend site | Netlify (free) | Built from CRA, deploys on push to `develop` |
| Postgres | Railway-managed or Neon free tier | **Must** have `staging` in its name (see `scripts/reset-staging.sh`) |
| Redis | Railway-managed or Upstash free tier | Used for seat holds + rate limits |
| Mail | Mailtrap sandbox (free) | Captures all outbound mail — no real users get emails |
| DNS | Cloudflare or registrar | `staging.fairtix.io` + `api.staging.fairtix.io` |
| Stripe | Stripe test mode | sk_test_* keys, separate webhook signing secret from prod |

Budget target: ~$15/month all-in.

## First-time setup

### 1. Create the `develop` branch

```
git checkout main
git pull
git checkout -b develop
git push -u origin develop
```

`develop` is the integration branch. Feature branches PR into `develop`; `develop` auto-deploys to staging; `main` only updates after staging smoke tests pass.

### 2. Provision the backend on Railway

1. New project → "Deploy from repo" → select the FairTix repo, `develop` branch.
2. Set the root dir to `backend/` so Railway uses `backend/Dockerfile`.
3. Add a Postgres service in the same project. Copy the connection string into `SPRING_DATASOURCE_URL`.
4. Add a Redis service. Copy host/port/password into `SPRING_REDIS_*`.
5. Copy every value from `.env.staging.example` into the Railway env panel. Replace `<host>` / `<port>` placeholders with real values.
6. Set `SPRING_PROFILES_ACTIVE=staging` so `application-staging.properties` activates.
7. Deploy. Watch logs for `Flyway` lines — all migrations should apply against the empty DB.

### 3. Provision the frontend on Netlify

1. New site → connect repo → set the production branch to `develop`.
2. Build command: `cd frontend/webpages && npm ci && npm run build`. Publish dir: `frontend/webpages/build`.
3. Env vars: `REACT_APP_API_URL=https://api.staging.fairtix.io` (or whatever your backend domain resolves to).
4. After first deploy, point `staging.fairtix.io` at Netlify via CNAME.

### 4. Stripe webhook

1. Stripe Dashboard → Developers → Webhooks → "Add endpoint".
2. URL: `https://api.staging.fairtix.io/api/payments/stripe/webhook`.
3. Subscribe to at least: `payment_intent.succeeded`, `payment_intent.payment_failed`, `charge.refunded`.
4. Copy the signing secret into Railway as `STRIPE_WEBHOOK_SECRET`.

### 5. Smoke test

```
curl https://api.staging.fairtix.io/actuator/health
# expect: {"status":"UP"}

# Authenticated deep check — substitute your admin cookie
curl -H "Cookie: fairtix_token=..." https://api.staging.fairtix.io/_health/deep
# expect: per-component status with stripe livemode=false
```

Run `scripts/demo-seed.sh` against the staging API to seed demo users + events.

## Recurring operations

### Resetting staging state

When the DB gets weird, wipe and reapply migrations:

```
export STAGING_DB_URL='postgresql://user:pw@host:port/fairtix_staging'
./scripts/reset-staging.sh
# answer 'reset' at the prompt
```

The script refuses unless `STAGING_DB_URL` contains the literal substring `staging` — last-ditch guard against pointing it at prod by accident.

After the schema is dropped, restart the Railway backend service so Flyway re-applies migrations against the fresh schema, then re-run `scripts/demo-seed.sh`.

### Cookie domain decision (open)

Cross-subdomain cookies (`api.staging.fairtix.io` ↔ `staging.fairtix.io`) need `Domain=.fairtix.io` and `SameSite=None; Secure`. The simpler alternative is to put both frontend and backend behind one origin via a Netlify `_redirects` or Railway reverse-proxy entry that maps `/api/*` to the backend. Pick one path and document in [docs/adr/0001-cookie-auth.md](adr/0001-cookie-auth.md) — currently unresolved.

### What to do when staging is "broken"

1. Hit `/_health/deep` (admin auth required) — find the DOWN component.
2. If DB is DOWN, check Railway/Neon dashboard for the postgres service.
3. If Redis is DOWN, the seat-hold module will degrade; restart the redis service.
4. If Stripe is DOWN, verify the test keys haven't been rotated.
5. If Mail is DOWN, Mailtrap occasionally rate-limits — check their dashboard.

If you cannot identify the cause within 15 minutes, run `scripts/reset-staging.sh` and let staging come back from a clean slate. It is the staging environment; there is nothing in it that matters.
