# API.md

This document records every REST API developed in BusLink. Update it whenever a new endpoint is implemented — no endpoint is considered "done" until it's documented here.

## Current Status

Sprint 2 delivered the first 5 endpoints: passenger register/login/refresh (`Auth`) and
profile/QR fetch (`User`). Sprint 3 added conductor auth, the full Route/Stop/Fare/Bus
domain, and role-based access (`ROLE_PASSENGER`/`ROLE_CONDUCTOR`/`ROLE_ADMIN`). Sprint 4
added the ticket issuance and wallet payment flow: conductor-side issuance (with
idempotency) and termination, passenger-side wallet payment (with overdraft support and
optimistic-lock-protected deduction), and passenger-facing ticket/wallet read endpoints.
Sprint 5 added real Razorpay (test mode) payment gateway integration: wallet recharge
and UPI ticket payment initiation endpoints, plus the public, signature-verified
`POST /webhooks/razorpay` that's the actual trust boundary confirming money movement —
see `ARCHITECTURE.md` for the `PaymentGatewayPort` abstraction behind it.
All follow the `ApiResponse<T>` envelope (`{success, message, data}`) on both success and
error paths. See `postman/BusLink-API.postman_collection.json` for a runnable collection.

**Note on `/admin/**` endpoints:** every `Routes`/`Buses` admin endpoint below is fully
implemented and correctly enforces `ROLE_ADMIN` — but as of Sprint 3, nothing in the
system can actually obtain a `ROLE_ADMIN` JWT (no `Admin` entity, principal, or login
endpoint exists yet). These endpoints are exercised only by direct testing (verifying
they reject unauthenticated/wrong-role requests); the seed data they'd normally create
(Route 500K, its stops, a test bus/conductor) is instead created by `DataSeeder`
bypassing the HTTP layer entirely. Real admin authentication is Sprint 4+ scope.

---

## Endpoint Documentation Template

Each endpoint below should follow this structure:

```
### <Method> <URL>

**Purpose**
What this endpoint does and why it exists.

**Authentication Requirement**
None / Bearer JWT / Role required (e.g., ADMIN).

**Request**
Path params, query params, and request body shape.

**Validation Rules**
Constraints enforced on the request (required fields, formats, ranges).

**Response**
Response body shape on success, including status code.

**Error Codes**
Status code -> condition (e.g., 404 -> route not found, 409 -> duplicate booking).

**Example Request**
\`\`\`
<method> <url>
<headers>
<body>
\`\`\`

**Example Response**
\`\`\`
<status>
<body>
\`\`\`
```

---

## Authentication

### POST /auth/register

**Purpose**
Register a new passenger. Creates the `User` (status `ACTIVE`), a linked `Wallet`
(balance 0, status `ACTIVE`), generates a QR token for boarding, and returns an
authenticated session — no separate login step required after registering.

**Authentication Requirement**
None (public).

**Request**
```json
{
  "name": "Test Rider",
  "email": "rider1@example.com",
  "mobileNo": "9876543210",
  "password": "password123"
}
```

