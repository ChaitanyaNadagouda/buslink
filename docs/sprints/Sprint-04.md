# Sprint 4

## Goal

Implement the full ticket issuance and wallet payment flow end-to-end. By end of
sprint a conductor can issue a ticket (with idempotency), the passenger receives
a payment request, pays via wallet (with overdraft support), the ticket moves to
PAID, and the conductor can view and terminate pending unpaid tickets.

---

## Scope

- `IdempotencyKey` entity — new table for deduplicating ticket issue requests
- `TicketRepository`, `TransactionRepository`, `PaymentRepository` — new repositories
- Ticket issuance flow — `POST /tickets/issue` (ROLE_CONDUCTOR)
  - QR validation → fare calculation → idempotency check → ticket creation
  - Returns immediately (ISSUED status) — conductor does not wait
- Conductor pending tickets — `GET /conductor/tickets/pending` (ROLE_CONDUCTOR)
- Conductor ticket termination — `PUT /tickets/{ticketId}/terminate` (ROLE_CONDUCTOR)
- Wallet payment flow — `POST /payments/wallet` (ROLE_PASSENGER)
  - Balance check with configurable overdraft limit (₹100 default)
  - Optimistic locking on wallet deduction
  - Transaction recording (DEBIT)
  - Ticket status → PAID
- Passenger ticket history — `GET /passenger/tickets` (ROLE_PASSENGER)
- Passenger ticket detail — `GET /passenger/tickets/{ticketId}` (ROLE_PASSENGER)
- Wallet balance — `GET /passenger/wallet/balance` (ROLE_PASSENGER)
- Wallet transaction history — `GET /passenger/wallet/transactions` (ROLE_PASSENGER)
- Unit tests — `TicketServiceImplTest`, `WalletServiceImplTest`
- Integration test — full issue → pay flow

**Out of scope:** UPI payment backend (Sprint 5), wallet recharge (Sprint 5),
overdraft recovery on recharge (Sprint 5), ticket expiry scheduler (Sprint 7),
WebSocket push (Sprint 6/7), admin analytics (Sprint 6), Flyway (deferred).

---

## Pre-existing gaps found during plan review (2026-07-29)

Checked this plan against the actual current `Ticket`/`TicketStatus` state before
starting — two mismatches would have broken compilation if built literally as
written. Both are resolved in the Tasks section below (S4-15), not left implicit:

- **`Ticket.sourceStop` vs. this plan's `originStop`** — the entity field (Sprint 1)
  is actually named `sourceStop`, but every DTO/service reference in this plan
  assumes `originStop`, matching `Route`/`RouteStop`'s existing naming. Decision:
  rename the entity field to `originStop` — the `ticket` table is currently empty,
  so this is a free rename now, same manual-drop-old-column treatment as Sprint 3's
  `fare` → `totalFare` rename.
- **`TicketStatus.TERMINATED` doesn't exist** — the enum only has
  `ISSUED, PAID, EXPIRED, CANCELLED`. Sprint 1's design notes describe `CANCELLED`
  as being "for conductor/admin voids," which overlaps with what `terminateTicket`
  does here. Decision: add `TERMINATED` as its own distinct value rather than
  reusing `CANCELLED` — keeps the door open for `CANCELLED` to mean something
  different later (e.g. an admin/fraud action) without conflating it with a
  conductor voiding an unpaid ticket.

---

## Tasks

### New Entity

- [x] S4-01 — Create `IdempotencyKey.java` in `entity/` — new entity:
  - `key` (VARCHAR, PK — the client-sent UUID string, not generated — no
    `@GeneratedValue`)
  - `ticketId` (UUID, not null — points to the ticket created for this key)
  - `createdAt` (Instant, not null)
  - `expiresAt` (Instant, not null — 24hrs after createdAt)
  - Table: `idempotency_key`
  - Does NOT extend `BaseEntity` — no Hibernate `@CreationTimestamp` either:
    `createdAt`/`expiresAt` are both set explicitly by the service layer
    together (`now`, `now + ttl`) when the key is first stored (S4-12), not
    auto-stamped independently — letting Hibernate own `createdAt` while the
    service separately computes `expiresAt` would risk the two being based
    on slightly different clock reads
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app booted against
    live Postgres, `psql \d idempotency_key` confirms `key` (VARCHAR PK),
    `ticket_id` (UUID not null), `created_at`/`expires_at` (timestamptz not
    null) — matches spec exactly

### New Repositories

