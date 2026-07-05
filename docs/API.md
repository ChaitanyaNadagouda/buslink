# API.md

This document records every REST API developed in BusLink. Update it whenever a new endpoint is implemented — no endpoint is considered "done" until it's documented here.

## Current Status

No APIs have been implemented yet.

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

_No endpoints yet._

## User Management

_No endpoints yet._

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