**Validation Rules**
- `name` — required, non-blank
- `email` — required, must be a valid email address
- `mobileNo` — required, 10-digit Indian mobile number (`^[6-9]\d{9}$`), not E.164
- `password` — required, minimum 8 characters

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "accessToken": "<jwt>",
    "refreshToken": "<jwt>",
    "userId": "<uuid>",
    "email": "rider1@example.com",
    "name": "Test Rider"
  }
}
```
Deliberately `200`, not `201` — the body is an authenticated session (tokens), not a
representation of the created `User` resource, so the usual "201 for resource
creation" convention doesn't fit.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure on any field (field-level messages in `data`) |
| 409 | Email already registered |

---

### POST /auth/login

**Purpose**
Authenticate an existing passenger by email + password, returning fresh access and
refresh tokens.

**Authentication Requirement**
None (public).

**Request**
```json
{
  "email": "rider1@example.com",
  "password": "password123"
}
```

**Validation Rules**
- `email` — required, must be a valid email address
- `password` — required, non-blank

**Response** — `200 OK` — same `AuthResponseDTO` shape as `/auth/register`.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure, OR wrong email/password, OR account not `ACTIVE` |

**Note:** email-not-found and wrong-password both return the same generic `400`
message ("Invalid email or password") rather than distinguishable codes — prevents
an attacker from enumerating registered emails via this endpoint.

---

### POST /auth/refresh

**Purpose**
Exchange a valid refresh token for a new access token, without re-authenticating
with a password. The refresh token itself is returned unchanged (not rotated in
Sprint 2).

**Authentication Requirement**
None via header — the refresh token itself, sent in the body, is the credential.

**Request**
```json
{
  "refreshToken": "<jwt>"
}
```

**Validation Rules**
- `refreshToken` — required, non-blank

**Response** — `200 OK` — `AuthResponseDTO`, with a new `accessToken` and the same
`refreshToken` echoed back.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Token isn't a refresh token (e.g. an access token was sent instead), OR expired/tampered, OR user not found/not `ACTIVE` — all collapsed to one generic "Invalid or expired refresh token" message |

## User Management

### GET /user/profile

**Purpose**
Fetch the authenticated passenger's own profile.

**Authentication Requirement**
Bearer JWT (access token). No role check beyond authentication — every
authenticated user is `ROLE_PASSENGER` at this stage.

**Request**
No body. `Authorization: Bearer <accessToken>` header only. User identity comes
from `@AuthenticationPrincipal UserPrincipal`, not a path/query param — this
endpoint always means "me."

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": "<uuid>",
    "name": "Test Rider",
    "email": "rider1@example.com",
    "mobileNo": "9876543210",
    "status": "ACTIVE",
    "qrToken": "<32-char-hex>",
    "createdAt": "2026-07-20T10:15:30Z"
  }
}
```

**Error Codes**
| Status | Condition |
|---|---|
| 401 | Missing/invalid/expired Bearer token |

---

### GET /user/qr

**Purpose**
Fetch the authenticated passenger's QR token for boarding validation (conductor
scans this to identify the account, per the offline-first ticketing design).

**Authentication Requirement**
Bearer JWT (access token).

**Request**
No body. `Authorization: Bearer <accessToken>` header only.

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": "a1b2c3d4e5f6..."
}
```
Bare string, not an object — matches the plain-DTO-return precedent from
`/user/profile`; `ApiResponse` wrapping happens at the controller, not the service.

**Error Codes**
| Status | Condition |
|---|---|
| 401 | Missing/invalid/expired Bearer token |

## Conductor Auth

### POST /conductor/auth/login

**Purpose**
Authenticate a conductor by email + password, returning tokens plus the conductor's
assigned `busId` and derived `routeId` — the conductor app needs `routeId` immediately
to fetch and cache that route's full stop list (see `GET /routes/{routeId}/stops`).

**Authentication Requirement**
None (public).

**Request**
```json
{
  "email": "conductor@buslink.com",
  "password": "Test@1234"
}
```

**Validation Rules**
- `email` — required, must be a valid email address
- `password` — required, non-blank

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "accessToken": "<jwt, role=CONDUCTOR>",
    "refreshToken": "<jwt, role=CONDUCTOR>",
    "conductorId": "<uuid>",
    "name": "Test Conductor",
    "email": "conductor@buslink.com",
    "busId": "<uuid>",
    "routeId": "<uuid>"
  }
}
```

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Wrong email/password, account not `ACTIVE`, or conductor has no bus assigned (all collapsed to a generic message, same enumeration-prevention reasoning as passenger login) |

---

### GET /conductor/profile

**Purpose**
Fetch the authenticated conductor's own profile.

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`.

**Request**
No body. Identity comes from `@AuthenticationPrincipal ConductorPrincipal`.

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "conductorId": "<uuid>",
    "name": "Test Conductor",
    "email": "conductor@buslink.com",
    "busId": "<uuid>",
    "status": "ACTIVE"
  }
}
```