- [x] S4-02 — `TicketRepository.java` in `repository/` — extend
  `JpaRepository<Ticket, UUID>`:
  - `List<Ticket> findByConductorIdAndStatusOrderByIssuedAtAsc(UUID conductorId, TicketStatus status)`
  - `List<Ticket> findByUserIdOrderByIssuedAtDesc(UUID userId)`
  - `Optional<Ticket> findByTicketIdAndConductorId(UUID ticketId, UUID conductorId)`
  - `Optional<Ticket> findByTicketIdAndUserId(UUID ticketId, UUID userId)`

- [x] S4-03 — `TransactionRepository.java` in `repository/` — extend
  `JpaRepository<Transaction, UUID>`:
  - `List<Transaction> findByUserIdOrderByCreatedAtDesc(UUID userId)`

- [x] S4-04 — `PaymentRepository.java` in `repository/` — extend
  `JpaRepository<Payment, UUID>`:
  - **Gap found and fixed:** the plan asked for `findByTicketId(UUID ticketId)`,
    but `Payment` (Sprint 1) has no `ticketId` field — only a generic
    `referenceId` (deliberately generic since a `Payment` isn't always
    ticket-related, e.g. a wallet top-up references nothing ticket-side).
    `findByTicketId` would have failed at startup with a
    `PropertyReferenceException`. Confirmed with the user: implemented as
    `Optional<Payment> findByReferenceId(UUID referenceId)` instead — for
    UPI later, unused by this sprint's own logic (`WalletServiceImpl` never
    touches `Payment`, only `Wallet`/`Transaction`/`Ticket`)
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

- [x] S4-05 — `IdempotencyKeyRepository.java` in `repository/` — extend
  `JpaRepository<IdempotencyKey, String>` (String PK — the key itself):
  - `Optional<IdempotencyKey> findByKey(String key)`
  - `void deleteByExpiresAtBefore(Instant now)` ← cleanup, used by scheduler Sprint 7
  - Verified all 4 new repositories: `./mvnw compile clean` — BUILD SUCCESS;
    app booted against live Postgres, no `PropertyReferenceException` for
    any of the new derived query methods

### Configuration

- [x] S4-06 — Add wallet overdraft config to `application.properties`:
  ```
  wallet.overdraft-limit=100.00
  ```
  - **Gap found and fixed:** the plan's key was `wallet.overdraft.limit` (a dot),
    described alongside a flat `overdraftLimit` field under `prefix="wallet"`.
    Those two don't actually bind together — Spring's relaxed binding only
    equates camelCase/kebab-case/snake_case *within one property segment*; a
    literal `.` always means one more level of nesting to the binder, never a
    word-boundary. As written, `overdraftLimit` would have silently bound to
    `null` — no error, no exception, just a field that's never set — and only
    surfaced later as an NPE inside `WalletServiceImpl.payViaWallet()`.
    Confirmed with the user: fixed by using a kebab-case flat key
    (`wallet.overdraft-limit`) instead, keeping the simple one-field
    `WalletProperties` shape the plan intended.
  - Created `WalletProperties.java` in `config/` — a **record**
    (`@ConfigurationProperties(prefix="wallet")`, one field `overdraftLimit`)
    rather than a Lombok `@Getter/@Setter` class — idiomatic modern Spring
    Boot style for immutable configuration properties, and simpler than a
    mutable POJO for a single value
  - Added `@ConfigurationPropertiesScan` to `BusLinkApplication` (over
    `@EnableConfigurationProperties(WalletProperties.class)` — scans
    automatically, no need to list every properties class by name as more
    get added)
  - Verified two ways: `./mvnw compile clean` (BUILD SUCCESS) — but compile
    success can't prove the *value* actually binds, only that the code is
    valid, so also added `WalletPropertiesTest.java` (`ApplicationContextRunner`,
    binds `WalletProperties` in isolation with the real property key) — passes,
    confirms `overdraftLimit` resolves to `100.00`. Empirically re-ran the same
    test with the *original* dotted key (`wallet.overdraft.limit`) to confirm
    it really does silently bind to `null` — it does, validating the gap was
    real before removing that throwaway proof

- [x] S4-07 — Add idempotency key TTL config to `application.properties`:
  ```
  ticket.idempotency.ttl-hours=24
  ```

### DTOs

- [x] S4-08 — Request DTOs in `dto/request/` (Java records):
  - `IssueTicketRequestDTO` — qrToken (@NotBlank), busId (@NotNull),
    routeId (@NotNull), originStop (@NotBlank), destinationStop (@NotBlank),
    adults (@NotNull, @Min=1), children (@NotNull, @Min=0),
    infants (@NotNull, @Min=0)
    Note: minimum 1 adult — a ticket must have at least one paying passenger
  - `WalletPaymentRequestDTO` — ticketId (@NotNull) only; `userId` deliberately
    excluded (comes from `@AuthenticationPrincipal`, never trusted from the
    request body) and `amount` excluded (service computes it from
    `ticket.totalFare`, never trusts a client-supplied amount)
  - Verified: `./mvnw compile` — BUILD SUCCESS after each DTO

