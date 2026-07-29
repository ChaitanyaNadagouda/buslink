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

- [ ] S4-01 — Create `IdempotencyKey.java` in `entity/` — new entity:
  - `key` (VARCHAR, PK — the client-sent UUID string, not generated)
  - `ticketId` (UUID, not null — points to the ticket created for this key)
  - `createdAt` (Instant, not null)
  - `expiresAt` (Instant, not null — 24hrs after createdAt)
  - Table: `idempotency_key`
  - Does NOT extend `BaseEntity` — has its own `createdAt` and `expiresAt`
    with no `updatedAt` (idempotency keys are immutable once created)
  - Verify: app starts, `idempotency_key` table created in pgAdmin

### New Repositories

- [ ] S4-02 — `TicketRepository.java` in `repository/` — extend
  `JpaRepository<Ticket, UUID>`:
  - `List<Ticket> findByConductorIdAndStatusOrderByIssuedAtAsc(UUID conductorId, TicketStatus status)`
  - `List<Ticket> findByUserIdOrderByIssuedAtDesc(UUID userId)`
  - `Optional<Ticket> findByTicketIdAndConductorId(UUID ticketId, UUID conductorId)`
  - `Optional<Ticket> findByTicketIdAndUserId(UUID ticketId, UUID userId)`

- [ ] S4-03 — `TransactionRepository.java` in `repository/` — extend
  `JpaRepository<Transaction, UUID>`:
  - `List<Transaction> findByUserIdOrderByCreatedAtDesc(UUID userId)`

- [ ] S4-04 — `PaymentRepository.java` in `repository/` — extend
  `JpaRepository<Payment, UUID>`:
  - `Optional<Payment> findByTicketId(UUID ticketId)`  ← for UPI later

- [ ] S4-05 — `IdempotencyKeyRepository.java` in `repository/` — extend
  `JpaRepository<IdempotencyKey, String>` (String PK — the key itself):
  - `Optional<IdempotencyKey> findByKey(String key)`
  - `void deleteByExpiresAtBefore(Instant now)` ← cleanup, used by scheduler Sprint 7
  - Verify all 4 new repositories: `./mvnw compile clean`

### Configuration

- [ ] S4-06 — Add wallet overdraft config to `application.properties`:
  ```
  wallet.overdraft.limit=100.00
  ```
  - Create `WalletProperties.java` in `config/` — `@ConfigurationProperties(prefix="wallet")`
    with `BigDecimal overdraftLimit` field
  - Add `@EnableConfigurationProperties(WalletProperties.class)` to main class
    or `@ConfigurationPropertiesScan`
  - This is cleaner than `@Value` for a value that multiple services may need

- [ ] S4-07 — Add idempotency key TTL config to `application.properties`:
  ```
  ticket.idempotency.ttl-hours=24
  ```

### DTOs

- [ ] S4-08 — Request DTOs in `dto/request/` (Java records):
  - `IssueTicketRequestDTO` — qrToken (@NotBlank), busId (@NotNull),
    routeId (@NotNull), originStop (@NotBlank), destinationStop (@NotBlank),
    adults (@NotNull, @Min=1), children (@NotNull, @Min=0),
    infants (@NotNull, @Min=0)
    Note: minimum 1 adult — a ticket must have at least one paying passenger
  - `WalletPaymentRequestDTO` — ticketId (@NotNull)

- [ ] S4-09 — Response DTOs in `dto/response/` (Java records):
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

- [ ] S4-10 — Update `SecurityConfig.java` — add new endpoint rules:
  ```
  POST /tickets/issue          → ROLE_CONDUCTOR
  GET  /conductor/tickets/**   → ROLE_CONDUCTOR
  PUT  /tickets/*/terminate    → ROLE_CONDUCTOR
  POST /payments/wallet        → ROLE_PASSENGER
  GET  /passenger/**           → ROLE_PASSENGER
  ```
  - Verify: `./mvnw compile clean`, existing auth flows still work

### Ticket Entity Updates

