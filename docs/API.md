# API.md

This document records every REST API developed in BusLink. Update it whenever a new endpoint is implemented — no endpoint is considered "done" until it's documented here.

## Current Status

Sprint 2 delivered the first 5 endpoints: passenger register/login/refresh (`Auth`) and
profile/QR fetch (`User`). Sprint 3 added conductor auth, the full Route/Stop/Fare/Bus
domain, and role-based access (`ROLE_PASSENGER`/`ROLE_CONDUCTOR`/`ROLE_ADMIN`). All
follow the `ApiResponse<T>` envelope (`{success, message, data}`) on both success and
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

_No endpoints yet._

## Payments

_No endpoints yet._

## QR Validation

_No endpoints yet._

## Admin APIs

See **Routes** and **Buses** above — every `/admin/**` endpoint requires `ROLE_ADMIN`
and is documented under its own resource rather than duplicated in a separate bucket
here. See the note at the top of this document: none of them are actually reachable
yet, since no admin login path exists.