- [x] S4-09 — Response DTOs in `dto/response/` (Java records):
  - `IssueTicketResponseDTO` — ticketId, userId, originStop, destinationStop,
    stagesCrossed, adults, children, infants, adultFare, childFare, totalFare,
    status, issuedAt
    Note: 5-second auto-dismiss metadata is frontend-only, not in response
  - `PendingTicketResponseDTO` — ticketId, passengerName, passengerQrToken,
    originStop, destinationStop, totalFare, status, issuedAt, minutesSinceIssue
    (minutesSinceIssue computed in service as ChronoUnit.MINUTES.between(issuedAt, now))
  - `WalletPaymentResponseDTO` — ticketId, amountDeducted, walletBalanceAfter,
    ticketStatus, paidAt
  - `TicketDetailResponseDTO` — full ticket details for passenger view:
    ticketId, conductorName, busNumber, routeNumber, originStop,
    destinationStop, stagesCrossed, adults, children, infants,
    adultFare, childFare, totalFare, status, issuedAt, paidAt (nullable)
  - `WalletBalanceResponseDTO` — balance, status, lastUpdated
  - `TransactionResponseDTO` — transactionId, amount, type, status,
    referenceId, createdAt
  - Verify: `./mvnw compile clean`

### Security Config Update

- [x] S4-10 — Update `SecurityConfig.java` — add new endpoint rules:
  ```
  POST /tickets/issue          → ROLE_CONDUCTOR
  GET  /conductor/tickets/**   → ROLE_CONDUCTOR
  PUT  /tickets/*/terminate    → ROLE_CONDUCTOR
  POST /payments/wallet        → ROLE_PASSENGER
  GET  /passenger/**           → ROLE_PASSENGER
  ```
  - **Gap found:** `GET /conductor/tickets/**` needed no new rule — already
    covered by the existing Sprint 3 rule
    `.requestMatchers("/conductor/**").hasRole("CONDUCTOR")`, which applies
    to all HTTP methods under `/conductor/**`. Adding an explicit duplicate
    would have been dead code; skipped. The 4 rules actually added:
    `POST /tickets/issue`, `PUT /tickets/*/terminate`, `POST /payments/wallet`,
    `GET /passenger/**` (all `ROLE_CONDUCTOR`/`ROLE_PASSENGER` as specified).
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; booted app live against
    Postgres — no-token `/user/profile`, `/conductor/profile`, `/admin/routes`
    all still `401`; conductor login still returns `200` with valid
    access/refresh tokens. Existing auth flows unaffected.

### Ticket Entity Updates