**Error Codes**
| Status | Condition |
|---|---|
| 401 | Missing/invalid/expired Bearer token |
| 403 | Valid token, but wrong role (e.g. a passenger token) |

## Routes

All endpoints in this section except the conductor-facing ones (marked below) require
`ROLE_ADMIN` — see the note at the top of this document on why they're currently
unreachable in practice.

### POST /admin/routes

**Purpose**
Create a route with its full stop list in one call — `Route` and every `RouteStop` are
saved transactionally (`@Transactional`; both succeed or fail together).

**Authentication Requirement**
Bearer JWT, `ROLE_ADMIN`.

**Request**
```json
{
  "routeNumber": "500K",
  "routeName": "Banashankari to Hebbal",
  "farePerStage": 6.00,
  "stops": [
    { "stopName": "Banashankari Bus Station", "stopSequence": 1, "stageNumber": 1 },
    { "stopName": "Sangam Circle", "stopSequence": 2, "stageNumber": 1 }
  ]
}
```

**Validation Rules**
- `routeNumber`, `routeName` — required, non-blank
- `farePerStage` — required, minimum `0.1`
- `stops` — required, non-empty, `@Valid` cascades into each element (`stopName`
  non-blank, `stopSequence`/`stageNumber` required and `>= 1`)

**Response** — `200 OK` — `RouteResponseDTO`: `routeId`, `routeNumber`, `routeName`,
`originStop`/`destinationStop` (derived from the first/last stop in the submitted
list), `totalStops`, `farePerStage`, `status` (`ACTIVE`).

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure, OR duplicate `stopSequence`/`stopName` within the request |
| 409 | `routeNumber` already exists |
| 401 / 403 | No token / wrong role |

---

### GET /admin/routes

**Purpose** List every route. **Response** — `200 OK`, array of `RouteResponseDTO`.

---

### GET /admin/routes/{routeId}

**Purpose** Fetch one route. **Response** — `200 OK`, `RouteResponseDTO`.
**Error Codes** `404` if `routeId` doesn't exist.

---

### PUT /admin/routes/{routeId}/status

**Purpose**
Partial update of a route's `farePerStage` and/or `status` — both fields optional
(only non-null fields are applied). **Deviation from the original plan:** named for
"activate/deactivate," but wired to accept fare too, since no separate fare-only
endpoint exists anywhere in the sprint's controller list — this avoided either
inventing an unplanned endpoint or leaving `RouteServiceImpl.updateRoute`'s fare-editing
half with no HTTP entry point at all.

**Request**
```json
{ "farePerStage": 8.00, "status": "INACTIVE" }
```
Either field, or both, may be omitted/null.

**Response** — `200 OK`, updated `RouteResponseDTO`. **Error Codes** `404` if
`routeId` doesn't exist.

---

### POST /admin/routes/{routeId}/stops

**Purpose**
Add one stop to an existing route. **Append-only**: `stopSequence` must equal the
route's current `totalStops + 1` — inserting into the middle of a route would require
renumbering every stop after it, out of scope for this endpoint. The new stop's
`stageNumber` must also be `>=` the current last stop's `stageNumber` (same
seeded 2-stops-per-stage pattern is valid; a *lower* stage means the stop actually
belongs in the middle, not the end, even if its `stopSequence` looks appended).
Updates the route's denormalized `totalStops`/`destinationStop` on success.

**Request**
```json
{ "stopName": "New Terminal", "stopSequence": 30, "stageNumber": 15 }
```

**Response** — `200 OK`, `RouteStopResponseDTO`.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure, wrong `stopSequence`, or `stageNumber` less than the current last stop's |
| 404 | `routeId` doesn't exist |
| 409 | A stop with that `stopName` already exists on the route |

---

### GET /admin/routes/{routeId}/stops

**Purpose** Admin view of every stop on a route, ordered by `stopSequence`.
**Response** — `200 OK`, array of `RouteStopResponseDTO`.

