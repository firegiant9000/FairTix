# ADR 0001 — HttpOnly cookie authentication

- Status: Accepted
- Date: 2026-05-21
- Issue: [#164](https://github.com/firegiant9000/FairTix/issues/164)

## Context

FairTix previously stored JWTs in `sessionStorage` and sent them on every request via `Authorization: Bearer <token>`. That model was migrated to HttpOnly cookies (`fairtix_token`, `fairtix_refresh`) with frontend hydration through `/auth/me`. A full audit of `frontend/webpages/src` in May 2026 found zero `sessionStorage` usages and zero `Authorization: Bearer` headers. The migration is complete; this ADR exists to (1) explain why we chose this shape and (2) prevent regression.

## Decision

- Access and refresh tokens are issued as cookies with `HttpOnly`, `Secure`, and `SameSite=Strict` (Lax acceptable in dev) attributes.
- The browser never sees the JWT. All API calls go through `frontend/webpages/src/api/client.js` which sets `credentials: 'include'` and adds no `Authorization` header.
- On boot the SPA calls `/auth/me` to hydrate the user from the cookie. A `401`/`403` response triggers a silent `/auth/refresh` attempt; if refresh fails a `auth:session-expired` event is dispatched and the AuthContext clears state.
- `localStorage` remains acceptable for **non-auth** UI state (seat-picker preferences, last-viewed venue, etc.). Only `sessionStorage` for auth and `Authorization: Bearer` headers are banned.

## Rationale

1. **XSS blast radius.** A token in `sessionStorage` is readable by any script that runs in the page (a compromised dependency, a misconfigured 3rd-party widget). An HttpOnly cookie is not. JWT exfiltration is the single highest-impact XSS payload; closing it removes a class of incident.
2. **Refresh flow simplicity.** With cookies the refresh endpoint sets a new access cookie in the response — no client-side token storage, no race between tabs reading/writing the same key.
3. **Multi-tab consistency.** Cookies are shared across tabs of the same origin automatically; `sessionStorage` is per-tab and required ad-hoc broadcast channels to stay in sync.

## CSRF mitigation

CSRF protection in Spring Security is disabled (see `SecurityConfig.java` line 67). This is acceptable because:

- The API is fully stateless (`SessionCreationPolicy.STATELESS`). There is no server-side session that a forged cross-origin request could ride on outside of the cookie itself.
- Cookies are issued with `SameSite=Strict` in prod and `Lax` in dev, blocking the cross-site cookie attachment that classic CSRF relies on.
- CORS is locked to an explicit allowed-origins list with `allowCredentials=true` (`SecurityConfig.java` line 36–42). Browsers refuse credentialed requests from non-allowed origins.

If we ever relax `SameSite` (e.g. for a 3rd-party embed) we must re-enable CSRF tokens — track in a follow-up ADR.

## Cross-subdomain note

Staging (#165) splits `staging.fairtix.io` (frontend) from `api.staging.fairtix.io` (backend). The two viable shapes — `.fairtix.io`-scoped cookies with `SameSite=None; Secure`, vs reverse-proxying `/api` through the frontend host so both share an origin — are resolved in [ADR 0002](0002-cross-subdomain-cookies.md). **The reverse-proxy path was chosen**, which preserves `SameSite=Strict` and keeps the cookie scoped to the SPA host.

## Enforcement

- ESLint `no-restricted-globals` rule in `frontend/webpages/package.json` rejects any new reference to `sessionStorage`.
- ESLint `no-restricted-syntax` rule rejects any new `Bearer ` string literal in source.
- CI step in `.github/workflows/ci.yml` greps the frontend tree and fails the build if either pattern reappears (belt-and-suspenders for cases ESLint misses, like dynamic property access).

## Consequences

- Future engineers adding token-based auth (e.g. machine-to-machine API keys in M6) cannot reuse the `Authorization: Bearer` pattern in the SPA. They must add a separate code path and update this ADR.
- Tests cannot stub auth by writing to `sessionStorage`. Instead, mock `/auth/me` in `setupTests.js` or stub the AuthContext.
- The session-expiry UX depends on the `auth:session-expired` custom event being handled. The current modal is functional but minimal; UX upgrade tracked in #164's "extras worth bundling" list.
