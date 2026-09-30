# FairTix Deployment Guide

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 24+ | Container runtime |
| Docker Compose | v2 (bundled with Docker Desktop) | Multi-service orchestration |
| Java | 21 | Backend build (skip if only running via Docker) |
| Node.js | 20+ | Frontend build (skip if only running via Docker) |
| Stripe CLI | Latest | Local webhook forwarding (optional, dev only) |

---

## Quick Start (Local Dev)

```bash
cp .env.example .env
# Edit .env and fill in secrets (see Environment Variables below)
docker compose up --build
```

Services start at:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- MailHog UI: http://localhost:8025

The backend runs Flyway migrations automatically on startup. The first `up` may take 2–3 minutes to build images.

---

## Environment Variables

All backend variables are loaded from `.env` at the project root. Frontend variables are set in `frontend/webpages/.env` (copy from `frontend/webpages/.env.example`).

### Backend (`.env`)

| Variable | Required | Default | Description | Where to get it |
|----------|----------|---------|-------------|-----------------|
| `POSTGRES_USER` | Yes | `fairtix_user` | PostgreSQL username | Set freely |
| `POSTGRES_PASSWORD` | Yes | `changeme` | PostgreSQL password | Set freely |
| `POSTGRES_DB` | Yes | `fairtix` | PostgreSQL database name | Set freely |
| `POSTGRES_HOST` | No | `postgres` | DB hostname (Docker service name) | Leave default inside Docker |
| `POSTGRES_PORT` | No | `5432` | DB port | Leave default |
| `REDIS_HOST` | No | `redis` | Redis hostname | Leave default inside Docker |
| `REDIS_PORT` | No | `6379` | Redis port | Leave default |
| `JWT_SECRET` | Yes | — | JWT signing secret, must be ≥64 characters | `python3 -c "import secrets; print(secrets.token_hex(48))"` |
| `MAIL_HOST` | No | `mailhog` | SMTP host | `mailhog` for dev; real SMTP for prod |
| `MAIL_PORT` | No | `1025` | SMTP port | `1025` for MailHog; `587` for TLS SMTP |
| `MAIL_USERNAME` | No | `` | SMTP username | From your mail provider |
| `MAIL_PASSWORD` | No | `` | SMTP password | From your mail provider |
| `MAIL_AUTH` | No | `false` | Enable SMTP authentication | `true` for real SMTP |
| `MAIL_STARTTLS` | No | `false` | Enable STARTTLS | `true` for real SMTP on port 587 |
| `MAIL_FROM` | No | `noreply@fairtix.com` | From address on outbound email | Set to a verified sender |
| `APP_BASE_URL` | No | `http://localhost:3000` | Base URL for email links | Set to public URL in production |
| `RECAPTCHA_ENABLED` | No | `false` | Enable Google reCAPTCHA v2 | See `docs/recaptcha-setup.md` |
| `RECAPTCHA_SECRET` | If enabled | `` | reCAPTCHA server-side secret | Google reCAPTCHA Admin Console |
| `STRIPE_ENABLED` | No | `false` | Enable Stripe payments | See `docs/stripe-setup.md` |
| `STRIPE_SECRET_KEY` | If enabled | `` | Stripe secret key (`sk_test_…` or `sk_live_…`) | Stripe Dashboard → Developers → API keys |
| `STRIPE_WEBHOOK_SECRET` | If enabled | `` | Stripe webhook signing secret (`whsec_…`) | `stripe listen` output or Stripe Dashboard |
| `CORS_ALLOWED_ORIGINS` | Prod only | `http://localhost:3000,http://localhost:5173` | Comma-separated list of allowed CORS origins | Set to your Netlify URL, e.g. `https://fairtix.netlify.app` |
| `SPRING_PROFILES_ACTIVE` | Prod only | `` | Active Spring profile | Set to `prod` in production to disable payment simulation and enforce secure cookies |

### Frontend (`frontend/webpages/.env`)

| Variable | Required | Default | Description | Where to get it |
|----------|----------|---------|-------------|-----------------|
| `REACT_APP_API_URL` | No | `` | Backend base URL; leave empty to use CRA proxy | Set for separate deployments |
| `REACT_APP_RECAPTCHA_SITE_KEY` | If reCAPTCHA enabled | `` | reCAPTCHA v2 site key | Google reCAPTCHA Admin Console |
| `REACT_APP_STRIPE_PUBLISHABLE_KEY` | If Stripe enabled | `` | Stripe publishable key (`pk_test_…` or `pk_live_…`) | Stripe Dashboard → Developers → API keys |

---