---

### GET /routes/{routeId}/stops

**Purpose**
Conductor-facing stop listing — one path, three behaviors depending on which optional
query params are present (see `ARCHITECTURE.md`/`INTERVIEW_PREP.md` for why: the
conductor app fetches the full list once at login and caches it locally for offline
use, per the project's offline-first design — this endpoint isn't meant to be called
per keystroke in a real client).

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`.

| Query params | Behavior |
|---|---|
| none | Full stop list, ordered by `stopSequence` (used once at login to build the offline cache) |
| `search=<prefix>` | Stops whose name starts with `<prefix>` (case-insensitive) — origin dropdown |
| `after=<stopName>&search=<prefix>` | Stops after `<stopName>`'s position matching `<prefix>` — destination dropdown; `search` defaults to empty (matches everything) if omitted |

**Response** — `200 OK`, array of `RouteStopResponseDTO`.

**Error Codes**
| Status | Condition |
|---|---|
| 401 / 403 | No token / wrong role (e.g. a passenger token) |
| 404 | (`after` variant only) the named origin stop doesn't exist on the route |

---

### GET /routes/{routeId}/fare

**Purpose**
Calculate the fare between two stops on a route for a given passenger mix — a preview
before ticket issuance (issuance itself is Sprint 4 scope).

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`.

**Request** — query params: `origin`, `destination` (stop names), `adults`,
`children`, `infants` (integer counts).

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "originStop": "HSR Layout",
    "destinationStop": "KR Puram Railway Station",
    "stagesCrossed": 6,
    "adultFare": 36.00,
    "childFare": 18.00,
    "infantFare": 0,
    "totalFare": 90.00
  }
}
```
`stagesCrossed = (destinationStage - originStage) + 1`; `adultFare = stagesCrossed ×
farePerStage`; `childFare = adultFare / 2` (ceiling-rounded); `infantFare` always `0`;
`totalFare = adults × adultFare + children × childFare`.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `destination` is not after `origin` on the route |
| 404 | `origin` or `destination` stop name doesn't exist on the route |
| 401 / 403 | No token / wrong role |

## Stops

See `GET /routes/{routeId}/stops` and `GET /admin/routes/{routeId}/stops` under
**Routes** above — stop listing is documented there since every stop endpoint is
scoped to a specific route, not a standalone resource.

## Buses

All endpoints require `ROLE_ADMIN` (see the note at the top of this document).

### POST /admin/buses

**Purpose** Register a bus against a route.

**Request**
```json
{ "busNumber": "KA-01-F-1234", "routeId": "<uuid>" }
```

**Response** — `200 OK`, `BusResponseDTO` (`busId`, `busNumber`, `routeId`,
`routeNumber` — joined in since `Bus` only stores a plain `routeId` UUID).

**Error Codes**
| Status | Condition |
|---|---|
| 409 | `busNumber` already exists |
| 404 | `routeId` doesn't exist |

---

### GET /admin/buses

**Purpose** List every bus. **Response** — `200 OK`, array of `BusResponseDTO`.
**Known trade-off:** one `Route` lookup per bus (N+1) to populate `routeNumber` —
accepted given the domain's real fleet size; see `ARCHITECTURE.md`/`Sprint-03.md`.

---

### GET /admin/buses/{busId}

**Purpose** Fetch one bus. **Response** — `200 OK`, `BusResponseDTO`.
**Error Codes** `404` if `busId` doesn't exist.

---

### PUT /admin/conductors/{conductorId}/assign-bus

**Purpose** Assign (or reassign) a bus to a conductor.

**Request**
```json
{ "busId": "<uuid>" }
```

**Response** — `200 OK`, `ConductorResponseDTO` (`conductorId`, `name`, `email`,
`busId`, `status`).

**Error Codes**
| Status | Condition |
|---|---|
| 404 | `conductorId` or `busId` doesn't exist |

## Tickets

### POST /tickets/issue

**Purpose**
Conductor issues a ticket for a scanned passenger. Validates the full chain — passenger
`ACTIVE`, the issuing conductor's assigned bus matches `busId`, that bus's route matches
`routeId`, both stops exist on the route, and destination is after origin — then
calculates the fare and creates the ticket in `ISSUED` status. Payment is a **separate,
later** step (`POST /payments/wallet`); this endpoint returns immediately without
waiting for it.

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`.

