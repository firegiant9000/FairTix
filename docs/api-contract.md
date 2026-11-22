# FairTix API Contract

> **Base URL:** `http://localhost:8080`
> **Interactive docs:** `http://localhost:8080/swagger-ui.html`
> **OpenAPI spec:** `http://localhost:8080/v3/api-docs`
> Swagger UI and the OpenAPI spec are disabled under the `prod` profile (404); dev and
> staging still serve them.

> **This is a partial, hand-written overview** of the core buyer flow (auth, events,
> seats, holds, payments, orders, tickets, transfers, refunds, the queue and the
> Stripe webhooks). The full and current contract is the OpenAPI spec above, which
> is generated from the controllers. Organizer, box-office, reporting, branding,
> venue, performer and support endpoints are documented there only. Where this page
> and the spec disagree, the spec is right.

## Authentication

Authentication uses HttpOnly cookies. `POST /auth/login` and `POST /auth/register`
set `fairtix_token` (the access JWT, 15 minutes) and `fairtix_refresh` (7 days);
`POST /auth/refresh` rotates both. The browser sends them automatically; the
frontend reads the current user from `GET /auth/me` and never sees the token.

A state-changing request that carries an auth cookie must come from an allowed
origin (`Origin`, or else `Referer`), or it gets 403 `CSRF_ORIGIN_REJECTED`. The
backend also accepts `Authorization: Bearer <token>` as a fallback for non-browser
clients.

---

## Implemented Endpoints

### Auth

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/register` | Public | Register; sets the auth cookies |
| POST | `/auth/login` | Public | Log in; sets the auth cookies |
| GET | `/auth/me` | Cookie | The current user |
| POST | `/auth/refresh` | Refresh cookie | Rotate the auth cookies |
| POST | `/auth/logout` | Public | Revoke the refresh token and clear the cookies |
| GET | `/auth/verify` | Public | Verify an email address (`?token=`) |
| POST | `/auth/resend-verification` | Public | Resend the verification email |
| POST | `/auth/forgot-password` | Public | Request a password-reset email |
| POST | `/auth/reset-password` | Public | Set a new password with a reset token |
| POST | `/auth/step-up/verify` | Authenticated | Pass a step-up challenge (reCAPTCHA; 503 when reCAPTCHA is off) |

**Request body (register and login):**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Email at most 254 characters, password at most 72. Login also takes an optional
`recaptchaToken`, required after repeated failed attempts when reCAPTCHA is enabled.

**Response (register and login; no token in the body):**
```json
{
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "email": "user@example.com",
  "role": "USER",
  "emailVerified": false
}
```

### Events

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/events` | ADMIN | Create an event |
| GET | `/api/events` | Public | Search/list events (paginated) |
| GET | `/api/events/{id}` | Public | Get a single event |
| PUT | `/api/events/{id}` | ADMIN | Update an event |
| DELETE | `/api/events/{id}` | ADMIN | Delete an event |

**Create/Update request:**
```json
{
  "title": "Summer Music Festival",
  "startTime": "2026-07-15T19:00:00Z",
  "venueId": "f1e2d3c4-b5a6-7890-abcd-ef1234567890",
  "queueRequired": false,
  "queueCapacity": null,
  "maxTicketsPerUser": 6
}
```

`title`, `startTime` and `venueId` are required.

**Event response** (main fields; the spec has the rest): `id`, `title`, `startTime`,
`venue` (an object), `status` (`DRAFT`, `PUBLISHED`, `ACTIVE`, `COMPLETED`,
`CANCELLED`, `ARCHIVED`), `queueRequired`, `queueCapacity`, `maxTicketsPerUser`.
Cancelled, completed and archived events refuse purchases with 409
`EVENT_NOT_ON_SALE`.

**Search query parameters:** `venueName`, `performerName`, `title`, `upcoming` (boolean), `page` (default 0), `size` (default 20, max 100)

### Seats

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/events/{eventId}/seats` | ADMIN | Create a seat |
| GET | `/api/events/{eventId}/seats` | Public | List seats for an event |

**Create request:**
```json
{
  "section": "Floor",
  "rowLabel": "A",
  "seatNumber": "101",
  "price": 50.00
}
```

**Seat response** (also carries `price`):
```json
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "section": "Floor",
  "rowLabel": "A",
  "seatNumber": "101",
  "status": "AVAILABLE"
}
```

**List query parameters:** `availableOnly` (boolean, default false)

**Seat statuses:** `AVAILABLE`, `HELD` (an active hold), `BOOKED` (a confirmed hold),
`SOLD`

### Holds

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/events/{eventId}/holds` | Authenticated | Create a hold on 1-10 seats |
| GET | `/api/holds` | Authenticated | List the caller's holds (`?status=`, default `ACTIVE`) |
| GET | `/api/holds/{holdId}` | Authenticated | Get a single hold |
| POST | `/api/holds/{holdId}/release` | Authenticated | Release a hold (idempotent) |
| POST | `/api/holds/{holdId}/confirm` | Authenticated | Confirm a hold (idempotent) |