- [x] S4-15 — Update `Ticket.java` in `entity/`, and `TicketStatus.java` in
  `enums/` — resolves the two gaps found during plan review (see above):
  - Added `paidAt` field to `Ticket` (Instant, nullable — null until payment
    confirmed); `ddl-auto=update` added the column automatically
  - Renamed `Ticket.sourceStop` → `originStop` (matches `Route`/`RouteStop`
    naming). Grepped first — `sourceStop` had no references outside the
    entity itself, so nothing else needed updating. `ddl-auto=update` added
    a new `origin_stop` column rather than renaming the existing one;
    manually dropped the old `source_stop` column via `psql` afterward
    (`ticket` table was empty, no backfill needed — same treatment as
    Sprint 3's `fare` → `totalFare` rename)
  - Added `TERMINATED` to `TicketStatus` enum (alongside existing
    `ISSUED, PAID, EXPIRED, CANCELLED`)
  - **Gap found and fixed (unrelated to the rename):** Postgres still had a
    Hibernate-generated `ticket_status_check` constraint from Sprint 1's
    `ddl-auto=create`, hardcoded to the original 4 enum values.
    `ddl-auto=update` never alters existing constraints, so `TERMINATED`
    would compile fine and pass mocked unit tests but fail at the DB layer
    the moment `terminateTicket()` (S4-12) tries to persist it — a runtime
    failure invisible to anything except a live DB. Proved it empirically
    with a rollback-wrapped `INSERT ... status='TERMINATED'` before treating
    it as real (confirmed: `ERROR: violates check constraint
    "ticket_status_check"`). Fixed via `psql`: dropped and recreated the
    constraint with `TERMINATED` included; re-ran the same insert test to
    confirm it now succeeds.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app booted against
    live Postgres — `psql \d ticket` confirms `paid_at`/`origin_stop`
    present, `source_stop` gone, `ticket_status_check` now includes all 5
    values; app restart afterward shows zero schema diff (no ALTER/CREATE
    statements, no errors).

### Ticket Service

- [x] S4-11 — Create `TicketService.java` interface in `service/`

- [x] S4-12 — Create `TicketServiceImpl.java` in `service/impl/`:

  **`issueTicket(IssueTicketRequestDTO, UUID conductorId, String idempotencyKey)`:**
  1. Idempotency check — `idempotencyKeyRepository.findByKey(idempotencyKey)`:
     - Found + not expired → return original ticket (fetch by stored ticketId)
     - Found + expired → treat as new request (delete old key, proceed)
     - Not found → proceed
  2. Validate QR token → `userRepository.findByQrToken(qrToken)`
     → throw `ResourceNotFoundException("Passenger not found")` if absent
  3. Validate passenger status = ACTIVE
     → throw `ValidationException("Passenger account is not active")` if not
  4. Validate conductor's bus = request's busId
     → `conductorRepository.findById(conductorId)` → check `conductor.busId == request.busId`
     → throw `ValidationException("Bus mismatch")` if not
     (prevents conductor issuing tickets for another bus's route)
  5. Validate bus's routeId = request's routeId
     → `busRepository.findById(busId)` → check `bus.routeId == request.routeId`
     → throw `ValidationException("Route mismatch")` if not
  6. Validate origin and destination stops exist on route:
     → `routeStopRepository.findByRouteIdAndStopName(routeId, originStop)`
     → `routeStopRepository.findByRouteIdAndStopName(routeId, destinationStop)`
     → throw `ResourceNotFoundException` if either absent
  7. Validate destination sequence > origin sequence
     → throw `ValidationException("Destination must be after origin")` if not
  8. Calculate fare:
     → `stagesCrossed = (destStage - originStage) + 1`
     → `adultFare = stagesCrossed × route.farePerStage`
     → `childFare = ceiling(adultFare / 2)`
     → `totalFare = (adults × adultFare) + (children × childFare)`
  9. Build and save `Ticket` (status = ISSUED, issuedAt = Instant.now())
  10. Store idempotency key:
      → `IdempotencyKey(key, ticket.ticketId, now, now + 24hrs)`
      → `idempotencyKeyRepository.save(...)`
  11. Return `IssueTicketResponseDTO`
  - `@Transactional` — ticket save + idempotency key save together
  - **Gap found and fixed:** the plan text hardcoded `now + 24hrs`, but S4-07
    already added `ticket.idempotency.ttl-hours=24` to `application.properties`
    for exactly this purpose — building it as literally written would leave
    that config dead/unused. Added `TicketProperties` (`config/`, `record`,
    `@ConfigurationProperties(prefix="ticket.idempotency")`, field `ttlHours`),
    mirroring `WalletProperties`'s pattern exactly. Verified the binding itself
    (not just compile success) with `TicketPropertiesTest`
    (`ApplicationContextRunner`, same shape as `WalletPropertiesTest`) — passes,
    confirms `ttlHours` resolves to `24` from the kebab-case property key.

  **`getPendingTickets(UUID conductorId)`:**
  → `ticketRepository.findByConductorIdAndStatusOrderByIssuedAtAsc(conductorId, ISSUED)`
  → map to `PendingTicketResponseDTO` with `minutesSinceIssue` computed
  → fetch passenger name via `userRepository.findById(ticket.userId)`

  **`terminateTicket(UUID ticketId, UUID conductorId)`:**
  1. Fetch ticket → `ticketRepository.findByTicketIdAndConductorId(ticketId, conductorId)`
     → throw `ResourceNotFoundException` if not found
     (also implicitly validates this conductor owns this ticket)
  2. Validate status = ISSUED
     → throw `ValidationException("Only ISSUED tickets can be terminated")` if not
  3. Update status = TERMINATED
  4. Save and return updated `IssueTicketResponseDTO`

  **`getPassengerTickets(UUID userId)`:**
  → `ticketRepository.findByUserIdOrderByIssuedAtDesc(userId)`
  → map to `TicketDetailResponseDTO`
  → enrich with conductorName (Conductor lookup), busNumber (Bus lookup),
    routeNumber (Route lookup) — accept N+1 for now, same reasoning as BusServiceImpl

  **`getTicketById(UUID ticketId, UUID userId)`:**
  → `ticketRepository.findByTicketIdAndUserId(ticketId, userId)`
  → throw `ResourceNotFoundException` if absent
  → map to `TicketDetailResponseDTO`

  - Verified: `./mvnw compile clean` — BUILD SUCCESS; `TicketPropertiesTest`
    passes (see gap note above).

### Wallet Service

- [x] S4-13 — Create `WalletService.java` interface in `service/`

- [x] S4-14 — Create `WalletServiceImpl.java` in `service/impl/`:

  **`payViaWallet(WalletPaymentRequestDTO, UUID userId)`:**
  1. Fetch ticket → `ticketRepository.findByTicketIdAndUserId(ticketId, userId)`
     → throw `ResourceNotFoundException` if absent
     (also validates this passenger owns this ticket)
  2. Validate ticket status = ISSUED
     → throw `ValidationException("Ticket is not awaiting payment")` if not
     (handles already-PAID, TERMINATED, EXPIRED cases)
  3. Fetch wallet → `walletRepository.findByUserId(userId)`
     → throw `ResourceNotFoundException` if absent
  4. Validate wallet status = ACTIVE
     → throw `ValidationException("Wallet is not active")` if not
  5. Overdraft check:
     ```java
     BigDecimal overdraftLimit = walletProperties.getOverdraftLimit();
     BigDecimal effectiveBalance = wallet.getBalance().add(overdraftLimit);
     if (effectiveBalance.compareTo(ticket.getTotalFare()) < 0) {
         throw new ValidationException(
             "Insufficient balance. Available: ₹"
             + effectiveBalance + ", Required: ₹" + ticket.getTotalFare()
         );
     }
     ```
  6. Deduct wallet — optimistic locking via `@Version` on `Wallet`:
     ```java
     wallet.setBalance(wallet.getBalance().subtract(ticket.getTotalFare()));
     walletRepository.save(wallet);
     // ObjectOptimisticLockingFailureException → already handled by
     // GlobalExceptionHandler (409) from Sprint 2
     ```
  7. Record `Transaction`:
     - type = DEBIT
     - amount = ticket.totalFare
     - status = SUCCESS
     - referenceId = ticket.ticketId
     - userId = userId
  8. Update ticket status = PAID
  9. Set `ticket.paidAt` = Instant.now()
     (`paidAt` field added to `Ticket` in S4-15)
  10. Save transaction + ticket
  11. Return `WalletPaymentResponseDTO`
  - `@Transactional` — wallet deduction + transaction + ticket update
    must all succeed or all fail together

  **`getWalletBalance(UUID userId)`:**
  → `walletRepository.findByUserId(userId)` → map to `WalletBalanceResponseDTO`

  **`getTransactionHistory(UUID userId)`:**
  → `transactionRepository.findByUserIdOrderByCreatedAtDesc(userId)`
  → map to `TransactionResponseDTO`

  - Verified: `./mvnw compile clean` — BUILD SUCCESS. `walletProperties
    .getOverdraftLimit()` in the plan pseudocode adjusted to
    `walletProperties.overdraftLimit()` — `WalletProperties` is a record
    (S4-06), so its accessor is the record-style name, not a POJO getter.
    No manual locking code needed for step 6 — `Wallet.version` (`@Version`,
    Sprint 1) makes `walletRepository.save(wallet)` fail automatically with
    `ObjectOptimisticLockingFailureException` on a concurrent write, already
    mapped to `409` by `GlobalExceptionHandler` since Sprint 2.

### Controllers

- [x] S4-16 — Create `TicketController.java` in `controller/`
  (conductor-facing — ROLE_CONDUCTOR):
  - `POST /tickets/issue`
    → extract `X-Idempotency-Key` header (throw `ValidationException` if missing)
    → extract `conductorId` from `@AuthenticationPrincipal ConductorPrincipal`
    → `TicketServiceImpl.issueTicket()` → `ApiResponse<IssueTicketResponseDTO>`
  - `GET /conductor/tickets/pending`
    → `TicketServiceImpl.getPendingTickets()` → `ApiResponse<List<PendingTicketResponseDTO>>`
  - `PUT /tickets/{ticketId}/terminate`
    → `TicketServiceImpl.terminateTicket()` → `ApiResponse<IssueTicketResponseDTO>`
  - All `@Valid` on request bodies
  - **Design note:** no class-level `@RequestMapping` — `/tickets/issue`,
    `/conductor/tickets/pending`, and `/tickets/{ticketId}/terminate` don't
    share one path root, so each method carries its own full path instead of
    forcing an artificial common prefix.
  - **Design note:** `X-Idempotency-Key` read via `@RequestHeader(required
    = false)` + a manual null/blank check throwing `ValidationException`,
    rather than `required = true`. Spring's own missing-header rejection
    doesn't go through `GlobalExceptionHandler` and wouldn't come back in
    the app's uniform `ApiResponse` shape — the manual check keeps every
    error response consistent.

- [x] S4-17 — Create `PassengerController.java` in `controller/`
  (passenger-facing — ROLE_PASSENGER):
  - `GET /passenger/tickets`
    → `TicketServiceImpl.getPassengerTickets()` → `ApiResponse<List<TicketDetailResponseDTO>>`
  - `GET /passenger/tickets/{ticketId}`
    → `TicketServiceImpl.getTicketById()` → `ApiResponse<TicketDetailResponseDTO>`
  - `GET /passenger/wallet/balance`
    → `WalletServiceImpl.getWalletBalance()` → `ApiResponse<WalletBalanceResponseDTO>`
  - `GET /passenger/wallet/transactions`
    → `WalletServiceImpl.getTransactionHistory()` → `ApiResponse<List<TransactionResponseDTO>>`

- [x] S4-18 — Create `PaymentController.java` in `controller/`
  (passenger-facing — ROLE_PASSENGER):
  - `POST /payments/wallet`
    → extract `userId` from `@AuthenticationPrincipal UserPrincipal`
    → `WalletServiceImpl.payViaWallet()` → `ApiResponse<WalletPaymentResponseDTO>`
  - Verified all 3 controllers: `./mvnw compile clean` — BUILD SUCCESS; app
    booted live against Postgres with zero wiring errors. Spot-checked live:
    no-token `POST /tickets/issue`, `GET /passenger/tickets`, `POST
    /payments/wallet` all correctly `401`; conductor login + `POST
    /tickets/issue` without the idempotency header returns `400` with
    `"X-Idempotency-Key header is required"` in the standard `ApiResponse`
    shape (not a raw Spring error).

### Testing & Verification

- [x] S4-19 — Unit tests: `TicketServiceImplTest.java` in `src/test/`:
  - Mock: `TicketRepository`, `UserRepository`, `ConductorRepository`,
    `BusRepository`, `RouteRepository`, `RouteStopRepository`,
    `IdempotencyKeyRepository`, plus `TicketProperties` (needed since S4-12
    added it as a constructor dependency — Mockito 5.23.0's default mock
    maker mocks records/final classes with no extra setup)
  - `issueTicket_success` — verify ticket saved with ISSUED status,
    idempotency key saved, correct fare calculated
  - `issueTicket_idempotentRequest` — same key sent twice →
    second call returns first ticket without creating new one
  - `issueTicket_invalidQrToken` → `ResourceNotFoundException` thrown
  - `issueTicket_inactivePassenger` → `ValidationException` thrown
  - `issueTicket_busMismatch` → `ValidationException` thrown
  - `issueTicket_destinationBeforeOrigin` → `ValidationException` thrown
  - `terminateTicket_success` — verify status = TERMINATED
  - `terminateTicket_alreadyPaid` → `ValidationException` thrown
  - Verified: `./mvnw test -Dtest=TicketServiceImplTest` — 8/8 pass; full
    `./mvnw test` — 36/36 pass. One unrelated flaky failure surfaced on the
    first full-suite run (`JwtUtilTest.isTokenValid_returnsFalse_for
    TamperedToken`, a pre-existing Sprint 2 test untouched this session) —
    passed both in isolation and on a clean re-run of the full suite, so not
    a regression from this sprint's work.

- [x] S4-20 — Unit tests: `WalletServiceImplTest.java` in `src/test/`:
  - Mock: `WalletRepository`, `TicketRepository`,
    `TransactionRepository`, `WalletProperties`
  - `payViaWallet_success_sufficientBalance` — balance deducted,
    transaction recorded, ticket → PAID
  - `payViaWallet_success_overdraftAllowed` — balance = ₹20,
    fare = ₹90, overdraft = ₹100 → allowed, balance = -₹70
  - `payViaWallet_rejected_exceedsOverdraft` — balance = ₹0,
    fare = ₹110, overdraft = ₹100 → `ValidationException` thrown
  - `payViaWallet_ticketNotFound` → `ResourceNotFoundException` thrown
  - `payViaWallet_ticketAlreadyPaid` → `ValidationException` thrown
  - `payViaWallet_walletInactive` → `ValidationException` thrown
  - `getWalletBalance_success` — returns correct balance
  - Verified: `./mvnw test -Dtest=WalletServiceImplTest` — 7/7 pass; full
    `./mvnw test` — all green (`JwtUtilTest`'s earlier flaky failure from
    S4-19 did not recur).

- [x] S4-21 — Postman verification sequence (in order):
  1. `POST /auth/register` → register test passenger
     (or use existing registered passenger from Sprint 2)
  2. `POST /auth/login` → get passenger token + store as `{{passengerToken}}`
  3. `GET /passenger/wallet/balance` (passenger token)
     → expect balance = 0.00 (fresh wallet from registration)
  4. Manually update wallet balance to ₹150 via psql:
     `UPDATE wallet SET balance = 150.00 WHERE user_id = '<uuid>';`
     (no recharge endpoint yet — Sprint 5)
  5. `POST /conductor/auth/login` → get conductor token
  6. `POST /tickets/issue` (conductor token, `X-Idempotency-Key: <uuid>`)
     → body: qrToken, busId, routeId, originStop="HSR Layout",
       destinationStop="KR Puram Railway Station", adults=2, children=1, infants=1
     → expect 200, status=ISSUED, totalFare=90.00
  7. `POST /tickets/issue` (same request + SAME idempotency key)
     → expect 200, SAME ticketId returned (idempotency working)
  8. `GET /conductor/tickets/pending` (conductor token)
     → expect 1 ticket in list, minutesSinceIssue >= 0
  9. `GET /passenger/tickets` (passenger token)
     → expect 1 ticket, status=ISSUED
  10. `POST /payments/wallet` (passenger token, ticketId from step 6)
      → expect 200, status=PAID, walletBalanceAfter=60.00
  11. `GET /passenger/wallet/balance` (passenger token)
      → expect balance=60.00
  12. `GET /passenger/wallet/transactions` (passenger token)
      → expect 1 DEBIT transaction, amount=90.00
  13. `GET /conductor/tickets/pending` (conductor token)
      → expect empty list (ticket is now PAID, not ISSUED)
  14. `GET /passenger/tickets/{ticketId}` (passenger token)
      → expect status=PAID, paidAt non-null
  15. Issue a second ticket for same passenger (new idempotency key)
      → `POST /tickets/issue` → status=ISSUED
  16. `PUT /tickets/{ticketId}/terminate` (conductor token)
      → expect status=TERMINATED
  17. `GET /conductor/tickets/pending` → empty again
  18. Overdraft test — update wallet to ₹20 via psql:
      → issue new ticket (fare=90) → `POST /payments/wallet`
      → expect 200, balance=-70.00 (overdraft used: 20+100>=90)
  19. Overdraft reject test — update wallet to -₹80 via psql:
      → issue new ticket (fare=90) → `POST /payments/wallet`
      → expect 400: "Insufficient balance. Available: ₹20, Required: ₹90"
      (effective balance = -80 + 100 = 20 < 90)
  20. `POST /payments/wallet` same ticketId again (already PAID)
      → expect 400: "Ticket is not awaiting payment"
  - Add Ticket, Payment, Passenger folders to Postman collection
  - **Verified (2026-07-30), run in Postman Desktop, not curl:** all 20 steps
    passed. Added `Ticket` (7 requests), `Payment` (4 requests), `Passenger`
    (4 requests) folders to `postman/BusLink-API.postman_collection.json`,
    matching the existing collection's conventions (`pm.test`/
    `pm.collectionVariables` in test scripts, `{{$guid}}` pre-request scripts
    for fresh idempotency keys per ticket). Extended `Conductor Auth > Login`
    to also capture `{{busId}}` and `User > QR Token` to capture `{{qrToken}}`
    — both newly needed by `POST /tickets/issue`'s request body, neither
    previously captured since no earlier sprint's flow needed them.
  - Reused the existing `rider1@example.com` passenger (Sprint 2/3) rather
    than registering a new one — confirms the plan's "or use existing
    registered passenger" alternative works end-to-end too, not just the
    fresh-registration path.
  - The three wallet-balance changes needed at steps 4/18/19 (₹150, ₹20,
    -₹80) were applied via `psql` against the live container, confirmed with
    a `SELECT` before/after each, since no recharge endpoint exists yet
    (Sprint 5 scope).
  - Fare numbers matched exactly on the first run with no fixture guessing:
    `HSR Layout` (stage 5) → `KR Puram Railway Station` (stage 10) on the
    seeded Route 500K gives `stagesCrossed=6`, and for 2 adults + 1 child +
    1 infant, `totalFare=90.00` — the same numbers the pre-existing `Fare`
    folder already asserted, confirming `TicketServiceImpl`'s fare
    calculation (S4-12) is consistent with `FareServiceImpl`'s (Sprint 3).

### Git

- [ ] S4-22 — Commit and merge `feature/ticket-wallet-payment` into `dev`

---

## Dependencies

| Dependency | Status |
|---|---|
| Spring Boot 4.1.0 project | ✅ Done (Sprint 1) |
| All base entities incl. Ticket, Wallet, Transaction, Payment | ✅ Done (Sprint 1) |
| JWT + role-based security | ✅ Done (Sprints 2 & 3) |
| Passenger auth (register/login/profile) | ✅ Done (Sprint 2) |
| Conductor auth (login/profile) | ✅ Done (Sprint 3) |
| Route + RouteStop + fare calculation | ✅ Done (Sprint 3) |
| DataSeeder (Route 500K, Bus, Conductor) | ✅ Done (Sprint 3) |
| GlobalExceptionHandler handles `ObjectOptimisticLockingFailureException` | ✅ Done (Sprint 2) |
| `ConflictException`, `ValidationException`, `ResourceNotFoundException` | ✅ Done (Sprints 1 & 2) |
| `WalletRepository`, `UserRepository` | ✅ Done (Sprint 2) |
| `Ticket.originStop` rename, `TicketStatus.TERMINATED` | ⏳ S4-15 (this sprint — see gap note above) |
| UPI payment backend | ⏳ Sprint 5 |
| Wallet recharge + overdraft recovery | ⏳ Sprint 5 |
| Ticket expiry scheduler | ⏳ Sprint 7 |

---

## Definition of Done

- [ ] `idempotency_key` table exists in pgAdmin — only confirmed via
      `psql \d idempotency_key` (S4-01) and Postman/DB behavior, never
      visually checked in the pgAdmin UI itself. Same distinction Sprint 1
      drew between a DDL-log check and an actual pgAdmin look — leaving
      unchecked until done. Quick pgAdmin look-and-confirm, then this can
      be ticked.
- [ ] `paid_at` and `origin_stop` columns present on `ticket` table in
      pgAdmin, `source_stop` gone — same caveat: confirmed via `psql \d
      ticket` (S4-15), not the pgAdmin UI. Leaving unchecked for the same
      reason as above.
- [x] `TicketStatus` includes `TERMINATED` — enum value added (S4-15),
      DB check constraint fixed to accept it (S4-15 gap fix), and a real
      `TERMINATED` ticket created live via Postman (step 16).
- [x] `POST /tickets/issue` creates ticket with status=ISSUED,
      returns fare breakdown, stores idempotency key — verified live
      (Postman step 6: `ISSUED`, `stagesCrossed=6`, `totalFare=90.00`) and
      by `TicketServiceImplTest.issueTicket_success`.
- [x] Duplicate `POST /tickets/issue` with same `X-Idempotency-Key`
      returns same ticketId without creating a new ticket — verified live
      (Postman step 7) and by `issueTicket_idempotentRequest`.
- [ ] `GET /conductor/tickets/pending` returns only ISSUED tickets
      for that conductor, ordered by issuedAt ASC — the "only ISSUED for
      that conductor" half is verified (empty after pay/terminate,
      non-empty while `ISSUED`, live in Postman steps 8/13/17). The
      `ASC`-ordering half was never actually exercised with two or more
      simultaneous pending tickets in the same response — every check this
      sprint only ever had 0 or 1 pending ticket at a time. Leaving
      unchecked rather than assuming the derived query name guarantees
      correct behavior without having seen it.