**Request**
Header: `X-Idempotency-Key: <client-generated UUID>` — **required**. A retried request
with the same key returns the original ticket instead of creating a duplicate (protects
against a conductor's device retrying after a network blip, a real risk for a
bus-mounted app).
```json
{
  "qrToken": "a1b2c3d4e5f6...",
  "busId": "<uuid>",
  "routeId": "<uuid>",
  "originStop": "HSR Layout",
  "destinationStop": "KR Puram Railway Station",
  "adults": 2,
  "children": 1,
  "infants": 1
}
```

**Validation Rules**
- `qrToken`, `busId`, `routeId`, `originStop`, `destinationStop` — required
- `adults` — required, minimum `1` (a ticket must have at least one paying passenger)
- `children`, `infants` — required, minimum `0`

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "ticketId": "<uuid>",
    "userId": "<uuid>",
    "originStop": "HSR Layout",
    "destinationStop": "KR Puram Railway Station",
    "stagesCrossed": 6,
    "adults": 2,
    "children": 1,
    "infants": 1,
    "adultFare": 36.00,
    "childFare": 18.00,
    "totalFare": 90.00,
    "status": "ISSUED",
    "issuedAt": "2026-07-29T13:07:38.632477Z"
  }
}
```
Duplicate request with the same `X-Idempotency-Key` (within its 24-hour TTL,
`ticket.idempotency.ttl-hours`) returns this exact same body, `ticketId` included,
without creating a second ticket. An expired key is treated as a brand-new request.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Missing `X-Idempotency-Key` header, OR `@Valid` failure, OR passenger not `ACTIVE`, OR conductor's bus doesn't match `busId`, OR bus's route doesn't match `routeId`, OR destination not after origin |
| 404 | `qrToken` doesn't match any passenger, OR origin/destination stop doesn't exist on the route |
| 401 / 403 | No token / wrong role |

---

### GET /conductor/tickets/pending

**Purpose**
The conductor's live "awaiting payment" queue — every `ISSUED` ticket issued by this
conductor, oldest first, so the app can show the current unpaid backlog.

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`.

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "ticketId": "<uuid>",
      "passengerName": "S4 Test Rider",
      "passengerQrToken": "a1b2c3d4e5f6...",
      "originStop": "HSR Layout",
      "destinationStop": "KR Puram Railway Station",
      "totalFare": 90.00,
      "status": "ISSUED",
      "issuedAt": "2026-07-29T13:07:38.632477Z",
      "minutesSinceIssue": 2
    }
  ]
}
```
`minutesSinceIssue` is computed fresh on every call (`ChronoUnit.MINUTES.between(issuedAt,
now)`), not stored — always reflects "right now," not the time of ticket creation.

**Error Codes**
| Status | Condition |
|---|---|
| 401 / 403 | No token / wrong role |

---

### PUT /tickets/{ticketId}/terminate

**Purpose**
Lets a conductor void a ticket they issued that never gets paid (e.g. the passenger
gets off before paying) — removes it from the pending queue immediately, without
waiting on the ticket-expiry scheduler (Sprint 7 scope, not built yet).

**Authentication Requirement**
Bearer JWT, `ROLE_CONDUCTOR`. Ownership is enforced implicitly by the lookup itself —
scoped to `(ticketId, conductorId)` together, so a conductor can't terminate another
conductor's ticket even with a guessed `ticketId`.

**Request**
No body. `ticketId` in the path.

**Response** — `200 OK` — same shape as `POST /tickets/issue`'s response, with
`status: "TERMINATED"`.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Ticket isn't currently `ISSUED` (already `PAID`/`TERMINATED`/`EXPIRED`) |
| 404 | `ticketId` doesn't exist, or doesn't belong to this conductor |
| 401 / 403 | No token / wrong role |

## Payments

### POST /payments/wallet

**Purpose**
Passenger pays for an `ISSUED` ticket out of their wallet balance, with overdraft
support up to a configurable limit (`wallet.overdraft-limit`, default ₹100) — moves the
ticket to `PAID` and records a `DEBIT` `Transaction`. Optimistic locking (`Wallet.
version`) protects against a concurrent double-payment attempt on the same wallet.

**Authentication Requirement**
Bearer JWT, `ROLE_PASSENGER`.

**Request**
```json
{ "ticketId": "<uuid>" }
```
Deliberately just `ticketId` — `userId` always comes from the authenticated principal
(`@AuthenticationPrincipal`), never trusted from the request body, and `amount` is never
client-supplied either; the service computes it from `ticket.totalFare`.

**Validation Rules**
- `ticketId` — required

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "ticketId": "<uuid>",
    "amountDeducted": 90.00,
    "walletBalanceAfter": 60.00,
    "ticketStatus": "PAID",
    "paidAt": "2026-07-30T09:12:04.511Z"
  }
}
```

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure, OR ticket isn't `ISSUED` ("Ticket is not awaiting payment" — covers already-`PAID`/`TERMINATED`/`EXPIRED`), OR wallet isn't `ACTIVE`, OR `balance + overdraftLimit < totalFare` ("Insufficient balance. Available: ₹X, Required: ₹Y") |
| 404 | `ticketId` doesn't exist, or doesn't belong to this passenger |
| 409 | Concurrent update to the same wallet detected (optimistic lock failure) — generic "This record was updated by another request. Please retry." |
| 401 / 403 | No token / wrong role |

