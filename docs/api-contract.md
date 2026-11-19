# FairTix API Contract

> **Version:** 1.0.0
> **Base URL:** `http://localhost:8080`
> **Interactive docs:** `http://localhost:8080/swagger-ui.html`
> **OpenAPI spec:** `http://localhost:8080/v3/api-docs`

## Authentication

All protected endpoints require a JWT bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are obtained from the Auth endpoints and expire after 15 minutes.

---

## Implemented Endpoints

### Auth

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/register` | Public | Register a new user |
| POST | `/auth/login` | Public | Log in and receive a JWT |

**Request body (both):**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
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
  "venue": "Madison Square Garden"
}
```

**Event response:**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "title": "Summer Music Festival",
  "startTime": "2026-07-15T19:00:00Z",
  "venue": "Madison Square Garden"
}
```

**Search query parameters:** `venueName`, `title`, `upcoming` (boolean), `page` (default 0), `size` (default 20, max 100)

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
  "seatNumber": "101"
}
```

**Seat response:**
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

### Holds

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/events/{eventId}/holds` | Authenticated | Create a hold on 1-10 seats |
| GET | `/api/holds` | Authenticated | List holds for a holder |
| GET | `/api/holds/{holdId}` | Authenticated | Get a single hold |
| POST | `/api/holds/{holdId}/release` | Authenticated | Release a hold (idempotent) |
| POST | `/api/holds/{holdId}/confirm` | Authenticated | Confirm a hold (idempotent) |

**Create hold request:**
```json
{
  "seatIds": ["b2c3d4e5-f6a7-8901-bcde-f12345678901"],
  "holderId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "durationMinutes": 10
}
```

**Hold response (array — one hold per seat):**
```json
[
  {
    "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "seatId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "holderId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "expiresAt": "2026-07-15T19:10:00Z",
    "createdAt": "2026-07-15T19:00:00Z",
    "status": "ACTIVE"
  }
]
```

**Hold statuses:** `ACTIVE`, `CONFIRMED`, `EXPIRED`, `RELEASED`

**Constraints:**
- Max 10 seats per hold request
- Max 5 active holds per holder
- Duration: 1-60 minutes (default 10)
- Holds expire automatically after the specified duration

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

**Error codes:** `HOLD_NOT_FOUND`, `HOLD_CONFLICT`, `BAD_REQUEST`, `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `RESOURCE_NOT_FOUND`

---

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
  "createdAt": "2026-07-15T19:05:00Z"
}
```

**Order statuses:** `PENDING`, `COMPLETED`, `CANCELLED`, `REFUNDED`

### Tickets

Endpoints for accessing purchased tickets.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/tickets` | Authenticated | List the current user's tickets |
| GET | `/api/tickets/{ticketId}` | Authenticated | Get ticket details |

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
  "holderEmail": "user@example.com",
  "status": "VALID",
  "issuedAt": "2026-07-15T19:05:00Z"
}
```

**Ticket statuses:** `VALID`, `USED`, `TRANSFERRED`, `CANCELLED`

---

## Planned Endpoints (Not Yet Implemented)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/orders/{orderId}/cancel` | Authenticated | Cancel an order (if refundable) |
| GET | `/api/admin/orders` | ADMIN | List all orders (admin) |
| POST | `/api/tickets/{ticketId}/transfer` | Authenticated | Transfer a ticket to another user |
| POST | `/api/admin/tickets/{ticketId}/validate` | ADMIN | Validate a ticket at the door |

---

## Frontend Alignment Notes

The frontend currently integrates with:
- Auth endpoints (login/register)
- Events listing (`GET /api/events`)
- Analytics dashboard (`GET /api/analytics/dashboard`)
- Admin user management (`GET /api/admin/users`, `PATCH .../promote`)
- Tickets listing (`GET /api/tickets`) — My Tickets page

**Not yet wired in the frontend:** Seats, holds, release, confirm, order creation. These are backend-ready and awaiting the seat selection / checkout UI.
