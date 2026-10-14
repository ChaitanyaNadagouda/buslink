# Sprint 7

## Goal

Implement BusLink's core differentiator — the offline sync engine.
Enhance passenger QR to a signed JWT for offline identity verification.
Replace push notifications with a QR scan model (conductor shows ticket QR,
passenger scans to confirm and pay). Process offline ticket batches idempotently
with three-outcome conflict resolution (SYNCED / DISPUTED / FAILED) and background
schedulers for retry, expiry, and cleanup.

---

## Scope

### QR Model Changes
- Passenger QR → signed JWT (userId, userStatus, deviceId, exp=24hr)
- `POST /user/qr/refresh` — daily QR renewal endpoint
- Ticket QR → plain JSON payload (ticketId, originStop, destinationStop, totalFare,
  conductorId, issuedAt, exp=issuedAt+1hr) — no signing needed
- `POST /tickets/scan` — passenger scans conductor's ticket QR to get confirm screen
- Push notifications REMOVED entirely — QR scan model replaces it always

### Offline Sync Engine
- `SyncStatus` enum — add PROCESSING
- `TicketStatus` enum — add DISPUTED
- `SyncEventRepository` — status-based queries + GROUP BY counts
- `SyncProperties` + `TicketExpiryProperties` — `@ConfigurationProperties`
- All Sync DTOs — request + response
- `SyncEventProcessor` — separate `@Service` for per-event `@Transactional`
- `SyncServiceImpl` — non-transactional batch loop
- `POST /sync/upload` (ROLE_CONDUCTOR) — batch offline ticket reconciliation
- `GET /sync/status` (ROLE_CONDUCTOR) — per-status counts
- 3 background schedulers — retry FAILED (5min), ticket expiry (24hr), idempotency cleanup
- `GET /admin/analytics/disputed-tickets` — admin visibility of fare evasion flags

### Passenger Confirm Flow (Online + Offline Unified)
- **Online**: conductor issues ticket → conductor shows ticket QR → passenger
  scans → `POST /tickets/scan` → server returns ticket details → passenger
  sees [Confirm & Pay] → wallet deducted immediately
- **Offline**: conductor issues ticket locally → conductor shows ticket QR →
  passenger scans → app reads QR payload locally (no server call) → passenger
  sees [Confirm & Pay Later] or [Cancel] → if confirmed, SyncEvent marked
  CONFIRMED on device → uploaded at sync time → backend auto-deducts wallet
- **UX is identical in both modes** — passenger always actively confirms before
  payment is committed. Cancellation before confirm = no ticket, no deduction.
- DISPUTED: passenger sees status in ticket history → [Pay Now] via existing
  `POST /payments/wallet` — no admin needed, no new endpoints
- No new payment endpoints needed — design clarification only

### Ticket QR Payload Design
```json
{
  "ticketId": "uuid",
  "originStop": "Majestic",
  "destinationStop": "Hebbal",
  "totalFare": 25.00,
  "conductorId": "uuid",
  "issuedAt": "2026-10-01T15:32:00Z",
  "exp": "2026-10-01T16:32:00Z"
}
```
- Plain JSON — no JWT, no signing needed (see Pre-Sprint Notes)
- `exp` = `issuedAt + 1hr` — client-side gate only, never checked at sync time
- Passenger app logic on scan:
  - `now > exp` → "QR expired, ask conductor" → stop
  - else → show confirm screen → [Confirm & Pay] / [Cancel]

**Deferred to Sprint 8:** OTP verification, deviceId registration check,
outstanding dues block, DISPUTED blacklist endpoint, README + architecture diagrams.

**Out of scope:** Docker (Sprint 9), CI/CD (Sprint 9), AWS (Sprint 9), Flyway (deferred).

---

## Pre-Sprint Notes

**Why QR scan replaces push notifications:**
One consistent flow (online + offline) beats two different flows that double
conductor cognitive load on a crowded moving bus. QR is synchronous, reliable,
works without FCM, needs no notification permissions, and is familiar to every
Indian user (PhonePe/GPay pattern). Removed entirely — not a fallback.

**Why ticket QR needs no signing:**
Online → backend verifies via `POST /tickets/scan` (DB is source of truth).
Offline → backend recalculates fare at sync time regardless of QR content.
The QR is a data pipe — camera replaces keyboard. No identity claim is being
made that needs cryptographic proof. Forging the QR payload gains nothing:
fare is always recalculated server-side, and `POST /tickets/scan` verifies
userId ownership server-side. `exp` is a client-side convenience check only.

**Why passenger QR needs signing:**
Conductor must verify passenger identity OFFLINE with no backend fallback.
Without signing, any fake QR with a valid userId bypasses identity verification.
Server signs with private key → conductor verifies with cached public key →
cryptographic proof of authenticity without network.

**Ticket QR exp is a client-side gate, not a sync-time gate:**
`exp` = `issuedAt + 1hr` prevents passenger from scanning a stale QR on their
screen. It is checked by the passenger app only. SyncEventProcessor never checks
`exp` — it checks `issuedAt` against `maxSyncAgeDays` (default 7 days) to
detect replay attacks and device clock manipulation.

**Passenger confirm flow — why offline needs same UX as online:**
In online mode the passenger actively taps [Confirm & Pay]. Offline mode must
give the same agency — passenger should be able to cancel just before commit.
Since `POST /tickets/scan` cannot reach the server offline, the passenger app
renders the confirm screen locally from the ticket QR payload. If they cancel,
the SyncEvent is never uploaded — no ticket created, no wallet touched.

**SyncEventProcessor must be a separate @Service:**
`@Transactional` per-event requires its own Spring proxy.
Self-invocation bypasses the proxy — same lesson as Sprint 6 `@Cacheable`.
One event failure must never roll back already-committed events.

**DISPUTED vs FAILED:**
FAILED = technical failure → retry scheduler makes sense.
DISPUTED = wallet insufficient even with overdraft → wallet won't fill itself
→ retry pointless → passenger self-pays via [Pay Now] in ticket history.
Scheduler ONLY retries FAILED, never DISPUTED.

---

## Tasks

### QR Model — Passenger QR Enhancement

- [x] S7-01 — Update `JwtUtil.java` in `security/`:
  - Add `generatePassengerQrToken(User user)`:
    ```java
    return Jwts.builder()
        .claim("userId", user.getUserId())
        .claim("userStatus", user.getStatus().name())
        .claim("deviceId", user.getDeviceId())
        .claim("type", "PASSENGER_QR")
        .issuedAt(new Date())
        .expiration(Date.from(Instant.now().plus(24, ChronoUnit.HOURS)))
        .signWith(getSigningKey())
        .compact();
    ```
  - Add `validatePassengerQrToken(String token)`:
    → Verify signature using existing signing key
    → Check expiry
    → Check type claim = "PASSENGER_QR"
    → Return extracted claims or throw ValidationException
  - Add `extractUserIdFromPassengerQr(String token)` → UUID
  - Add `extractDeviceIdFromPassengerQr(String token)` → String
  - Update `JwtUtilTest` — add passenger QR round-trip tests
  - Verify: `./mvnw test -Dtest=JwtUtilTest` — all pass

