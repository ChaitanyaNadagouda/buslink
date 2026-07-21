# API.md

This document records every REST API developed in BusLink. Update it whenever a new endpoint is implemented — no endpoint is considered "done" until it's documented here.

## Current Status

Sprint 2 delivered the first 5 endpoints: passenger register/login/refresh (`Auth`) and
profile/QR fetch (`User`). All follow the `ApiResponse<T>` envelope
(`{success, message, data}`) on both success and error paths. See
`postman/BusLink-API.postman_collection.json` for a runnable collection.

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

## Routes

_No endpoints yet._

## Stops

_No endpoints yet._

## Buses

_No endpoints yet._

## Tickets

_No endpoints yet._

## Payments

_No endpoints yet._

## QR Validation

_No endpoints yet._

## Admin APIs

_No endpoints yet._