---

### POST /payments/recharge/initiate

**Purpose**
Starts a wallet top-up via Razorpay. Creates a Razorpay order (through
`PaymentGatewayPort`, never the Razorpay SDK directly — see
`ARCHITECTURE.md`) and a `PENDING` `Payment` row; the wallet is **not**
credited yet — that only happens once the signed webhook confirms success
(see `POST /webhooks/razorpay` below).

**Authentication Requirement**
Bearer JWT, `ROLE_PASSENGER`.

**Request**
```json
{ "amount": 200.00 }
```

**Validation Rules**
- `amount` — required, minimum `1.00`

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "paymentId": "<uuid>",
    "razorpayOrderId": "order_TTFKZlrngVi0oz",
    "amount": 200.00,
    "currency": "INR",
    "razorpayKeyId": "rzp_test_..."
  }
}
```
`razorpayKeyId` is handed to the frontend to open Razorpay's checkout
widget — deliberately Razorpay-named in the response, since the checkout
integration itself is inherently tied to whichever gateway's widget is in
use (see `Sprint-05.md`'s "Mid-sprint design decision" for the scope line
between backend and frontend abstraction).

**Error Codes**
| Status | Condition |
|---|---|
| 400 | `@Valid` failure, OR wallet isn't `ACTIVE` |
| 404 | Wallet doesn't exist for this passenger |
| 401 / 403 | No token / wrong role |
| 502 | Razorpay order creation failed (`PaymentGatewayException`) |

---

### POST /payments/ticket/upi/initiate

**Purpose**
Starts a direct UPI/card payment for an `ISSUED` ticket via Razorpay — an
alternative to `POST /payments/wallet` that never touches the wallet.
Idempotent: a second call for the same ticket while a payment is still
`PENDING` returns the existing order instead of creating a duplicate.

**Authentication Requirement**
Bearer JWT, `ROLE_PASSENGER`.

**Request**
```json
{ "ticketId": "<uuid>" }
```

**Validation Rules**
- `ticketId` — required

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "paymentId": "<uuid>",
    "razorpayOrderId": "order_TTIDlM4IE2ameS",
    "amount": 90.00,
    "currency": "INR",
    "razorpayKeyId": "rzp_test_...",
    "ticketId": "<uuid>"
  }
}
```
`amount` is always `ticket.totalFare` — never client-supplied.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Ticket isn't `ISSUED` ("Ticket is not awaiting payment") |
| 404 | `ticketId` doesn't exist, or doesn't belong to this passenger |
| 401 / 403 | No token / wrong role |
| 502 | Razorpay order creation failed (`PaymentGatewayException`) |

