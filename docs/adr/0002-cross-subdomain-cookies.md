# ADR 0002 — Cross-subdomain auth cookies for staging and prod

- Status: Accepted
- Date: 2026-05-22
- Issue: [#165](https://github.com/firegiant9000/FairTix/issues/165)
- Supersedes the open question in [ADR 0001 §Cross-subdomain note](0001-cookie-auth.md#cross-subdomain-note)

## Context

[ADR 0001](0001-cookie-auth.md) committed FairTix to HttpOnly cookies with `SameSite=Strict` for auth. That works trivially when the SPA and the API share an origin (the docker-compose dev setup, where everything hangs off `localhost`). It does **not** work for the staging split being stood up in #165: `staging.fairtix.io` (Netlify-hosted frontend) and `api.staging.fairtix.io` (Railway-hosted backend) are cross-origin to the browser, so `SameSite=Strict` strips the cookie from credentialed XHRs and `/auth/me` returns 401 on every load.

Two reasonable shapes resolve this. Until this ADR they were both open.

## Decision

**We reverse-proxy `/api/*` from the frontend host (Netlify) to the backend so the browser only ever sees one origin.** Cookies stay scoped to `staging.fairtix.io` (not `.fairtix.io`), `SameSite=Strict` is preserved, and the API surface is reachable as same-origin from JS.

In `netlify.toml`:

```toml
[[redirects]]
  from = "/api/*"
  to   = "https://api.staging.fairtix.io/api/:splat"
  status = 200    # rewrite, not 30x
  force  = true
```

(Production gets the same rewrite from `app.fairtix.io` → `api.fairtix.io` when prod stands up.)

## Alternatives considered

### A. Widen the cookie `Domain` to `.fairtix.io` with `SameSite=None; Secure`

- Works without infrastructure changes.
- Exposes the auth cookie to **every** subdomain — including any future marketing site, blog, status page, or third-party app deployed under `*.fairtix.io`. One compromised subdomain leaks production sessions.
- `SameSite=None` re-opens the class of CSRF the existing posture (Strict + no CSRF tokens, see [ADR 0001 §CSRF mitigation](0001-cookie-auth.md#csrf-mitigation)) deliberately relies on. Adopting it would force us to re-introduce CSRF token middleware, which is a larger blast radius than the reverse-proxy change.
- Some privacy modes (Brave shields, Safari ITP under certain conditions, the upcoming Chrome third-party-cookie phase-out) increasingly degrade or block `SameSite=None` even when the cookie is first-party-set, and the failure mode is silent (login appears to succeed, then `/auth/me` 401s).

### B. (Chosen) Reverse-proxy `/api/*` from the frontend host

- Single origin from the browser's perspective; `SameSite=Strict` continues to work end-to-end.
- Keeps cookie scope narrow (just `staging.fairtix.io`).
- Adds one Netlify rewrite rule and zero application code.
- Backend stays on its own host so we keep deploy independence and don't accidentally bundle the SPA into the Spring container.
- Costs: one extra network hop per API call inside Netlify's edge (~5–15ms p50), and Netlify counts proxied traffic against bandwidth quota. Both are acceptable at staging volume and remain acceptable at first-paying-customer scale.

## Consequences

- `frontend/webpages/.env.staging` sets `REACT_APP_API_URL=""` (empty — calls become same-origin `/api/...`). The `api/client.js` base-URL logic already concatenates an empty prefix.
- Backend `app.allowed-origins` is set to `https://staging.fairtix.io` (just the frontend origin) — the API host itself is never called cross-origin from a browser. Direct calls to `api.staging.fairtix.io` from a non-allowed origin will be rejected by CORS; this is intentional.
- Cookies on the backend response continue to use `SameSite=Strict; Secure; HttpOnly`. **No `Domain` attribute is set** — browser scopes them to `staging.fairtix.io` automatically because that's the request origin (the proxy forwards `Host: staging.fairtix.io`).
- Health checks and Stripe webhooks must hit the API host directly (`api.staging.fairtix.io/_health/deep`, `api.staging.fairtix.io/api/payments/webhook`) — they have no cookies and don't care about origin.
- If we later need a third surface (e.g. an embeddable widget hosted on a partner's domain) we accept that **it cannot use the auth cookie** and must use a different mechanism (e.g. signed widget tokens). This is fine and tracked under M6.

## Enforcement

- `netlify.toml` is committed; no rewrite, no auth — that surfaces immediately on deploy.
- Frontend smoke test in CI hits `/api/healthz` through the proxy and asserts a cookie-bearing response carries the auth cookie back when authenticated.
- Backend `SecurityConfig.allowedOrigins` is unit-tested to reject `*` and to require exact match.
- The "Cross-subdomain note" in [ADR 0001](0001-cookie-auth.md#cross-subdomain-note) should be updated to point here once this ADR is merged.

## Migration notes

- This ADR is staging-first; production migrates the same way the first time a paying customer's tenant goes live.
- The proxy is a runtime decision — no application code changed. Rolling back means reverting `netlify.toml` and switching `REACT_APP_API_URL` back to the API host.