- [ ] S4-15 — Update `Ticket.java` in `entity/`, and `TicketStatus.java` in
  `enums/` — resolves the two gaps found during plan review (see above):
  - Add `paidAt` field to `Ticket` (Instant, nullable — null until payment
    confirmed); `ddl-auto=update` adds the column automatically
  - Rename `Ticket.sourceStop` → `originStop` (matches `Route`/`RouteStop`
    naming) — `ddl-auto=update` will *add* a new `origin_stop` column rather
    than rename the existing one; manually drop the old `source_stop` column
    via `psql` afterward (`ticket` table is empty, so no backfill needed —
    same treatment as Sprint 3's `fare` → `totalFare` rename)
  - Add `TERMINATED` to `TicketStatus` enum (alongside existing
    `ISSUED, PAID, EXPIRED, CANCELLED`)
  - Verify: app starts, `paid_at` and `origin_stop` columns present on
    `ticket` in pgAdmin, `source_stop` gone, app restart shows zero-diff

### Ticket Service

- [ ] S4-11 — Create `TicketService.java` interface in `service/`

- [ ] S4-12 — Create `TicketServiceImpl.java` in `service/impl/`:

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

  - Verify: `./mvnw compile clean`

### Wallet Service

- [ ] S4-13 — Create `WalletService.java` interface in `service/`

- [ ] S4-14 — Create `WalletServiceImpl.java` in `service/impl/`:

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

  - Verify: `./mvnw compile clean`

### Controllers

- [ ] S4-16 — Create `TicketController.java` in `controller/`
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

- [ ] S4-17 — Create `PassengerController.java` in `controller/`
  (passenger-facing — ROLE_PASSENGER):
  - `GET /passenger/tickets`
    → `TicketServiceImpl.getPassengerTickets()` → `ApiResponse<List<TicketDetailResponseDTO>>`
  - `GET /passenger/tickets/{ticketId}`
    → `TicketServiceImpl.getTicketById()` → `ApiResponse<TicketDetailResponseDTO>`
  - `GET /passenger/wallet/balance`
    → `WalletServiceImpl.getWalletBalance()` → `ApiResponse<WalletBalanceResponseDTO>`
  - `GET /passenger/wallet/transactions`
    → `WalletServiceImpl.getTransactionHistory()` → `ApiResponse<List<TransactionResponseDTO>>`

- [ ] S4-18 — Create `PaymentController.java` in `controller/`
  (passenger-facing — ROLE_PASSENGER):
  - `POST /payments/wallet`
    → extract `userId` from `@AuthenticationPrincipal UserPrincipal`
    → `WalletServiceImpl.payViaWallet()` → `ApiResponse<WalletPaymentResponseDTO>`
  - Verify all 3 controllers: `./mvnw compile clean`, app starts clean

### Testing & Verification

- [ ] S4-19 — Unit tests: `TicketServiceImplTest.java` in `src/test/`:
  - Mock: `TicketRepository`, `UserRepository`, `ConductorRepository`,
    `BusRepository`, `RouteRepository`, `RouteStopRepository`,
    `IdempotencyKeyRepository`
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
  - Verify: `./mvnw test -Dtest=TicketServiceImplTest` — all pass

- [ ] S4-20 — Unit tests: `WalletServiceImplTest.java` in `src/test/`:
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
  - Verify: `./mvnw test -Dtest=WalletServiceImplTest` — all pass

- [ ] S4-21 — Postman verification sequence (in order):
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

- [ ] `idempotency_key` table exists in pgAdmin
- [ ] `paid_at` and `origin_stop` columns present on `ticket` table in pgAdmin,
      `source_stop` gone
- [ ] `TicketStatus` includes `TERMINATED`
- [ ] `POST /tickets/issue` creates ticket with status=ISSUED,
      returns fare breakdown, stores idempotency key
- [ ] Duplicate `POST /tickets/issue` with same `X-Idempotency-Key`
      returns same ticketId without creating a new ticket
- [ ] `GET /conductor/tickets/pending` returns only ISSUED tickets
      for that conductor, ordered by issuedAt ASC
- [ ] `PUT /tickets/{ticketId}/terminate` sets status=TERMINATED,
      removes from pending list
- [ ] `POST /payments/wallet` with sufficient balance → PAID,
      wallet deducted, DEBIT transaction recorded
- [ ] Overdraft: balance=₹20, fare=₹90 → allowed, balance=-₹70
- [ ] Overdraft reject: balance=-₹80, fare=₹90 → 400 with clear message
- [ ] `GET /passenger/tickets` returns ticket history newest first
- [ ] `GET /passenger/wallet/balance` returns current balance
- [ ] `GET /passenger/wallet/transactions` returns DEBIT transaction
- [ ] Paying an already-PAID ticket returns 400
- [ ] All 8 `TicketServiceImplTest` tests pass
- [ ] All 7 `WalletServiceImplTest` tests pass
- [ ] All 20 Postman verification calls pass
- [ ] `feature/ticket-wallet-payment` merged into `dev`, build clean
