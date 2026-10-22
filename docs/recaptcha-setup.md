# reCAPTCHA v2 Setup Guide

FairTix integrates Google reCAPTCHA v2 ("I'm not a robot" checkbox) to throttle brute-force login
attempts and to gate step-up verification. The feature is **disabled by default** — no CAPTCHA is
shown until you opt in.

---

## 1. Create a reCAPTCHA v2 site key

1. Go to <https://www.google.com/recaptcha/admin/create>.
2. Choose **reCAPTCHA v2 → "I'm not a robot" Checkbox**.
3. Add your domains:
   - `localhost` for local development
   - Your production domain (e.g. `fairtix.example.com`) for production
4. Submit — Google gives you a **Site Key** (public, goes in the frontend) and a **Secret Key**
   (private, stays on the server).

---

## 2. Configure environment variables

### Root `.env` (backend)

```env
RECAPTCHA_ENABLED=true
RECAPTCHA_SECRET=<your-secret-key>
```

### `frontend/webpages/.env` (frontend)

```env
REACT_APP_RECAPTCHA_SITE_KEY=<your-site-key>
```

> **Never commit real keys.** Both `.env` files are in `.gitignore`. Use `.env.example` as a
> template — it ships with `RECAPTCHA_ENABLED=false` and empty placeholders.

---

## 3. Behavior

| State | What happens |
|-------|-------------|
| `RECAPTCHA_ENABLED=false` (default) | No CAPTCHA is shown anywhere and login skips token checks. Step-up **fails closed**: `POST /auth/step-up/verify` returns 503 `STEP_UP_UNAVAILABLE` and never marks the user verified, so a user the fraud gate challenges can't check out or hold seats until reCAPTCHA is configured. |
| `RECAPTCHA_ENABLED=true`, secret blank | Backend logs a startup warning. Treated as disabled, so step-up fails closed as above. |
| `RECAPTCHA_ENABLED=true`, valid keys | CAPTCHA is shown after **3 consecutive failed login attempts** and is always required for step-up verification. |

### When the CAPTCHA appears

- **Login**: After the third consecutive failed attempt for the same account, the login form
  renders the reCAPTCHA widget. The token is sent as `X-Recaptcha-Token` header with the next
  login request.
- **Step-up verification** (`POST /auth/step-up/verify`): The CAPTCHA widget is always rendered
  in the step-up modal in `Checkout.js`. The token must be present for the request to succeed.

---

## 4. Local development without reCAPTCHA

Leave `RECAPTCHA_ENABLED=false` in your local `.env`. The backend will bypass all CAPTCHA checks
and the frontend will not render the widget.

---

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Startup log: *"reCAPTCHA is enabled but RECAPTCHA_SECRET is blank"* | `RECAPTCHA_ENABLED=true` but secret not set | Set `RECAPTCHA_SECRET` in `.env` |
| CAPTCHA widget shows but always fails | Site key / secret key mismatch or wrong domain registered | Regenerate keys with the correct domain in Google Admin Console |
| CAPTCHA not showing after 3 failures | `RECAPTCHA_ENABLED=false` or `REACT_APP_RECAPTCHA_SITE_KEY` not set | Enable the flag and set the site key in `frontend/webpages/.env` |
| `RecaptchaUnavailableException` in logs | Backend cannot reach `google.com/recaptcha/api/siteverify` | Check outbound network; in CI/offline envs keep `RECAPTCHA_ENABLED=false` |