- [x] S7-02 — Update `AuthServiceImpl.register()`:
  - Replace `QrTokenUtil.generateQrToken()` (plain UUID) with
    `jwtUtil.generatePassengerQrToken(savedUser)`
  - Store signed JWT in `User.qrToken`
  - Check `qrToken` column size in DB:
    JWT is ~200-300 chars vs old UUID hex (32 chars)
    → `ALTER TABLE users ALTER COLUMN qr_token TYPE TEXT;`
    via psql if needed (ddl-auto=update won't resize automatically)
  - Verify: `POST /auth/register` → `GET /user/qr` returns JWT string

- [x] S7-03 — Create `POST /user/qr/refresh` endpoint (ROLE_PASSENGER):
  - In `UserServiceImpl`:
    ```java
    public String refreshQrToken(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(...));
        String newQrToken = jwtUtil.generatePassengerQrToken(user);
        user.setQrToken(newQrToken);
        userRepository.save(user);
        return newQrToken;
    }
    ```
  - In `UserController`:
    `POST /user/qr/refresh` → `ApiResponse<String>`
  - In `SecurityConfig`: add `POST /user/qr/refresh` → ROLE_PASSENGER
  - Verify: refresh generates new JWT with fresh 24hr expiry

  **Deviation (discussed, approved):** a manual refresh button alone doesn't
  match the real usage pattern — a passenger only opens the app at boarding
  time, not daily, so nothing would ever call this endpoint before the QR
  expired. Made `GET /user/qr` (`UserServiceImpl.getQrToken`) self-healing
  instead: it validates the stored token via `jwtUtil.validatePassengerQrToken`
  and transparently regenerates + persists a fresh one on any failure
  (expired, tampered, or missing), sharing the same `regenerateQrToken`
  helper this endpoint uses. `POST /user/qr/refresh` is kept as an explicit
  force-refresh path (e.g. future lost-device scenarios), but freshness on
  the passenger's actual read path no longer depends on it being called.
  No expiry buffer added — 24hr window vs. a boarding-time read-then-scan is
  wide enough that regenerating only on/after actual expiry is sufficient.
  Trade-off accepted: `GET /user/qr` is no longer a side-effect-free read.
  Covered by 3 new `UserServiceImplTest` tests. Full suite: 77/77 pass.

### QR Model — Ticket QR + Scan Endpoint

- [x] S7-04 — Update `IssueTicketResponseDTO` + ticket QR payload design:
  - Confirm `IssueTicketResponseDTO` already contains `ticketId` ✅
  - **Design update (post-brainstorm):** ticket QR is NOT plain ticketId only.
    Frontend encodes the following JSON as QR — backend returns all fields
    needed in `IssueTicketResponseDTO`:
    ```json
    {
      "ticketId": "uuid",
      "originStop": "Majestic",
      "destinationStop": "Hebbal",
      "totalFare": 25.00,
      "conductorId": "uuid",
      "issuedAt": "2026-10-01T15:32:00Z",
      "exp": "2026-10-01T16:32:00Z"
    }
    ```
  - Verify `IssueTicketResponseDTO` exposes: ticketId, originStop,
    destinationStop, totalFare, conductorId, issuedAt
  - Frontend sets `exp = issuedAt + 1hr` before encoding QR
  - No signing — plain JSON. Backend never reads `exp` at sync time.
  - Offline passenger app reads QR locally → renders confirm screen →
    `now > exp` → reject; else → show [Confirm & Pay Later] / [Cancel]

- [x] S7-05 — Create `POST /tickets/scan` endpoint (ROLE_PASSENGER):
  - New request DTO: `ScanTicketRequestDTO` (Java record):
    `ticketId` (@NotNull, UUID)
  - In `TicketServiceImpl`:
    ```java
    public TicketDetailResponseDTO scanTicket(UUID ticketId, UUID userId) {
        Ticket ticket = ticketRepository
            .findByTicketIdAndUserId(ticketId, userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Ticket not found or not issued for your account"));
        if (ticket.getStatus() != TicketStatus.ISSUED
            && ticket.getStatus() != TicketStatus.DISPUTED) {
            throw new ValidationException(
                "Ticket is not awaiting payment");
        }
        return mapToTicketDetailResponseDTO(ticket);
    }
    ```
    Note: `findByTicketIdAndUserId` already exists from Sprint 4 (S4-02)
  - Online flow: passenger scans ticket QR → calls this endpoint → server
    returns ticket details → passenger sees [Confirm & Pay] → payment processed
  - Offline flow: passenger scans ticket QR → reads payload locally → no
    server call → passenger sees [Confirm & Pay Later] / [Cancel]
    → this endpoint NOT called offline
  - In `SecurityConfig`: `POST /tickets/scan` → ROLE_PASSENGER
  - Verify: compile clean, app boots

  **Deviation (discussed, approved):** built in `TicketController`, not
  `PassengerController` as originally planned. `PassengerController` has a
  class-level `@RequestMapping("/passenger")`; Spring concatenates
  class-level and method-level mappings with no way to escape the prefix
  from a method annotation, so a handler there would actually route as
  `POST /passenger/tickets/scan`, not the `POST /tickets/scan` the DoD and
  Postman steps require. `TicketController` has no class-level prefix and
  already hosts the sibling `/tickets/issue` and `/tickets/{ticketId}/
  terminate` action endpoints, so `/tickets/scan` was added there instead —
  same resource family, same bare-path pattern, correct URL for free.

- [x] S7-06 — Update `SecurityConfig.java`:
  - [x] `POST /user/qr/refresh` → ROLE_PASSENGER (done with S7-03)
  - [x] `POST /tickets/scan` → ROLE_PASSENGER (done with S7-05)
  - [x] `POST /sync/upload` → ROLE_CONDUCTOR (done with S7-19, via a blanket
        `/sync/**` rule rather than two separate method-scoped matchers)
  - [x] `GET /sync/status` → ROLE_CONDUCTOR (done with S7-19)
  - Verify: existing auth flows unaffected

### Enum + DB Constraint Updates

- [x] S7-07 — Add `PROCESSING` to `SyncStatus.java` in `enums/`:
  - State machine: PENDING → PROCESSING → SYNCED / FAILED / DISPUTED
  - Check `sync_event` table for Hibernate check constraint:
    `psql: \d sync_event` → inspect constraint
  - If exists: DROP and recreate with PROCESSING included
  - Verify: `./mvnw compile clean`, app boots clean

  **Deviation (discussed, approved):** the task text only named `PROCESSING`,
  but the state-machine line right above it, `SyncEventProcessor`'s own
  pseudocode (S7-14, `syncEvent → DISPUTED`), and `SyncStatusResponseDTO`'s
  `disputed` count field (S7-12) all require `SyncEvent` to reach a
  `DISPUTED` status too — building only `PROCESSING` now would have made
  S7-14 fail to compile later. Added both `PROCESSING` and `DISPUTED` to
  `SyncStatus` in this task instead of splitting `DISPUTED` off to a future
  one. `sync_event_status_check` dropped and recreated as
  `CHECK (status IN ('PENDING','PROCESSING','SYNCED','FAILED','DISPUTED'))`,
  confirmed via `\d sync_event` and a rollback-wrapped `INSERT` of both new
  values (same proof style as Sprint 4's `TERMINATED` fix).

- [x] S7-08 — Add `DISPUTED` to `TicketStatus.java` in `enums/`:
  - Check `ticket` table check constraint (same as Sprint 4 TERMINATED fix)
  - `psql: \d ticket` → drop + recreate with DISPUTED
  - Verify: `./mvnw compile clean`, constraint updated confirmed via psql

  `ticket_status_check` dropped and recreated as
  `CHECK (status IN ('ISSUED','PAID','EXPIRED','CANCELLED','TERMINATED','DISPUTED'))`,
  confirmed via `\d ticket` and a rollback-wrapped `INSERT` of a `DISPUTED`
  row. Full suite: 77/77 pass, `BusLinkApplicationTests.contextLoads` green
  against the live DB.

### Repository

- [x] S7-09 — Create `SyncEventRepository.java` in `repository/`:
  - `List<SyncEvent> findByConductorIdAndStatusOrderByCreatedAtAsc(UUID, SyncStatus)`
  - `List<SyncEvent> findByStatusOrderByCreatedAtAsc(SyncStatus)`
    ← retry scheduler fetches all FAILED across all conductors
  - `@Query("SELECT s.status, COUNT(s) FROM SyncEvent s
    WHERE s.conductorId = :conductorId GROUP BY s.status")`
    `List<Object[]> countByStatusForConductor(@Param UUID conductorId)`
  - Add to `TicketRepository`:
    `List<Ticket> findByStatusAndIssuedAtBefore(TicketStatus, Instant)`
    ← ticket expiry scheduler
  - Verify: `./mvnw compile clean`, app boots (derived queries parsed)

  **Deviation (discussed, approved):** two additions and one later replacement.
  (1) Extra `TicketRepository.findByStatus(TicketStatus)`, needed by S7-20's
  `GET /admin/analytics/disputed-tickets`. (2) Extra
  `SyncEventRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)` + JPQL
  `@Query`), added 2026-10-13 by the race-condition fix: a separate method,
  not an override of `findById`, so plain reads stay lock-free. (3) The retry
  query `findByStatusOrderByCreatedAtAsc` was replaced 2026-10-13 by
  `findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc` for the retry cap
  (see S7-16). `findByConductorIdAndStatusOrderByCreatedAtAsc` is built but not
  called yet; kept deliberately for a future per-conductor detail view.

### Configuration

- [x] S7-10 — Add sync + expiry config to `application.properties`:
  ```
  sync.retry.interval-ms=300000
  sync.max-sync-age-days=7
  ticket.expiry.threshold-hours=24
  ticket.expiry.check-interval-ms=3600000
  idempotency.cleanup.interval-ms=86400000
  ```
  - Create `SyncProperties.java` in `config/`:
    `@ConfigurationProperties(prefix="sync")` record: `retryIntervalMs`,
    `maxSyncAgeDays`
  - Create `TicketExpiryProperties.java` in `config/`:
    `@ConfigurationProperties(prefix="ticket.expiry")` record:
    `thresholdHours`, `checkIntervalMs`
  - Create `SyncPropertiesTest` + `TicketExpiryPropertiesTest`
    (ApplicationContextRunner pattern from Sprint 4/5/6)
  - Verify: `./mvnw test -Dtest=SyncPropertiesTest,TicketExpiryPropertiesTest`

  **Deviation (discussed, approved):** the property is
  `sync.retry-interval-ms`, not `sync.retry.interval-ms`. Required for
  binding: with the dot, Spring looks for a nested `retry` object with an
  `intervalMs` field and never binds the flat record field `retryIntervalMs`.
  The hyphen keeps it flat. Two settings added later to `SyncProperties`
  (2026-10-13): `sync.clock-skew-tolerance-minutes=5` (QR expiry and
  future-time checks, see S7-14) and `sync.max-retry-attempts=10` (retry cap,
  see S7-16). `SyncPropertiesTest` asserts all four.

### DTOs

- [x] S7-11 — Request DTOs in `dto/request/` (Java records):
  - `ScanTicketRequestDTO` — ticketId (@NotNull) ← for POST /tickets/scan
  - `SyncEventRequestDTO` — eventId (@NotNull UUID), conductorId (@NotNull UUID),
    userId (@NotNull UUID), passengerQrToken (@NotNull String),
    originStop (@NotBlank), destinationStop (@NotBlank),
    busId (@NotNull UUID), routeId (@NotNull UUID),
    issuedAt (@NotNull Instant), clientFare (BigDecimal — logged only, never trusted)
  - `SyncBatchRequestDTO` — events (@NotEmpty @Valid List<SyncEventRequestDTO>)
    Note: @Valid on nested list required — same lesson as Sprint 3
    `CreateRouteRequestDTO.stops`

- [x] S7-12 — Response DTOs in `dto/response/` (Java records):
  - `SyncEventResultDTO` — eventId (UUID), status (SyncStatus),
    ticketId (UUID nullable), message (String)
    message examples:
    "Ticket issued and paid" (SYNCED)
    "Wallet insufficient — ticket disputed" (DISPUTED)
    "Invalid QR token" (FAILED)
    "Bus mismatch" (FAILED)
    "Event too old" (FAILED)
  - `SyncBatchResponseDTO` — totalEvents (int), synced (int),
    failed (int), disputed (int), results (List<SyncEventResultDTO>)
  - `SyncStatusResponseDTO` — conductorId (UUID), pending (long),
    processing (long), synced (long), failed (long), disputed (long)
  - `DisputedTicketDTO` — ticketId, passengerName, conductorName,
    routeName, originStop, destinationStop, totalFare, issuedAt
  - Verify: `./mvnw compile clean`

### Core Sync Service

- [x] S7-13 — Create `SyncService.java` interface in `service/`

- [x] S7-14 — Create `SyncEventProcessor.java` `@Service` in `service/`:
  CRITICAL: Separate bean — NOT inner class of SyncServiceImpl
  Required for per-event @Transactional isolation

  `processEvent(SyncEventRequestDTO event, UUID conductorId)`
  → `SyncEventResultDTO`:

  ```
  Step 1 — Idempotency check:
  idempotencyKeyRepository.findByKey(event.eventId.toString())
  → Found + not expired → return original result
  → Found + expired → delete, treat as new
  → Not found → proceed

  Step 2 — Find or create SyncEvent:
  syncEventRepository.findById(event.eventId)
  → SYNCED/DISPUTED → return existing result
  → FAILED → allow reprocessing (retry path)
  → Not found → create new (status=PENDING, conductorId, issuedAt, stops, routeId, busId)

  Step 3 — Mark PROCESSING:
  syncEvent.setStatus(PROCESSING)
  syncEventRepository.save(syncEvent)
  → Prevents scheduler double-pick

  Step 4 — Validate issuedAt age:
  if event.issuedAt.isBefore(now.minus(syncProperties.maxSyncAgeDays(), DAYS))
  → SyncEvent FAILED, "Event too old — possible replay or clock manipulation"
  Note: exp from ticket QR is NEVER checked here — only issuedAt age matters

  Step 5 — Validate conductor:
  conductorRepository.findById(conductorId)
  Check: conductor.busId == event.busId
  → Mismatch → SyncEvent FAILED, "Bus mismatch"

  Step 6 — Validate passenger QR:
  jwtUtil.validatePassengerQrToken(event.passengerQrToken)
  Extract userId from JWT
  userRepository.findById(userId)
  → Not found → FAILED "Invalid QR token"
  → UserStatus != ACTIVE → FAILED "Passenger inactive"

  Step 7 — Validate stops:
  routeStopRepository.findByRouteIdAndStopName(routeId, originStop)
  routeStopRepository.findByRouteIdAndStopName(routeId, destinationStop)
  → Either absent → FAILED "Invalid stop"
  → destSequence <= originSequence → FAILED "Invalid direction"

  Step 8 — Recalculate fare (NEVER trust clientFare):
  stagesCrossed = (destStage - originStage) + 1
  adultFare = stagesCrossed × route.farePerStage
  childFare = ceiling(adultFare / 2)
  totalFare = (adults × adultFare) + (children × childFare)
  Note: event.clientFare is logged for analytics only — calculation ignores it

  Step 9 — Wallet conflict resolution:
  walletRepository.findByUserId(userId)
  effectiveBalance = wallet.balance + overdraftLimit

  if effectiveBalance >= totalFare:
  → deduct wallet (@Version optimistic locking)
  → record DEBIT Transaction (referenceId = event.eventId)
  → create Ticket (status=PAID, issuedAt=event.issuedAt ← offline timestamp)
  → SyncEvent → SYNCED
  → store IdempotencyKey (TTL 24hrs)
  → return "Ticket issued and paid"

  if effectiveBalance < totalFare:
  → create Ticket (status=DISPUTED, issuedAt=event.issuedAt)
  → SyncEvent → DISPUTED
  → NO wallet deduction
  → NO IdempotencyKey stored (passenger self-pays via [Pay Now])
  → return "Wallet insufficient — ticket disputed"

  Step 10 — Any unexpected exception:
  → SyncEvent → FAILED
  → log exception
  → return exception message
  → DO NOT rethrow (batch continues)
  ```
  - `@Transactional` on processEvent()
  - Verify: `./mvnw compile clean`

  **Deviations (discussed, approved):**
  - **`SyncEvent` entity reshaped.** Sprint 1's generic `entityType` /
    `entityId` / `payload` (jsonb) fields and the `SyncEntityType` enum were
    replaced with typed sync fields (`conductorId`, `userId`,
    `passengerQrToken`, `busId`, `routeId`, `originStop`, `destinationStop`,
    `issuedAt`, `clientFare`, `resultTicketId`, `errorMessage`). The retry
    scheduler (S7-16) has to rebuild a `SyncEventRequestDTO` from stored
    fields, which a generic JSON payload made awkward and untyped. `eventId`
    lost `@GeneratedValue`: it's client-assigned, so a re-uploaded event is
    recognisable as the same one. The live table's leftover columns were
    dropped 2026-10-13 (see carry-over).
  - **Step 1 skipped: no `IdempotencyKey` lookup or store.** The `SyncEvent`
    row, keyed by the client's `eventId` and never deleted, is already a
    permanent idempotency guard. A separate 24h `IdempotencyKey` would only
    duplicate it, more weakly (it expires). Consequence: the DoD line
    "Exceeds overdraft → … no IdempotencyKey" holds trivially (no path stores
    one).
  - **Step 8: one adult fare only.** The offline payload (and the ticket QR
    from S7-04) carries a single fare, with no adults/children/infants
    breakdown, so `stagesCrossed × farePerStage` for one adult; the ticket is
    stored with `adultCount=1`, `childCount=0`, `infantCount=0`.
  - **Extra validations beyond the plan:** the event's `conductorId` must
    match the JWT's conductor ("Conductor mismatch"); the bus's route must
    match the event's `routeId` ("Route mismatch"); the userId inside the
    passenger QR must match the event's `userId` ("Invalid QR token").
  - **Later changes (2026-10-13), each recorded in full under Open Items /
    Carry-over below:** (a) concurrency: locked lookup
    (`findByIdForUpdate`), `SyncEvent implements Persistable` so a new
    event's save is a real INSERT, `saveAndFlush` at the PROCESSING claim;
    (b) passenger QR validated at **boarding time** (`issuedAt`) via
    `JwtUtil.extractUserIdFromPassengerQrAt`, plus a new
    "Event issued in the future" check, both with a 5-min clock tolerance;
    (c) data problems now end in **`REJECTED`** via a new `reject()` (final,
    never retried), `fail()` is only the unexpected-error catch-all and
    increments `failedAttempts`; REJECTED is treated as final on re-upload
    (`isFinal`).

- [x] S7-15 — Create `SyncServiceImpl.java` in `service/impl/`:

  `processSyncBatch(SyncBatchRequestDTO, UUID conductorId)`:
  ```java
  // NOT @Transactional — each event is its own transaction
  List<SyncEventResultDTO> results = new ArrayList<>();
  int synced = 0, failed = 0, disputed = 0;

  for (SyncEventRequestDTO event : batch.events()) {
      SyncEventResultDTO result =
          syncEventProcessor.processEvent(event, conductorId);
      results.add(result);
      switch (result.status()) {
          case SYNCED -> synced++;
          case FAILED -> failed++;
          case DISPUTED -> disputed++;
      }
  }
  return new SyncBatchResponseDTO(
      batch.events().size(), synced, failed, disputed, results);
  ```

  `getSyncStatus(UUID conductorId)`:
  → `syncEventRepository.countByStatusForConductor(conductorId)`
  → Map `Object[]` rows to `Map<SyncStatus, Long>`
  → Zero-fill missing statuses
  → Return `SyncStatusResponseDTO`

  Verify: `./mvnw compile clean`

  **Deviation (discussed, approved, 2026-10-13):** (1) Each event now goes
  through `processIsolated()`, a per-event try/catch around `processEvent()`.
  Errors thrown at COMMIT (outside `processEvent`'s own try/catch) no longer
  abort the rest of the batch. The event is reported FAILED "Could not be
  processed — please re-upload", **nothing is saved** for it, collisions are
  logged at WARN and anything else at ERROR. (2) A `rejected` count was added
  to `SyncBatchResponseDTO` and `SyncStatusResponseDTO` for the new
  `REJECTED` status. See `docs/SYNC_RACE_CONDITIONS.md` §16 and the Open
  Items / Carry-over notes below.

### Schedulers

- [x] S7-16 — Create `SyncRetryScheduler.java` in `scheduler/`:
  - `@EnableScheduling` on this class (consistent with `@EnableCaching`
    on `RedisConfig` — not on main class)
  - `@Scheduled(fixedDelayString = "${sync.retry.interval-ms}")`
  - Fetch all FAILED events (NOT DISPUTED — never retry disputed):
    `syncEventRepository.findByStatusOrderByCreatedAtAsc(FAILED)`
  - Reconstruct `SyncEventRequestDTO` from stored SyncEvent fields
  - Call `syncEventProcessor.processEvent()` per event
  - Log retry outcome
  - Verify: app boots, scheduler registers in startup log

  **Deviations (discussed, approved):** (1) The placeholder is
  `${sync.retry-interval-ms}` (see S7-10's binding note). (2) 2026-10-13:
  only **FAILED events under the retry cap** are fetched
  (`findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc(FAILED,
  sync.max-retry-attempts=10)`). DISPUTED and the new REJECTED (bad data) are
  never retried, and capped "poison" events are skipped but stay FAILED, so a
  re-upload still works. (3) 2026-10-13: a per-event try/catch, so one
  event's commit-time error doesn't end the run; nothing is saved on error;
  the log line reports the errored count. Tested in `SyncRetrySchedulerTest`.

- [x] S7-17 — Create `TicketExpiryScheduler.java` in `scheduler/`:
  - `@Scheduled(fixedDelayString = "${ticket.expiry.check-interval-ms}")`
  - `ticketRepository.findByStatusAndIssuedAtBefore(ISSUED, threshold)`
    where threshold = `Instant.now().minus(thresholdHours, ChronoUnit.HOURS)`
  - Mark each → EXPIRED, save
  - Log count per run
  - Verify: compile clean

- [x] S7-18 — Create `IdempotencyKeyCleanupScheduler.java` in `scheduler/`:
  - `@Scheduled(fixedDelayString = "${idempotency.cleanup.interval-ms}")`
  - `idempotencyKeyRepository.deleteByExpiresAtBefore(Instant.now())`
    Note: `deleteByExpiresAtBefore` added in Sprint 4 (S4-05) — finally used
  - Log count deleted
  - Verify: compile clean

  **Deviation (discussed, approved):** `IdempotencyKeyRepository.deleteByExpiresAtBefore`
  return type changed from `void` (Sprint 4) to `long`, so the scheduler can
  log how many keys it deleted, as this task asks. Spring Data derived delete
  methods support returning the count; no other caller was affected.

### Controller

- [x] S7-19 — Create `SyncController.java` in `controller/`:
  - `POST /sync/upload` (ROLE_CONDUCTOR):
    → Extract conductorId from `@AuthenticationPrincipal ConductorPrincipal`
    → `@Valid` on request body
    → `syncService.processSyncBatch()`
    → `ApiResponse<SyncBatchResponseDTO>`
  - `GET /sync/status` (ROLE_CONDUCTOR):
    → `syncService.getSyncStatus(conductorId)`
    → `ApiResponse<SyncStatusResponseDTO>`
  - Complete S7-06 remaining items after this task
  - Verify: compile clean, app boots

### Analytics Update

- [x] S7-20 — Update `AnalyticsServiceImpl` + `AnalyticsController`:
  - `GET /admin/analytics/disputed-tickets` (ROLE_ADMIN):
    → `ticketRepository.findByStatus(TicketStatus.DISPUTED)`
    → Enrich: passengerName (User lookup), conductorName (Conductor lookup),
      routeName (Route lookup)
    → Return `List<DisputedTicketDTO>`
  - Verify: compile clean

### Testing & Verification

- [x] S7-21 — Unit tests: `SyncEventProcessorTest.java` in `src/test/`:
  Mock: `SyncEventRepository`, `IdempotencyKeyRepository`,
  `UserRepository`, `ConductorRepository`, `BusRepository`,
  `RouteRepository`, `RouteStopRepository`, `WalletRepository`,
  `TicketRepository`, `TransactionRepository`, `WalletProperties`,
  `SyncProperties`, `JwtUtil`

  - `processEvent_success_sufficientBalance`
    wallet ₹150, fare ₹90 → PAID, -₹90, DEBIT recorded, SYNCED
  - `processEvent_success_overdraftAllowed`
    wallet ₹20, fare ₹90, overdraft ₹100
    effectiveBalance=₹120 >= ₹90 → PAID, -₹70, SYNCED
  - `processEvent_disputed_exceedsOverdraft`
    wallet -₹80, fare ₹90, overdraft ₹100
    effectiveBalance=₹20 < ₹90 → DISPUTED, wallet unchanged,
    no IdempotencyKey stored
  - `processEvent_idempotent_alreadySynced`
    same eventId twice → same ticketId, no duplicate ticket
  - `processEvent_failed_invalidPassengerQr`
    invalid JWT signature → FAILED "Invalid QR token"
  - `processEvent_failed_busMismatch`
    conductor.busId != event.busId → FAILED "Bus mismatch"
  - `processEvent_failed_tooOld`
    event.issuedAt = 10 days ago, maxSyncAgeDays=7 → FAILED "Event too old"
  - `processEvent_partialBatch`
    3 events via SyncServiceImpl: success + disputed + failed
    → all 3 independent, synced=1 disputed=1 failed=1
  - `processEvent_retryAfterFailed`
    previously FAILED → re-uploaded → SYNCED
  - Verify: `./mvnw test -Dtest=SyncEventProcessorTest` — 9/9 pass
  - Full suite: `./mvnw test` — all pass

- [x] S7-22 — Postman verification sequence:

  **QR model verification:**
  1. `GET /user/qr` (passengerToken)
     → expect JWT string (not plain UUID)
     → decode at jwt.io → verify type=PASSENGER_QR, exp=24hrs
  2. `POST /user/qr/refresh` (passengerToken)
     → expect new JWT with fresh exp
  3. `POST /tickets/issue` (conductorToken)
     → note ticketId, originStop, destinationStop, totalFare, conductorId, issuedAt in response
     → frontend would encode full JSON payload as ticket QR
  4. `POST /tickets/scan` (passengerToken, ticketId from step 3)
     → expect ticket details returned (online confirm screen)
  5. `POST /tickets/scan` wrong ticketId
     → expect 404
  6. `POST /tickets/scan` another passenger's ticketId
     → expect 404 (userId mismatch)

  **Normal sync flow:**
  7.  Conductor login → `{{conductorToken}}`
  8.  `GET /sync/status` → all counts = 0
  9.  Update wallet to ₹150 via psql
  10. `POST /sync/upload` — 1 event (valid, wallet ₹150, fare ₹90)
      → synced=1, ticketId non-null
  11. `GET /passenger/tickets` → PAID, issuedAt = offline timestamp (not sync time)
  12. `GET /passenger/wallet/balance` → ₹60
  13. Same eventId again → same ticketId (idempotency ✅)
  14. `GET /sync/status` → synced >= 1

  **Overdraft sync:**
  15. Update wallet to ₹20 via psql
  16. New event → synced=1, balance=-₹70

  **Disputed sync:**
  17. Update wallet to -₹80 via psql
  18. New event → disputed=1
  19. `GET /passenger/tickets` → DISPUTED ticket visible
  20. Passenger self-pay flow:
      `POST /payments/wallet` (passengerToken, amount=fare for DISPUTED ticket)
      → wallet topped up → ticket status updated to PAID
      Note: passenger sees DISPUTED in history → taps [Pay Now] →
      uses existing POST /payments/wallet → no admin needed

  **Replay / too-old event:**
  21. `POST /sync/upload` with issuedAt = 10 days ago
      → rejected=1, "Event too old" (REJECTED since 2026-10-13, was failed=1)

  **Partial batch:**
  22. Batch of 3: valid + invalid QR + bus mismatch
      → synced=1, rejected=2 (both are bad data → REJECTED since 2026-10-13, was failed=2)

  **Ticket expiry:**
  23. Issue ticket → ticketId, status=ISSUED
  24. Backdate via psql:
      `UPDATE ticket SET issued_at = NOW() - INTERVAL '25 hours'`
  25. Set `ticket.expiry.check-interval-ms=5000` temporarily
  26. Wait → `GET /passenger/tickets/{ticketId}` → EXPIRED

  **Disputed analytics:**
  27. `GET /admin/analytics/disputed-tickets` (adminToken)
      → disputed ticket listed with passenger + conductor details

  - Add Sync + QR Scan folders to Postman collection

### Git

- [x] S7-23 — Commit and merge `feature/offline-sync` into `dev`

---

## Dependencies

| Dependency | Status |
|---|---|
| SyncEvent entity (Sprint 1) | ✅ Done |
| SyncStatus enum PENDING/SYNCED/FAILED | ✅ Done — PROCESSING + DISPUTED added S7-07 |
| TicketStatus enum | ✅ Done — DISPUTED added S7-08 |
| IdempotencyKey table + deleteByExpiresAtBefore | ✅ Done (Sprint 4) |
| WalletProperties (overdraft limit) | ✅ Done (Sprint 4) |
| JwtUtil (existing) | ✅ Done — extended for PASSENGER_QR S7-01 |
| TicketRepository.findByTicketIdAndUserId | ✅ Done (Sprint 4) |
| WalletServiceImpl deduction + DEBIT Transaction | ✅ Done (Sprint 4) |
| AnalyticsServiceImpl + Controller | ✅ Done (Sprint 6) |
| User.deviceId field | ✅ Done (Sprint 1) |
| User.mobileNo field (unique constraint) | ✅ Done (Sprint 2) |

---

## Bug found + fixed during S7-22 verification prep (2026-10-10)

**Symptom (would have been):** step 20's self-pay flow — `POST /payments/wallet`
on a `DISPUTED` ticket — would have 400'd with "Ticket is not awaiting
payment" instead of resolving the dispute.

**Root cause:** `WalletServiceImpl.payViaWallet` (Sprint 4) only ever accepted
`TicketStatus.ISSUED`. The *earlier, superseded* draft of this sprint's plan
expected exactly that 400 (DISPUTED needed admin resolution back then); the
**finalized** plan redesigned DISPUTED resolution around this same endpoint
(*"passenger sees DISPUTED in history → taps [Pay Now] → uses existing
`POST /payments/wallet` → no admin needed"*) but never updated the guard to
match. Caught by re-reading the method against the finalized plan before
running the Postman sequence, not during the run itself.

**Fix:** widened the check to `status != ISSUED && status != DISPUTED`. No
other change needed — the deduction amount was already server-computed from
`ticket.getTotalFare()`, independent of which status let it through. New
regression test `payViaWallet_disputedTicket_success` added; full
`WalletServiceImplTest` suite (8/8) passes.

---

## Open Items / Known Limitations (revisit at sprint close)

Running list of gaps raised and consciously deferred during implementation —
not blocking, but not to be forgotten either.

- ~~**`IssueTicketResponseDTO` missing `conductorId`.**~~ **Resolved
  2026-10-11.** S7-04's ticket-QR JSON payload design needs it (alongside
  `ticketId`/`originStop`/`destinationStop`/`totalFare`/`issuedAt`), but the DTO was
  never updated. Flagged during S7-11/12. Fixed by adding `UUID conductorId`
  (after `userId`) to the record and mapping `ticket.getConductorId()` in
  `TicketServiceImpl.toIssueResponseDTO`. No schema change: `Ticket` already
  stored it. `PUT /tickets/{id}/terminate` shares the DTO, so its response
  gains the field too. `issueTicket_success` now asserts it. Full suite:
  92/92 pass. Naming alignment (same day): the plan's QR payload sketches
  originally said `sourceStop`/`fare`, but every real DTO
  (`IssueTicketResponseDTO`, `SyncEventRequestDTO`, `DisputedTicketDTO`) uses
  `originStop`/`totalFare`, the API's established names since Sprint 4's
  `sourceStop`→`originStop` rename. This file was updated to use the API
  names throughout; no code renamed. The QR payload now uses the response
  fields exactly as returned, with no mapping needed on the frontend.
- **`SyncEvent.status = PROCESSING` doesn't actually prevent a scheduler
  double-pick**, despite that being Step 3's stated reason. `processEvent()`
  is one `@Transactional` method — `PROCESSING` is set and then unconditionally
  overwritten by a terminal status (`SYNCED`/`FAILED`/`DISPUTED`) before the
  method returns, so it's never the value present when the transaction
  actually commits. No external reader (including `SyncRetryScheduler`) can
  ever observe `PROCESSING` in the database under the current design. A real
  fix would need the `PROCESSING` write to commit in its own transaction
  (e.g. `@Transactional(propagation = REQUIRES_NEW)`) before `resolve()`
  starts. Not fixed now — the actual financial risk (two concurrent attempts
  both debiting the same wallet) is still caught by `Wallet.version`'s
  optimistic lock, which turns the race into one cleanly-`FAILED` duplicate
  attempt rather than a double deduction. Known limitation, not a correctness
  bug.
  **⚠ Re-assessed 2026-10-11: the paragraph above is inaccurate.** The race
  does not fail "cleanly": the commit-time exception escapes `processEvent()`'s
  `try/catch` and aborts the rest of the batch. The DISPUTED path never writes
  `Wallet`, so `@Version` gives no protection there and duplicate DISPUTED
  tickets are possible. Brand-new events can also be double-processed via
  `save()` → `merge`. Full analysis, diagrams and the proposed fix (Option B:
  pessimistic lock + `Persistable` + per-event `try/catch`) are in
  `docs/SYNC_RACE_CONDITIONS.md`.
  **✅ Resolved 2026-10-13 (deviation, discussed and approved).** Implemented
  Option B: `SyncEventRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`)
  for the check; `SyncEvent implements Persistable<UUID>` (transient
  `isNew`, default `true`, flipped by `@PostLoad`/`@PrePersist`) so a new
  event's save is a real INSERT guarded by the primary key; `saveAndFlush` at
  the PROCESSING claim so a losing duplicate fails before doing any work; and a
  per-event try/catch in both `SyncServiceImpl` and `SyncRetryScheduler` that
  reports FAILED "please re-upload" and **saves nothing**. `PROCESSING` itself
  is still never visible outside the transaction (`GET /sync/status` →
  `processing: 0`), which is accepted. 6 new tests; full suite 98/98. Detail in
  `docs/SYNC_RACE_CONDITIONS.md` §16.
- **All three schedulers (`SyncRetryScheduler`, `TicketExpiryScheduler`,
  `IdempotencyKeyCleanupScheduler`) would run redundantly on every instance**
  if BusLink is ever horizontally scaled. Spring's `@Scheduled` has no
  built-in cross-instance coordination — each instance fires its own copy on
  its own clock. Not a problem today (single-instance deployment per
  `ARCHITECTURE.md`); the fix if/when that changes is a distributed lock
  (e.g. ShedLock) wrapping each `@Scheduled` method, not a Quartz-level
  rewrite. Noted, not actioned.

---

## Carry-over: S7-09..S7-18 review findings (2026-10-11)

Review done task by task against the code. S7-09..S7-13, S7-15, S7-17 and
S7-18 match their task text and are ticked. **S7-14 and S7-16 stay unchecked**
until the decisions below are made. Pick up next session, **before S7-22**.

~~**Blocker: stale `sync_event` columns (needs approval, DB change).**~~
**✅ Resolved 2026-10-13 (deviation, discussed and approved).** One `psql`
transaction: `ALTER TABLE sync_event DROP COLUMN entity_id, DROP COLUMN
entity_type, DROP COLUMN payload, ALTER COLUMN error_message TYPE TEXT`
(`sync_event_entity_type_check` dropped with its column). Entity updated to
match: `errorMessage` → `@Column(columnDefinition = "TEXT")`, so a fresh DB
gets the same shape (`ddl-auto=update` never alters an existing column; both
changes are needed). Verified via `\d sync_event` plus a rollback-wrapped
`INSERT` of exactly the entity's columns with a 600-char `error_message`
(stored in full; 0 rows after `ROLLBACK`). Full suite 98/98,
`contextLoads` green. Options weighed: drop columns (chosen; the table was
empty and this leaves no junk) vs. relax `NOT NULL` (leaves confusing dead
columns) vs. drop and recreate the table (too broad) vs. Flyway (out of
scope this sprint); `TEXT` (chosen; the full message is kept for debugging,
same performance as varchar in Postgres) vs. truncating in Java. Side
finding: before the fix, the NOT NULL failure would have been caught by the
new per-event try/catch and logged as a "concurrent race" (same
`DataIntegrityViolationException` type), so every event would have come back
FAILED for a misleading reason. Original note follows. The
`SyncEvent` entity was reshaped this sprint: Sprint 1's generic
`entityType`/`entityId`/`payload` (and the `SyncEntityType` enum) were
replaced by typed sync fields, which the retry scheduler needs to rebuild a
request. `ddl-auto=update` never drops columns, so the live table still has
`entity_id uuid NOT NULL`, `entity_type varchar NOT NULL` (+
`sync_event_entity_type_check`) and `payload jsonb NOT NULL`. **Every insert
into `sync_event` would fail.** Unit tests are mocked and can't see this; it
would surface at S7-22 step 10. Same `ddl-auto` blind spot as Sprint 4's
`ticket_status_check`. Proposed fix via `psql` (table has 0 rows): drop the 3
columns, and change `error_message` from `varchar(255)` to `TEXT` (the
catch-all stores `ex.getMessage()`, which can exceed 255 chars).

~~**Design decision: passenger QR expiry checked at sync time (S7-14).**~~
**✅ Resolved 2026-10-13 (deviation, discussed and approved).** The rule at
sync time is now "the passenger QR was valid **at boarding time**"
(`event.issuedAt`), not "is valid now". New
`JwtUtil.extractUserIdFromPassengerQrAt(token, at, clockSkew)`: jjwt parser
clock set to `at`, with `clockSkewSeconds`, still verifying signature and
type. It shares a private `parsePassengerQrToken` with the unchanged
`validatePassengerQrToken`, so `GET /user/qr` self-heal (which correctly
checks against now) is untouched. New rule: `issuedAt > now + tolerance` →
FAILED "Event issued in the future — device clock is wrong" (a future time
otherwise slipped past the too-old check). Tolerance is configurable:
`sync.clock-skew-tolerance-minutes=5` (`SyncProperties.clockSkewToleranceMinutes`).
**Lean version chosen:** a `QR iat ≤ issuedAt` lower bound was considered and
dropped. It stops no realistic attack (a cheater would pick a time *inside* an
old QR's window) and would reject honest scans whenever the conductor's phone
clock runs slightly behind the server's. Known remaining gap: a dishonest
device can still pick a time inside an old QR's 24h window. Bounded by
`maxSyncAgeDays`; real defence is the `deviceId` check (Sprint 8). Tests:
4 new `JwtUtilTest` (inside window, just past exp within skew, past exp,
tampered), `processEvent_failed_issuedInFuture`, `SyncPropertiesTest`
updated; full suite 103/103. Original note follows.
`extractUserIdFromPassengerQr` → `validatePassengerQrToken` checks `exp`
against *now*. The QR lives 24h from generation, but offline events may sync
up to 7 days later (`maxSyncAgeDays`), so any event synced more than ~24h
after QR generation is FAILED "Invalid QR token", even though the QR was valid
at boarding. Proposed fix: still verify signature + type at sync, but check
expiry against `event.issuedAt` (a jjwt parser with its clock set to
`issuedAt`), plus a `JwtUtilTest` case. The Postman sequence syncs
immediately, so it won't catch this.

~~**Design decision: permanent failures retried forever (S7-16).**~~
**✅ Resolved 2026-10-13 (deviation, discussed and approved). Option (c).**
- **New `SyncStatus.REJECTED`** for data problems (conductor/bus/route
  mismatch, too old, future, invalid QR, passenger inactive, invalid
  stop/direction): a final state, never retried. `SyncEventProcessor` has a
  new `reject()`; `fail()` (FAILED) is now only the unexpected-error
  catch-all. REJECTED joined SYNCED/DISPUTED in the "already final" check
  (`isFinal`), so a re-upload returns the stored reason without
  re-validating; reprocessing the same data can't change the answer.
  "Passenger inactive" treated as permanent on purpose: if an account is
  reactivated, a background job shouldn't auto-charge the old ride without
  review.
- **Retry cap for FAILED**: new `SyncEvent.failedAttempts` (incremented by
  `fail()` only), `sync.max-retry-attempts=10` (10 × 5 min = 50 min);
  scheduler query is now
  `findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc`. Guards against
  "poison" events (e.g. a code bug that fails every time). A capped event
  stays FAILED, so a conductor re-upload still reprocesses it. The phone is
  the final safety net, so the exact cap number matters little.
- **API:** `rejected` count added to `SyncBatchResponseDTO` and
  `SyncStatusResponseDTO`. Client contract to document in `API.md`: FAILED =
  keep and re-upload later; REJECTED = final, stop sending.
- **DB:** `sync_event_status_check` recreated with `'REJECTED'` (`psql`,
  rollback-wrapped INSERT proof, same as S7-07); `failed_attempts integer
  NOT NULL` added by `ddl-auto` (table empty), confirmed via
  `information_schema`.
- **Tests:** 4 processor tests renamed `*_rejected_*` and now expect
  REJECTED; partial batch expects `rejected=1, failed=0`; new
  `…alreadyRejected_returnsStoredReasonWithoutRevalidating`,
  `…unexpectedError_staysRetryableAndCountsAttempt`,
  `…rejected_doesNotCountAsFailedAttempt`; scheduler and properties tests
  updated. Full suite 106/106.
- **Known limitation:** a DB-level error mid-event aborts the whole
  transaction, so its FAILED write (and the attempt increment) rolls back
  too; such events don't advance toward the cap. Acceptable: the per-event
  catch reports them and a re-upload retries.
- **Noted, out of scope:** "Bus mismatch" compares against the conductor's
  bus assignment at **sync** time, so a conductor reassigned between boarding
  and sync would see an honest event rejected.

Original note follows. FAILED mixes
temporary failures (DB hiccup, lock conflict: worth retrying) with permanent
validation failures ("Event too old", "Bus mismatch", "Invalid stop",
"Invalid QR token": fail identically every time). `SyncRetryScheduler`
retries all of them every 5 min with no cap, and "Event too old" only gets
older. This contradicts the plan's own *"FAILED = technical failure → retry
makes sense"*. Options: (a) new `REJECTED` status for validation failures,
never retried (recommended: same rule as "DISPUTED is never retried"; needs
an enum value + check-constraint update like S7-07); (b) `retryCount` with a
cap; (c) both.

**✅ Recorded 2026-10-14** as "Deviation (discussed, approved)" notes under
S7-09, S7-10, S7-14, S7-15, S7-16 and S7-18. S7-14 is now ticked (compiles,
13 processor tests pass). **S7-16 stays unchecked**: the app boots, but the
task's own Verify line ("scheduler registers in startup log") hasn't been
observed. Spring doesn't log `@Scheduled` registration, and the job returns
silently when there are no FAILED events. Confirm during S7-22 by seeing its
"retried N FAILED event(s)" log line once a FAILED event exists.

~~**Deviations to record (docs only, already in the code):**~~
- `SyncEvent` entity reshape (above).
- S7-10: property is `sync.retry-interval-ms`, not `sync.retry.interval-ms`.
  Required for binding: the dotted form would look for a nested `retry`
  object, not the flat record field `retryIntervalMs`.
- S7-14 Step 1 skipped: no `IdempotencyKey` lookup or store. The `SyncEvent`
  row, keyed by the client's `eventId` and never deleted, is already a
  permanent idempotency guard; a 24h key would only duplicate it, more weakly.
- S7-14 Step 8: one adult fare only. The offline payload carries no
  passenger-count breakdown.
- S7-14 extra validations beyond the plan: event `conductorId` must match the
  JWT, the bus's route must match `routeId`, and the QR's userId must match the
  event's `userId`.
- S7-09: extra `TicketRepository.findByStatus` (used by S7-20).
- S7-18: `deleteByExpiresAtBefore` return type `void` → `long`, so the
  deleted count can be logged.

~~**Also parked:** the race-condition fix (Option B).~~ **Done 2026-10-13**:
see Open Items above and `docs/SYNC_RACE_CONDITIONS.md` §16. Design worked
out by the user via a 14-question walkthrough (§15), then implemented the same
way. **Still to check during S7-22:** the SQL log shows `for no key update` on
the sync lookup, and a plain `insert into sync_event` (no `select` first) for
a new event.

---

## S7-22 Verification Run (2026-10-14)

Run by the user in Postman Desktop against a live app + DB, guided step by
step; `psql` setup/inspection and SQL-log checks done alongside. App started
with **command-line overrides only** (`application.properties` untouched):
`--spring.jpa.show-sql=true --sync.retry-interval-ms=10000
--ticket.expiry.check-interval-ms=5000`, plus
`--idempotency.cleanup.interval-ms=10000` for the restart in check B.

**Adjustments to the plan's text (not failures):**
- Offline sync events are one adult (S7-14 deviation), so the fare is **₹36**,
  not the plan's ₹90. Expected balances became ₹150→₹114 (step 12) and
  ₹20→**−₹16** (step 16), not ₹60 / −₹70.
- **Wallet top-up before step 20** added (`psql` → ₹150): the plan says
  "wallet topped up" but had no step for it, and at −₹80 the passenger can't
  self-pay ₹36 (effective ₹20).
- Step 27 (admin disputed list) run **before** step 20, since paying removes
  the ticket from the list; then re-run after step 20 → `[]`.
- Steps 21–22 expect `rejected` (not `failed`), per the REJECTED change.
- Step 23 reused step 3's still-ISSUED ticket instead of issuing another.
- Collection: re-import resets collection variables, so all three logins
  must be re-run after importing (a 401 on Issue Ticket traced to this).
  Access tokens last 15 min; re-run a login on a 401.

**Results, all passing:**

| Steps | Result |
|---|---|
| 1–2 | `GET /user/qr` self-healed rider1's **pre-Sprint-7 plain-UUID** QR into a 235-char JWT (persisted); `type=PASSENGER_QR`, `exp−iat=86400`. Refresh → new `iat` (+195 s), fresh 24 h. No `deviceId` claim: rider1 has none (Sprint 8 scope). |
| 3 | Issue response includes **`conductorId`** (11 Oct fix) plus every ticket-QR field. |
| 4–6 | Scan → confirm-screen details; unknown id → 404; rider2 scanning rider1's real ticket → **404** (same message as nonexistent; no existence leak). |
| 7–14 | SYNCED; ticket PAID ₹36 with **`issuedAt` = event's boarding time (12:15:37), `paidAt` = sync time (12:24:24)**; wallet ₹114; same `eventId` re-upload → same `ticketId`, DB shows 1 ticket / 1 DEBIT / balance unchanged; status `synced=1`, `processing=0` (expected, see race doc §4). |
| 15–16 | Overdraft → SYNCED, −₹16. |
| 17–20, 27 | DISPUTED with wallet untouched (−₹80, 0 DEBITs); visible in passenger history and admin list (names enriched); self-pay → PAID, ₹150→₹114; admin list then `[]`. |
| 21–22 | Too old → **REJECTED**; mixed batch → 1 SYNCED + 2 REJECTED ("Invalid QR token", "Bus mismatch"), wallet ₹114→₹78; all REJECTED rows `failed_attempts=0`. |
| 23–26 | Step 3's ISSUED ticket backdated 25 h → `TicketExpiryScheduler: expired 1` within 2 s → passenger sees EXPIRED. |
| Final status | `synced 3 · disputed 1 · rejected 3 · failed 0`, exact match with the DB. |

**SQL-log proof of the race-condition fix** (step 10 upload):
`select … from sync_event … where event_id=? for no key update` (row lock,
Part A) → `insert into sync_event` with **no merge-SELECT before it**
(Part B) and **before** any validation query (`saveAndFlush` claim) →
validation chain → `insert transaction` / `insert ticket` →
`update sync_event` → `update wallet … where … and version=?`.

**Extra scheduler checks (`psql` test rows, deleted afterwards):**
- **A, retry job (S7-16):** FAILED event at `failed_attempts=0`, issued 10 days
  ago → within 10 s `SyncRetryScheduler: retried 1 FAILED event(s), 0 errored`
  and it became **REJECTED** "Event too old". FAILED event at
  `failed_attempts=10` (the cap) → **ignored**, unchanged. No retries ever of
  the real DISPUTED/REJECTED rows (zero "retried" log lines before the test).
- **B, idempotency cleanup:** one expired + one live test key, app restarted →
  `purged 1 expired idempotency key(s)`; the live test key **and** step 3's
  real key kept; next run `purged 0`.

**Bonus:** the passenger ticket list came back newest-first by `issuedAt` with
3+ tickets, closing the ordering claim left unchecked at Sprint 4 close.

## Definition of Done

- [x] `GET /user/qr` returns signed JWT (not plain UUID)
- [x] `POST /user/qr/refresh` issues fresh JWT with new 24hr expiry
- [x] `GET /user/qr` self-heals — regenerates expired/tampered token transparently
- [x] `POST /tickets/scan` returns ticket details for payment confirm screen
- [x] `POST /tickets/scan` rejects wrong passenger (userId mismatch)
- [x] `SyncStatus.PROCESSING` + `SyncStatus.DISPUTED` added, DB constraint updated
- [x] `TicketStatus.DISPUTED` added, DB constraint updated
- [x] `IssueTicketResponseDTO` exposes all fields needed for ticket QR JSON payload
- [x] `POST /sync/upload` valid batch → tickets PAID, wallets deducted, SYNCED
- [x] Same eventId twice → same ticketId, no duplicate (idempotency)
- [x] Overdraft scenario → PAID, balance negative
- [x] Exceeds overdraft → DISPUTED, wallet unchanged, no IdempotencyKey
- [x] Event older than maxSyncAgeDays → REJECTED "Event too old" (was FAILED; REJECTED added 2026-10-13)
- [x] Partial batch → each event independent, correct per-event status
- [x] `GET /sync/status` → correct counts per status
- [x] `GET /admin/analytics/disputed-tickets` → disputed tickets listed
- [x] Ticket expiry scheduler marks ISSUED > 24hr tickets as EXPIRED
- [x] SyncRetryScheduler retries FAILED only (never DISPUTED or REJECTED), and stops after sync.max-retry-attempts
- [x] IdempotencyKeyCleanupScheduler deletes expired keys
- [x] All `SyncEventProcessorTest` tests pass (9 planned, 13 after 2026-10-13 additions)
- [x] All 27 Postman verification steps pass
- [x] `feature/offline-sync` merged into `dev`, build clean (`--no-ff` merge `8beb5cc`, `./mvnw clean install` 106/106, 2026-10-14)