**Create hold request:**
```json
{
  "seatIds": ["b2c3d4e5-f6a7-8901-bcde-f12345678901"],
  "durationMinutes": 10
}
```

The owner is always the signed-in user; it is never taken from the request.
Returns 201.

**Hold response (array — one hold per seat):**
```json
[
  {
    "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "seatId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "ownerId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "expiresAt": "2026-07-15T19:10:00Z",
    "createdAt": "2026-07-15T19:00:00Z",
    "status": "ACTIVE"
  }
]
```

**Hold statuses:** `ACTIVE`, `CONFIRMED`, `EXPIRED`, `RELEASED`

**Constraints:**
- Max 10 seats per hold request (`holds.max-seats-per-hold`)
- Max 5 active holds per user (`holds.max-active-per-holder`)
- Duration: 1-60 minutes (default 10)
- On an event that requires the queue, the caller must have been admitted
- The event's per-user purchase cap counts held, bought and comped tickets
- ACTIVE holds expire after their duration; CONFIRMED holds are swept
  `holds.confirmed-grace-minutes` (15) after their expiry, which
  `POST /api/payments/intent` pushes out while the customer pays

### Analytics

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/analytics/dashboard` | ADMIN | Aggregate dashboard stats |

**Response:** see Swagger UI for the full `AnalyticsResponse` schema.

### Admin

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/admin/users` | ADMIN | List all users (paginated) |
| PATCH | `/api/admin/users/{id}/promote` | ADMIN | Promote a user to admin |

---

## Error Response Format

All errors follow a consistent shape:

```json
{
  "status": 409,
  "code": "HOLD_CONFLICT",
  "message": "Seat b2c3d4e5-... is not available",
  "path": "/api/holds/abc/confirm",
  "timestamp": "2026-07-15T19:00:00Z"
}
```

**Common error codes** (partial; the full set is in
[`GlobalExceptionHandler`](../backend/src/main/java/com/fairtix/config/GlobalExceptionHandler.java)):
`HOLD_NOT_FOUND`, `HOLD_CONFLICT`, `BAD_REQUEST`, `VALIDATION_ERROR`, `UNAUTHORIZED`,
`FORBIDDEN`, `RESOURCE_NOT_FOUND`, `PURCHASE_CAP_EXCEEDED`, `EVENT_NOT_ON_SALE`,
`PAYMENT_FAILED`, `CARD_DECLINED`, `PAYMENT_REFUNDED`, `PAYMENT_NOT_USABLE`,
`PAYMENT_NOT_BOUND`, `REFUND_NOT_ELIGIBLE`, `TRANSFER_VELOCITY_EXCEEDED`,
`STEP_UP_REQUIRED`.

Two 409 codes mean "retry": `REQUEST_IN_PROGRESS` (a lock wait timed out) and
`CONCURRENT_MODIFICATION` (another request changed the same rows first).

---

## Payments

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/payments/intent` | Authenticated | Create or reuse a Stripe PaymentIntent for the caller's CONFIRMED holds, and extend those holds while the customer pays. 501 when Stripe is off |
| POST | `/api/payments/checkout` | Authenticated | Create the order and tickets for the caller's holds. Returns 201 |

**Checkout request:**
```json
{
  "holdIds": ["c3d4e5f6-a7b8-9012-cdef-123456789012"],
  "paymentIntentId": "pi_...",
  "simulatedOutcome": "SUCCESS"
}
```

With Stripe on, `paymentIntentId` is required and must be the caller's intent for
exactly these holds; `simulatedOutcome` is ignored. With Stripe off (the local
default), payment is simulated. `simulatedOutcome` picks the result only where
`fairtix.payment.allow-simulated-outcome` is on (the `dev` profile); elsewhere
sending it is a 400, and the `prod` profile refuses simulated payment altogether.
A resubmit with the same intent returns the
existing order. Checkout and hold creation can answer 428 `STEP_UP_REQUIRED` for
an account the risk check flags; see `/auth/step-up/verify`.

## Orders

Endpoints for reading orders. An order is created only by
`POST /api/payments/checkout`; there is no unpaid order endpoint.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/orders` | Authenticated | List the current user's orders |
| GET | `/api/orders/{orderId}` | Authenticated | Get order details |