- [x] `PUT /tickets/{ticketId}/terminate` sets status=TERMINATED,
      removes from pending list — verified live (Postman steps 16–17).
- [x] `POST /payments/wallet` with sufficient balance → PAID,
      wallet deducted, DEBIT transaction recorded — verified live (Postman
      steps 10, 12: `PAID`, `walletBalanceAfter=60.00`, 1 `DEBIT` of
      `90.00`).
- [x] Overdraft: balance=₹20, fare=₹90 → allowed, balance=-₹70 — verified
      live (Postman step 18).
- [x] Overdraft reject: balance=-₹80, fare=₹90 → 400 with clear message —
      verified live (Postman step 19).
- [ ] `GET /passenger/tickets` returns ticket history newest first — only
      ever checked with exactly 1 ticket present (Postman step 9), so the
      "newest first" ordering claim specifically was never exercised
      against 2+ tickets. Leaving unchecked for the same reason as the
      pending-tickets ordering item above.
- [x] `GET /passenger/wallet/balance` returns current balance — verified
      live at 3 different balances (Postman steps 3, 11, and the
      psql-driven checks at steps 4/18/19).
- [x] `GET /passenger/wallet/transactions` returns DEBIT transaction —
      verified live (Postman step 12: 1 `DEBIT`, `90.00`).
- [x] Paying an already-PAID ticket returns 400 — verified live (Postman
      step 20: `"Ticket is not awaiting payment"`).
- [x] All 8 `TicketServiceImplTest` tests pass — verified (S4-19).
- [x] All 7 `WalletServiceImplTest` tests pass — verified (S4-20).
- [x] All 20 Postman verification calls pass — verified (S4-21, run in
      Postman Desktop by the user, 2026-07-30).
- [ ] `feature/ticket-wallet-payment` merged into `dev`, build clean — not
      done yet, this is S4-22.
