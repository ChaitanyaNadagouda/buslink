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
- Ticket QR → plain JSON payload (ticketId, sourceStop, destinationStop, fare,
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
  "sourceStop": "Majestic",
  "destinationStop": "Hebbal",
  "fare": 25.00,
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
      "sourceStop": "Majestic",
      "destinationStop": "Hebbal",
      "fare": 25.00,
      "conductorId": "uuid",
      "issuedAt": "2026-10-01T15:32:00Z",
      "exp": "2026-10-01T16:32:00Z"
    }
    ```
  - Verify `IssueTicketResponseDTO` exposes: ticketId, sourceStop,
    destinationStop, fare, conductorId, issuedAt
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
  - [ ] `POST /sync/upload` → ROLE_CONDUCTOR (blocked on S7-19 — no
        `SyncController` exists yet to route to)
  - [ ] `GET /sync/status` → ROLE_CONDUCTOR (blocked on S7-19)
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

- [ ] S7-09 — Create `SyncEventRepository.java` in `repository/`:
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

### Configuration

- [ ] S7-10 — Add sync + expiry config to `application.properties`:
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

### DTOs

- [ ] S7-11 — Request DTOs in `dto/request/` (Java records):
  - `ScanTicketRequestDTO` — ticketId (@NotNull) ← for POST /tickets/scan
  - `SyncEventRequestDTO` — eventId (@NotNull UUID), conductorId (@NotNull UUID),
    userId (@NotNull UUID), passengerQrToken (@NotNull String),
    sourceStop (@NotBlank), destinationStop (@NotBlank),
    busId (@NotNull UUID), routeId (@NotNull UUID),
    issuedAt (@NotNull Instant), clientFare (BigDecimal — logged only, never trusted)
  - `SyncBatchRequestDTO` — events (@NotEmpty @Valid List<SyncEventRequestDTO>)
    Note: @Valid on nested list required — same lesson as Sprint 3
    `CreateRouteRequestDTO.stops`

- [ ] S7-12 — Response DTOs in `dto/response/` (Java records):
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
    routeName, sourceStop, destinationStop, fare, issuedAt
  - Verify: `./mvnw compile clean`

### Core Sync Service

- [ ] S7-13 — Create `SyncService.java` interface in `service/`

- [ ] S7-14 — Create `SyncEventProcessor.java` `@Service` in `service/`:
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
  routeStopRepository.findByRouteIdAndStopName(routeId, sourceStop)
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

- [ ] S7-15 — Create `SyncServiceImpl.java` in `service/impl/`:

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

### Schedulers

- [ ] S7-16 — Create `SyncRetryScheduler.java` in `scheduler/`:
  - `@EnableScheduling` on this class (consistent with `@EnableCaching`
    on `RedisConfig` — not on main class)
  - `@Scheduled(fixedDelayString = "${sync.retry.interval-ms}")`
  - Fetch all FAILED events (NOT DISPUTED — never retry disputed):
    `syncEventRepository.findByStatusOrderByCreatedAtAsc(FAILED)`
  - Reconstruct `SyncEventRequestDTO` from stored SyncEvent fields
  - Call `syncEventProcessor.processEvent()` per event
  - Log retry outcome
  - Verify: app boots, scheduler registers in startup log

- [ ] S7-17 — Create `TicketExpiryScheduler.java` in `scheduler/`:
  - `@Scheduled(fixedDelayString = "${ticket.expiry.check-interval-ms}")`
  - `ticketRepository.findByStatusAndIssuedAtBefore(ISSUED, threshold)`
    where threshold = `Instant.now().minus(thresholdHours, ChronoUnit.HOURS)`
  - Mark each → EXPIRED, save
  - Log count per run
  - Verify: compile clean

- [ ] S7-18 — Create `IdempotencyKeyCleanupScheduler.java` in `scheduler/`:
  - `@Scheduled(fixedDelayString = "${idempotency.cleanup.interval-ms}")`
  - `idempotencyKeyRepository.deleteByExpiresAtBefore(Instant.now())`
    Note: `deleteByExpiresAtBefore` added in Sprint 4 (S4-05) — finally used
  - Log count deleted
  - Verify: compile clean

### Controller

- [ ] S7-19 — Create `SyncController.java` in `controller/`:
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

- [ ] S7-20 — Update `AnalyticsServiceImpl` + `AnalyticsController`:
  - `GET /admin/analytics/disputed-tickets` (ROLE_ADMIN):
    → `ticketRepository.findByStatus(TicketStatus.DISPUTED)`
    → Enrich: passengerName (User lookup), conductorName (Conductor lookup),
      routeName (Route lookup)
    → Return `List<DisputedTicketDTO>`
  - Verify: compile clean

### Testing & Verification

- [ ] S7-21 — Unit tests: `SyncEventProcessorTest.java` in `src/test/`:
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

- [ ] S7-22 — Postman verification sequence:

  **QR model verification:**
  1. `GET /user/qr` (passengerToken)
     → expect JWT string (not plain UUID)
     → decode at jwt.io → verify type=PASSENGER_QR, exp=24hrs
  2. `POST /user/qr/refresh` (passengerToken)
     → expect new JWT with fresh exp
  3. `POST /tickets/issue` (conductorToken)
     → note ticketId, sourceStop, destinationStop, fare, issuedAt in response
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
      → failed=1, "Event too old"

  **Partial batch:**
  22. Batch of 3: valid + invalid QR + bus mismatch
      → synced=1, failed=2

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

- [ ] S7-23 — Commit and merge `feature/offline-sync` into `dev`

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

## Definition of Done

- [x] `GET /user/qr` returns signed JWT (not plain UUID)
- [x] `POST /user/qr/refresh` issues fresh JWT with new 24hr expiry
- [x] `GET /user/qr` self-heals — regenerates expired/tampered token transparently
- [x] `POST /tickets/scan` returns ticket details for payment confirm screen
- [x] `POST /tickets/scan` rejects wrong passenger (userId mismatch)
- [x] `SyncStatus.PROCESSING` + `SyncStatus.DISPUTED` added, DB constraint updated
- [x] `TicketStatus.DISPUTED` added, DB constraint updated
- [ ] `IssueTicketResponseDTO` exposes all fields needed for ticket QR JSON payload
- [ ] `POST /sync/upload` valid batch → tickets PAID, wallets deducted, SYNCED
- [ ] Same eventId twice → same ticketId, no duplicate (idempotency)
- [ ] Overdraft scenario → PAID, balance negative
- [ ] Exceeds overdraft → DISPUTED, wallet unchanged, no IdempotencyKey
- [ ] Event older than maxSyncAgeDays → FAILED "Event too old"
- [ ] Partial batch → each event independent, correct per-event status
- [ ] `GET /sync/status` → correct counts per status
- [ ] `GET /admin/analytics/disputed-tickets` → disputed tickets listed
- [ ] Ticket expiry scheduler marks ISSUED > 24hr tickets as EXPIRED
- [ ] SyncRetryScheduler retries FAILED only (never DISPUTED)
- [ ] IdempotencyKeyCleanupScheduler deletes expired keys
- [ ] All 9 `SyncEventProcessorTest` tests pass
- [ ] All 27 Postman verification steps pass
- [ ] `feature/offline-sync` merged into `dev`, build clean