## Services Overview

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|---------|
| `postgres` | `postgres:16` | 5432 | Primary relational store; data persisted in `fairtix-db-data` volume |
| `redis` | `redis:7-alpine` | 6379 | Seat hold TTLs, rate limiting, step-up verification state |
| `mailhog` | `mailhog/mailhog` | 1025 (SMTP), 8025 (Web UI) | Catches outbound email in dev; inspect at http://localhost:8025 |
| `backend` | Built from `./backend` | 8080 | Spring Boot API; depends on postgres + redis being healthy |

The frontend is served by the Create React App dev server (`npm start`) and is **not** in `docker-compose.yml`. Start it separately:

```bash
cd frontend/webpages && npm install && npm start
```

---

## Database Migrations

Flyway runs automatically when the backend starts. Migration files live in `backend/src/main/resources/db/migration/` and are applied in version order.

**Never modify an existing migration file.** Always create a new versioned file (`V{N}__description.sql`).

If a migration fails mid-run (e.g., interrupted during dev), Flyway marks it as failed and refuses to start:

```
FlywayException: Validate failed: Detected failed migration to version N
```

Repair with:

```bash
# Inside the backend container or with the backend datasource configured locally
mvn flyway:repair
```

Then re-run `docker compose up --build` to re-apply the fixed migration.

---

## Secrets Management

- **`JWT_SECRET`**: Must be at least 64 characters. Shorter values will fail at startup. Generate with:
  ```bash
  python3 -c "import secrets; print(secrets.token_hex(48))"
  ```
- **Stripe keys**: `sk_live_` keys must never be committed. `.env` is in `.gitignore` — verify this before pushing. Only `.env.example` (with placeholders) is tracked.
- **reCAPTCHA keys**: Site keys are domain-scoped. A key registered for `localhost` will not work on your production domain and vice versa. Register separate keys per environment.
- **`STRIPE_WEBHOOK_SECRET`**: Leaving this blank with `STRIPE_ENABLED=true` causes all webhook deliveries to return 400 (signature verification fails). The backend logs a warning at startup if this is the case.

---

## Deploying to Netlify + Railway (Recommended for Course Demo)

> **Status 2026-09-29: OPTIONAL, legacy path, not maintained.** This path (and `cloudbuild.yaml` for Cloud Run) was written for the course demo. The planned deployment is the Terraform-defined Azure Container Apps environment in milestone L3 of [STRATEGIC_ROADMAP.md](STRATEGIC_ROADMAP.md). Local Docker Compose above remains the supported way to run FairTix.

This is the fastest path to a publicly reachable deployment using free-tier platforms.

### Architecture

```
Browser → Netlify (static frontend) → Railway (Spring Boot API)
                                            ├── Railway PostgreSQL
                                            └── Railway Redis
```

### Step 1 — Deploy the Backend to Railway