## Webhooks

### POST /webhooks/razorpay

**Purpose**
The actual trust boundary for every payment in this system — Razorpay
calls this once a checkout attempt succeeds or fails. Verifies the
request's HMAC signature, then credits the wallet (with overdraft
recovery) or marks the ticket `PAID`, depending on `Payment.purpose`.
Idempotent: a `Payment` already `SUCCESS` is never reprocessed, so
Razorpay's own webhook retries are safe.

**Authentication Requirement**
None — `permitAll()` in `SecurityConfig`, since Razorpay itself carries no
JWT. Security is enforced entirely by the signature check inside the
handler (`PaymentGatewayPort.verifyWebhookSignature`), not by Spring
Security.

**Request**
Header: `X-Razorpay-Signature` — **required**, the HMAC signature Razorpay
computes over the raw request body.
Body: Razorpay's raw webhook JSON, read as a plain `String` (not a typed
DTO) — the raw bytes are needed for signature verification before any
parsing happens.

**Sample raw webhook payload — `payment.captured`** (success; only the
fields `RazorpayGatewayAdapter.parseWebhookEvent` actually reads are
`event` and `payload.payment.entity.order_id` — everything else is real
Razorpay shape, shown for reference since capturing the full body is what
you'd actually see live in ngrok's inspector):
```json
{
  "entity": "event",
  "account_id": "acc_TL8csexample",
  "event": "payment.captured",
  "contains": ["payment"],
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_TTFxamp1e0001",
        "entity": "payment",
        "amount": 20000,
        "currency": "INR",
        "status": "captured",
        "order_id": "order_TTFKZlrngVi0oz",
        "invoice_id": null,
        "international": false,
        "method": "card",
        "amount_refunded": 0,
        "refund_status": null,
        "captured": true,
        "description": null,
        "card_id": "card_TTFxamp1eCard",
        "bank": null,
        "wallet": null,
        "vpa": null,
        "email": "rider1@example.com",
        "contact": "+919999999999",
        "notes": [],
        "fee": 472,
        "tax": 72,
        "error_code": null,
        "error_description": null,
        "created_at": 1755962764
      }
    }
  },
  "created_at": 1755962764
}
```