**Order response:**
```json
{
  "id": "d4e5f6a7-b8c9-0123-defa-234567890123",
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "holdIds": ["c3d4e5f6-a7b8-9012-cdef-123456789012"],
  "status": "COMPLETED",
  "totalAmount": 0.00,
  "currency": "USD",
  "createdAt": "2026-07-15T19:05:00Z",
  "eventTitle": "Summer Music Festival",
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "eventStartTime": "2026-07-15T19:00:00Z",
  "ticketCount": 1,
  "tickets": ["…one summary per ticket; see the spec"]
}
```

**Order statuses:** `PENDING`, `COMPLETED`, `CANCELLED`, `REFUNDED`

### Tickets

Endpoints for accessing purchased tickets.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/tickets` | Authenticated | List the current user's tickets |
| GET | `/api/tickets/{ticketId}` | Authenticated | Get ticket details |
| GET | `/api/tickets/{ticketId}/calendar.ics` | Authenticated | Calendar file for the ticket's event |

**Ticket response:**
```json
{
  "id": "e5f6a7b8-c9d0-1234-efab-345678901234",
  "orderId": "d4e5f6a7-b8c9-0123-defa-234567890123",
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "eventTitle": "Summer Music Festival",
  "eventVenue": "Madison Square Garden",
  "eventStartTime": "2026-07-15T19:00:00Z",
  "seatId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "seatSection": "Floor",
  "seatRow": "A",
  "seatNumber": "101",
  "price": 50.00,
  "holderEmail": "user@example.com",
  "status": "VALID",
  "issuedAt": "2026-07-15T19:05:00Z"
}
```

**Ticket statuses:** `VALID`, `USED`, `TRANSFERRED`, `CANCELLED`, `REFUNDED`

### Transfers

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/tickets/{ticketId}/transfer` | Authenticated | Offer a ticket to another registered user, by email |
| GET | `/api/transfers/incoming` | Authenticated | Transfers offered to the caller |
| GET | `/api/transfers/outgoing` | Authenticated | Transfers the caller has offered |
| POST | `/api/transfers/{requestId}/accept` | Authenticated | Accept (the recipient's purchase cap applies) |
| POST | `/api/transfers/{requestId}/reject` | Authenticated | Reject |
| POST | `/api/transfers/{requestId}/cancel` | Authenticated | Withdraw an offer |

The recipient must have an account ([RESALE_POLICY.md](RESALE_POLICY.md)). Too many
offers in a day answer 429 `TRANSFER_VELOCITY_EXCEEDED`.

### Refunds

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/orders/{orderId}/refunds` | Authenticated | Request a refund for one of the caller's orders |
| GET | `/api/refunds` | Authenticated | The caller's refund requests |
| GET | `/api/refunds/{refundId}` | Authenticated | One refund request |
| GET | `/api/admin/refunds` | ADMIN | All refund requests |
| POST | `/api/admin/refunds/{refundId}/review` | ADMIN | Approve or reject |
| POST | `/api/admin/refunds/{refundId}/retry` | ADMIN | Retry an approved refund whose Stripe call failed |

Small requests are approved automatically unless the account's risk tier is HIGH
or CRITICAL. An approved refund of a Stripe order is sent to Stripe; cash, comp and
simulated orders are refunded in FairTix only.

### Waiting room

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/events/{eventId}/queue/stream` | Authenticated | Server-Sent Events: position updates and admission |
| POST | `/api/events/{eventId}/queue/join` | Authenticated | Join the queue |
| GET | `/api/events/{eventId}/queue/status` | Authenticated | The caller's position and state |
| DELETE | `/api/events/{eventId}/queue/leave` | Authenticated | Leave the queue |
| GET | `/api/events/{eventId}/queue/admin` | ADMIN | Queue overview |
| POST | `/api/events/{eventId}/queue/admin/admit` | ADMIN | Admit the next batch now |

### Stripe webhooks

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/webhooks/stripe` | Stripe signature | `payment_intent.succeeded`, `payment_intent.payment_failed`, `charge.refunded`; each event id is processed once |
| POST | `/api/webhooks/stripe-connect` | Stripe signature | Connected-account events: account updates, payouts, `charge.dispute.created` |

A bad signature answers 400. With Stripe off, or no webhook secret set, both
endpoints answer 200 and process nothing.

---

## Not Implemented

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/admin/orders` | ADMIN | List all orders (admin) |
| POST | `/api/admin/tickets/{ticketId}/validate` | ADMIN | Validate a ticket at the door |

There is no order-cancel endpoint; a buyer asks for a refund instead (above).