1. Create a free account at [railway.app](https://railway.app) and start a new project.
2. Click **Deploy from GitHub repo** → select this repository → set the **Root Directory** to `backend/`.
3. Railway detects the `Dockerfile` automatically and builds it.
4. Add a **PostgreSQL** plugin and a **Redis** plugin from the Railway dashboard. Railway injects connection variables automatically.
5. In the Railway service's **Variables** tab, add all required backend environment variables from the table above. At minimum:
   - `JWT_SECRET` (generate: `python3 -c "import secrets; print(secrets.token_hex(48))"`)
   - `SPRING_PROFILES_ACTIVE=prod`
   - `CORS_ALLOWED_ORIGINS=https://<your-netlify-subdomain>.netlify.app`
   - `APP_BASE_URL=https://<your-netlify-subdomain>.netlify.app`
   - `MAIL_HOST`, `MAIL_PORT`, `MAIL_AUTH`, `MAIL_STARTTLS`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` (use a free SMTP provider such as Brevo/Sendinblue or Gmail App Password)
6. Railway provides a public HTTPS URL for your backend (e.g. `https://fairtix-backend.up.railway.app`). Copy it.

> **Note:** The `prod` Spring profile disables payment simulation and enforces HTTPS-only cookies. Flyway migrations run automatically on first deploy.

### Step 2 — Deploy the Frontend to Netlify

1. Create a free account at [netlify.com](https://netlify.com).
2. Click **Add new site → Import an existing project** → connect your GitHub repo.
3. Set the **Base directory** to `frontend/webpages` and **Build command** to `npm run build` and **Publish directory** to `build`.
4. In **Site configuration → Environment variables**, add:
   - `REACT_APP_API_URL=https://<your-railway-backend-url>`
5. Add a `_redirects` file for SPA routing (already committed at `frontend/webpages/public/_redirects`):
   ```
   /* /index.html 200
   ```
6. Deploy. Netlify gives you a URL like `https://fairtix.netlify.app`.

### Step 3 — Update Railway CORS

Go back to Railway and update `CORS_ALLOWED_ORIGINS` to match your actual Netlify URL, then redeploy the backend.

### Step 4 — Load Seed Data

```bash
# Get a shell on the Railway Postgres instance via Railway CLI:
railway run psql $DATABASE_URL < seed_test_events.sql

# OR connect directly with psql using the DATABASE_URL from Railway's Variables tab
psql "<RAILWAY_DATABASE_URL>" < seed_test_events.sql
```

Then create your admin user:
```bash
psql "<RAILWAY_DATABASE_URL>" -c "UPDATE users SET role = 'ADMIN' WHERE email = 'your@email.com';"
```

### Step 5 — Verify

```bash
curl https://<your-railway-url>/actuator/health
# Expected: {"status":"UP"}
```

Open your Netlify URL in a browser, log in, and complete a purchase end-to-end.

---

## Production Deployment Options

### Cloud VM (docker compose)

1. Provision a VM (Ubuntu 22.04+) with Docker and Docker Compose installed.
2. Clone the repo or copy the project directory.
3. Populate `.env` and `frontend/webpages/.env` with production values.
4. Update `APP_BASE_URL` to your public domain.
5. Replace MailHog with a real SMTP provider (`MAIL_HOST`, `MAIL_AUTH=true`, `MAIL_STARTTLS=true`).
6. Run:
   ```bash
   docker compose up -d --build
   ```
7. Point a reverse proxy (nginx, Caddy) at port 8080 (API) and 3000 (frontend), or serve the frontend as a static build behind the same proxy.

### Static frontend build

To serve the frontend as a static build rather than the CRA dev server:

```bash
cd frontend/webpages
REACT_APP_API_URL=https://api.yourdomain.com npm run build
# Serve the build/ directory with any static file server
```

### Container registry push pattern

```bash
docker build -t your-registry/fairtix-backend:latest ./backend
docker push your-registry/fairtix-backend:latest
# Update docker-compose.yml image: reference and pull on target host
```

---

## Health Checks

The backend exposes readiness via the `/auth/me` endpoint:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auth/me
# 401 = backend is up (unauthenticated); 000 = backend is not reachable
```

If Spring Boot Actuator is enabled (`management.endpoints.web.exposure.include=health`), use:

```bash
curl http://localhost:8080/actuator/health
```

Docker Compose already defines healthchecks for `postgres` and `redis`. The `backend` service waits for both to pass before starting.

---

## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every pull request to `main`:

| Job | What it does |
|-----|-------------|
| `test` | Builds the backend with Maven, runs all unit and integration tests against a real Redis instance |
| `frontend-test` | Installs frontend deps (`npm ci`), runs Jest tests with coverage |
| `dependency-scan` | Runs OWASP Dependency-Check against the backend for known CVEs |
| `container-scan` | Builds the backend Docker image and runs Trivy for CRITICAL/HIGH vulnerabilities |

**There is no auto-deploy step.** Deployments are manual. TODO: add a deploy job gated on `main` merge.

---

## Troubleshooting

**Port already in use (5432, 6379, 8080, 3000)**
Stop or remove any local Postgres/Redis/Node processes using those ports, or change the host-side port mapping in `docker-compose.yml`.

**Flyway checksum mismatch**
You modified an already-applied migration file. Revert the file to its original content, or in dev-only environments run `mvn flyway:repair` to clear the failed state, then re-migrate.

**Redis connection refused on backend startup**
The backend depends on redis being healthy before starting, but Docker Compose `depends_on` with healthcheck only delays the container start. If Redis is slow, the backend may still fail. Re-run `docker compose up` — it will pick up where it left off.

**JWT cookie not set (login appears to succeed but `/auth/me` returns 401)**
Browsers only send `HttpOnly; Secure` cookies over HTTPS. In local dev, the `Secure` flag is omitted, but if you are testing over a non-localhost hostname you need HTTPS. For local dev, always use `http://localhost:3000`.

**Stripe webhook returning 400 (`No signatures found matching the expected signature`)**
`STRIPE_WEBHOOK_SECRET` is blank or incorrect. If running locally, regenerate it from the `stripe listen` output:
```bash
stripe listen --forward-to localhost:8080/api/webhooks/stripe
# Copy the "webhook signing secret" printed to the terminal into STRIPE_WEBHOOK_SECRET in .env
# Restart the backend container after updating .env
```

**reCAPTCHA not appearing after 3 failed logins**
`RECAPTCHA_ENABLED` is `false` (the default). Set it to `true` and ensure `RECAPTCHA_SECRET` and `REACT_APP_RECAPTCHA_SITE_KEY` are both set. The backend logs a warning at startup if the secret is blank.

---

## Rollback

Flyway does not support automatic rollback of applied migrations. To undo a migration in dev:

1. Drop the affected tables/columns manually (psql or a DB client).
2. Delete the corresponding row from the `flyway_schema_history` table.
3. Fix or remove the migration file, then restart the backend.

In production, prefer writing a new forward migration (e.g., `V{N+1}__undo_something.sql`) rather than touching applied history.