**Sample raw webhook payload — `payment.failed`** (declined attempt —
note `error_code`/`error_description` populated, `order_id` unchanged
since it's still the same order):
```json
{
  "entity": "event",
  "account_id": "acc_TL8csexample",
  "event": "payment.failed",
  "contains": ["payment"],
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_TTFxamp1e0000",
        "entity": "payment",
        "amount": 20000,
        "currency": "INR",
        "status": "failed",
        "order_id": "order_TTFxkjYGGcyqgA",
        "international": true,
        "method": "card",
        "amount_refunded": 0,
        "refund_status": null,
        "captured": false,
        "card_id": "card_TTFxamp1eCard2",
        "bank": null,
        "wallet": null,
        "vpa": null,
        "email": "rider1@example.com",
        "contact": "+919999999999",
        "notes": [],
        "error_code": "BAD_REQUEST_ERROR",
        "error_description": "International cards are not supported",
        "error_source": "customer",
        "error_step": "payment_authentication",
        "error_reason": "international_transaction_not_allowed",
        "created_at": 1755964391
      }
    }
  },
  "created_at": 1755964391
}
```

**Response** — `200 OK`, body `"OK"` (plain string, not `ApiResponse`) —
Razorpay expects any `200` response to stop retrying; a non-200 triggers
more retries.

**Error Codes**
| Status | Condition |
|---|---|
| 400 | Missing `X-Razorpay-Signature` header, OR signature verification fails ("Invalid webhook signature") |
| 404 | No `Payment` found matching the webhook's order ID |

**Important behavioral note:** Razorpay sends one webhook **per payment
attempt**, not one per order — a declined attempt followed by a retry on
the same order produces 2 webhooks. Only `PaymentStatus.SUCCESS` is
treated as terminal; a `FAILED` attempt does not block a later `SUCCESS`
webhook for the same order from being processed. See `Sprint-05.md`/
`DEVELOPMENT_LOG.md` for the live-verification bug this fixed.

## Passengers

Read-only endpoints for a passenger's own ticket history and wallet — every lookup is
scoped to the authenticated principal's `userId`, never a path/query param, so one
passenger can never read another's data by guessing an ID.

### GET /passenger/tickets

**Purpose**
Full ticket history, newest first, enriched with conductor/bus/route names for display.

**Authentication Requirement**
Bearer JWT, `ROLE_PASSENGER`.

**Response** — `200 OK`, array of:
```json
{
  "ticketId": "<uuid>",
  "conductorName": "Test Conductor",
  "busNumber": "KA-01-F-1234",
  "routeNumber": "500K",
  "originStop": "HSR Layout",
  "destinationStop": "KR Puram Railway Station",
  "stagesCrossed": 6,
  "adults": 2,
  "children": 1,
  "infants": 1,
  "adultFare": 36.00,
  "childFare": 18.00,
  "totalFare": 90.00,
  "status": "PAID",
  "issuedAt": "2026-07-29T13:07:38.632477Z",
  "paidAt": "2026-07-30T09:12:04.511Z"
}
```
`paidAt` is `null` for a still-`ISSUED` ticket. `conductorName`/`busNumber`/
`routeNumber` are 3 extra lookups per ticket (N+1) — accepted for now, same reasoning
as `GET /admin/buses`, given the domain's real fleet/ridership scale.

**Error Codes**
| Status | Condition |
|---|---|
| 401 / 403 | No token / wrong role |

---

### GET /passenger/tickets/{ticketId}

**Purpose** Single-ticket detail view — same shape as above.

**Response** — `200 OK`, same object shape as `GET /passenger/tickets`'s array items.

**Error Codes**
| Status | Condition |
|---|---|
| 404 | `ticketId` doesn't exist, or doesn't belong to this passenger |
| 401 / 403 | No token / wrong role |

---

### GET /passenger/wallet/balance

**Purpose** Current wallet balance and status.

**Response** — `200 OK`
```json
{
  "success": true,
  "message": "Success",
  "data": { "balance": 60.00, "status": "ACTIVE", "lastUpdated": "2026-07-30T09:12:04.511Z" }
}
```

**Error Codes**
| Status | Condition |
|---|---|
| 401 / 403 | No token / wrong role |

---

### GET /passenger/wallet/transactions

**Purpose** Wallet transaction ledger, newest first.

**Response** — `200 OK`, array of:
```json
{
  "transactionId": "<uuid>",
  "amount": 90.00,
  "type": "DEBIT",
  "status": "SUCCESS",
  "referenceId": "<ticketId>",
  "createdAt": "2026-07-30T09:12:04.511Z"
}
```
`referenceId` points to whatever caused the transaction — a `ticketId` for every
`DEBIT` this sprint produces; a future wallet-recharge `CREDIT` (Sprint 5) would
reference something else (e.g. a payment gateway transaction), which is why the field
stays a generic UUID rather than being named `ticketId`.

**Error Codes**
| Status | Condition |
|---|---|
| 401 / 403 | No token / wrong role |

## QR Validation

_No endpoints yet._

## Admin APIs

See **Routes** and **Buses** above — every `/admin/**` endpoint requires `ROLE_ADMIN`
and is documented under its own resource rather than duplicated in a separate bucket
here. See the note at the top of this document: none of them are actually reachable
yet, since no admin login path exists.
