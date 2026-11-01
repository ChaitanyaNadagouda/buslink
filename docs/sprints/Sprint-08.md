# Sprint 8

## Goal

Harden BusLink's security layer by closing fare evasion gaps introduced by the
offline sync model. Add phone number as a unique identity anchor, OTP verification
at registration, deviceId tracking across auth flows, and a DeviceBlacklist that
prevents serial account creation as a means to evade DISPUTED ticket dues.

---

## Background: Why This Sprint Exists

Sprint 7's offline sync engine introduced DISPUTED tickets — cases where a
passenger boarded offline but had insufficient wallet balance at sync time.
The self-pay flow (existing `POST /payments/wallet`) resolves them. But two
evasion gaps existed:

**Gap 1 — Same account, new device login:**
A passenger with DISPUTED tickets logs into a new device and attempts to board
again before paying. `GET /user/qr` had no dues check.

**Gap 2 — Serial new accounts:**
A passenger creates a new account with a different email to get a clean QR.
No phone number requirement meant unlimited accounts with zero friction.

**Defence layers this sprint adds:**
- Phone number uniqueness → same SIM can't register twice
- OTP at registration → phone must be physically possessed
- User-level dues gate on `GET /user/qr` → same account, outstanding DISPUTED
- DeviceBlacklist → same device, different account, outstanding DISPUTED
- Both checks together cover 99% of real-world evasion without conductor-side
  complexity (no whitelist caching on conductor devices)

---

## Scope

### Phase 1 — Phone Number + OTP Registration

- Add `phoneNumber` (unique, not null) to `User` entity
- Two-step registration flow:
  - `POST /auth/otp/send` — generate OTP, store in `OtpRecord`, send via `SmsProvider`
  - `POST /auth/otp/verify` — verify OTP, mark phone as verified
  - `POST /auth/register` — unchanged URL, now requires prior OTP verification
- `OtpRecord` entity — stores phoneNumber, otp, expiresAt, verified flag
- `SmsProvider` interface — `MockSmsProvider` (dev) + `Msg91SmsProvider` (prod)
- `Msg91Properties` — `@ConfigurationProperties(prefix = "msg91")`
- `OtpCleanupScheduler` — daily purge of expired OtpRecords

### Phase 2 — deviceId in Auth Flow

- `POST /auth/register` and `POST /auth/login` accept `deviceId` in request body
- `deviceId` stored in auth JWT claims (alongside existing `userId`, `role`)
- `JwtService` updated to include `deviceId` in token generation + extraction
- `deviceId` available from `SecurityContext` in downstream services

### Phase 3 — DeviceBlacklist

- `DeviceBlacklist` entity — `deviceId`, `blockedForUserId`, `outstandingAmount`,
  `syncEventId`, `createdAt`, `resolvedAt` (null = active block)
- `SyncEventProcessor` Step 9 updated — on DISPUTED outcome, write DeviceBlacklist
  entry for the passenger's deviceId
- `DeviceBlacklistRepository` — `findActiveByDeviceId(deviceId)`

### Phase 4 — QR Gate

- `GET /user/qr` updated with two pre-checks before issuing QR:
  1. User-level: any DISPUTED tickets for this userId → 402
  2. Device-level: any active DeviceBlacklist entry for this deviceId → 402
- Both return structured error: `{"error": "OUTSTANDING_DUES", "amount": 25.00,
  "message": "Pay outstanding dues to continue"}`
- Self-pay via existing `POST /payments/wallet` — when DISPUTED ticket status
  → PAID, also resolve DeviceBlacklist entry for that syncEventId

### Phase 5 — Admin Visibility

- `GET /admin/analytics/blacklisted-devices` — list active DeviceBlacklist entries
  with userId, deviceId, outstandingAmount, createdAt

---

## Entities

### User (modified)
```
phoneNumber  VARCHAR(15) UNIQUE NOT NULL   -- new field
```
Migration: `ALTER TABLE users ADD COLUMN phone_number VARCHAR(15) UNIQUE NOT NULL`
Note: existing rows — add as nullable first, backfill, then add NOT NULL + UNIQUE
constraint. In practice (dev env) drop and recreate is fine.

### OtpRecord (new)
```
id           UUID PRIMARY KEY
phoneNumber  VARCHAR(15) NOT NULL
otp          VARCHAR(6)  NOT NULL
expiresAt    TIMESTAMP   NOT NULL
verified     BOOLEAN     NOT NULL DEFAULT FALSE
createdAt    TIMESTAMP   NOT NULL
```
Index on `phoneNumber` for fast lookup.

### DeviceBlacklist (new)
```
id                UUID PRIMARY KEY
deviceId          VARCHAR(255) NOT NULL
blockedForUserId  UUID NOT NULL  FK → users
outstandingAmount DECIMAL(10,2) NOT NULL
syncEventId       UUID NOT NULL  FK → sync_events
createdAt         TIMESTAMP NOT NULL
resolvedAt        TIMESTAMP NULL   -- null = active block
```
Index on `(device_id, resolved_at)` for QR gate query.

---

## API Changes

### New Endpoints

| Method | Path | Role | Description |
|--------|------|------|-------------|
| POST | `/auth/otp/send` | PUBLIC | Send OTP to phone number |
| POST | `/auth/otp/verify` | PUBLIC | Verify OTP, mark phone verified |
| GET | `/admin/analytics/blacklisted-devices` | ADMIN | Active blacklist entries |

### Modified Endpoints

| Method | Path | Change |
|--------|------|--------|
| POST | `/auth/register` | Now requires phoneNumber + deviceId + prior OTP verification |
| POST | `/auth/login` | Now accepts deviceId in request body |
| GET | `/user/qr` | Now checks dues + device blacklist before issuing |

---

## DTOs

### OtpSendRequestDTO
```java
record OtpSendRequestDTO(
    @NotBlank String phoneNumber
) {}
```

### OtpVerifyRequestDTO
```java
record OtpVerifyRequestDTO(
    @NotBlank String phoneNumber,
    @NotBlank String otp
) {}
```

### RegisterRequestDTO (modified)
```java
record RegisterRequestDTO(
    @NotBlank String name,
    @NotBlank @Email String email,
    @NotBlank String password,
    @NotBlank String phoneNumber,   // new
    @NotBlank String deviceId       // new
) {}
```

### LoginRequestDTO (modified)
```java
record LoginRequestDTO(
    @NotBlank String email,
    @NotBlank String password,
    @NotBlank String deviceId       // new
) {}
```

### QrBlockedResponseDTO
```java
record QrBlockedResponseDTO(
    String error,           // "OUTSTANDING_DUES"
    BigDecimal amount,
    String message
) {}
```

### BlacklistedDeviceDTO
```java
record BlacklistedDeviceDTO(
    String deviceId,
    UUID blockedForUserId,
    BigDecimal outstandingAmount,
    Instant createdAt
) {}
```

---

## SmsProvider Interface

```java
public interface SmsProvider {
    void sendOtp(String phoneNumber, String otp);
}
```

### MockSmsProvider (dev profile)
```java
@Service
@Profile("dev")
public class MockSmsProvider implements SmsProvider {
    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        log.info("[MOCK SMS] OTP for {}: {}", phoneNumber, otp);
    }
}
```

### Msg91SmsProvider (prod profile)
```java
@Service
@Profile("prod")
public class Msg91SmsProvider implements SmsProvider {

    private final Msg91Properties props;
    private final RestTemplate restTemplate;

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        // POST to MSG91 OTP API
        // https://api.msg91.com/api/v5/otp
        // Body: { "template_id": ..., "mobile": phoneNumber, "otp": otp }
        // Auth: authkey header from props
    }
}
```

### Msg91Properties
```java
@ConfigurationProperties(prefix = "msg91")
public record Msg91Properties(
    String authKey,
    String templateId,
    String senderId
) {}
```

`application-prod.properties` (gitignored):
```properties
msg91.auth-key=xxxxxxxxxxxxxxxx
msg91.template-id=xxxxxxxx
msg91.sender-id=BUSLINK
```

---

## OTP Generation

```java
private static final SecureRandom SECURE_RANDOM = new SecureRandom();

private String generateOtp() {
    return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
}
```

- `SecureRandom` instantiated once as static field (expensive to seed)
- `nextInt(1_000_000)` → 0 to 999999
- `%06d` → zero-pads to always 6 digits
- Why `SecureRandom` not `Random`: `Random` is predictable given enough
  observations. `SecureRandom` uses OS entropy (`/dev/urandom`) —
  cryptographically unpredictable. For OTP granting account access,
  this is required.

---

## OtpServiceImpl

```java
@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final OtpRecordRepository otpRecordRepository;
    private final SmsProvider smsProvider;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int OTP_TTL_MINUTES = 5;

    @Transactional
    public void sendOtp(String phoneNumber) {
        // Invalidate any existing unverified OTP for this phone
        otpRecordRepository.deleteByPhoneNumberAndVerifiedFalse(phoneNumber);

        String otp = generateOtp();
        OtpRecord record = new OtpRecord();
        record.setPhoneNumber(phoneNumber);
        record.setOtp(otp);
        record.setExpiresAt(Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        record.setVerified(false);
        otpRecordRepository.save(record);

        smsProvider.sendOtp(phoneNumber, otp);
    }

    @Transactional
    public void verifyOtp(String phoneNumber, String otp) {
        OtpRecord record = otpRecordRepository
            .findByPhoneNumberAndVerifiedFalseAndExpiresAtAfter(phoneNumber, Instant.now())
            .orElseThrow(() -> new OtpExpiredException("OTP expired or not found"));

        if (!record.getOtp().equals(otp)) {
            throw new OtpInvalidException("Invalid OTP");
        }

        record.setVerified(true);
        otpRecordRepository.save(record);
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
```

Key design decisions:
- On resend: delete existing unverified OTP before creating new one → only one
  valid OTP per phone at any time
- `verifyOtp` checks: not verified + not expired + otp matches
- After verify, `verified=true` — registration checks this flag

---

## AuthServiceImpl Changes

### Register flow
```java
public void register(RegisterRequestDTO dto) {
    // 1. Check phone OTP verified
    OtpRecord otpRecord = otpRecordRepository
        .findByPhoneNumberAndVerifiedTrueAndExpiresAtAfter(dto.phoneNumber(), Instant.now())
        .orElseThrow(() -> new PhoneNotVerifiedException("Phone number not verified"));

    // 2. Check phone uniqueness (DB constraint will also catch it)
    if (userRepository.existsByPhoneNumber(dto.phoneNumber())) {
        throw new PhoneAlreadyRegisteredException("Phone number already registered");
    }

    // 3. Create user with phoneNumber
    User user = new User();
    user.setPhoneNumber(dto.phoneNumber());
    // ... rest of existing registration logic

    // 4. Delete used OtpRecord
    otpRecordRepository.delete(otpRecord);
}
```

### Login flow
```java
public AuthResponseDTO login(LoginRequestDTO dto) {
    // existing auth logic...

    // deviceId now included in JWT claims
    String token = jwtService.generateToken(user, dto.deviceId());
    return new AuthResponseDTO(token);
}
```

---

## JwtService Changes

Token generation now includes `deviceId`:
```java
public String generateToken(User user, String deviceId) {
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("role", user.getRole())
        .claim("deviceId", deviceId)      // new
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
        .signWith(privateKey)
        .compact();
}

public String extractDeviceId(String token) {
    return extractClaim(token, claims -> claims.get("deviceId", String.class));
}
```

---

## QR Gate Logic

`UserQrServiceImpl.getQr(userId, deviceId)`:

```java
// Check 1: user-level outstanding dues
boolean hasDisputedTickets = ticketRepository
    .existsByUserIdAndStatus(userId, TicketStatus.DISPUTED);
if (hasDisputedTickets) {
    BigDecimal totalDues = ticketRepository.sumDisputedFareByUserId(userId);
    throw new OutstandingDuesException(totalDues);
}

// Check 2: device-level blacklist
Optional<DeviceBlacklist> blacklistEntry = deviceBlacklistRepository
    .findActiveByDeviceId(deviceId);
if (blacklistEntry.isPresent()) {
    throw new OutstandingDuesException(blacklistEntry.get().getOutstandingAmount());
}

// Both clear → generate and return QR as before
```

`OutstandingDuesException` → handled by `@ControllerAdvice` → 402 Payment Required
with `QrBlockedResponseDTO`.

---

## SyncEventProcessor Changes (Step 9)

When wallet resolution results in DISPUTED:
```java
// Existing: mark ticket DISPUTED, mark sync event DISPUTED
// New addition:
DeviceBlacklist blacklist = new DeviceBlacklist();
blacklist.setDeviceId(event.getDeviceId());   // extracted from passengerQrToken
blacklist.setBlockedForUserId(event.getUserId());
blacklist.setOutstandingAmount(fare);
blacklist.setSyncEventId(event.getId());
deviceBlacklistRepository.save(blacklist);
```

`deviceId` extraction: already in passenger QR JWT claims —
`JwtUtil.extractDeviceId(passengerQrToken)`

---

## WalletServiceImpl Changes (Self-Pay)

When `POST /payments/wallet` pays a DISPUTED ticket → ticket status → PAID:
```java
// Existing: deduct wallet, update ticket status to PAID
// New addition:
deviceBlacklistRepository
    .findBySyncEventId(ticket.getSyncEventId())
    .ifPresent(entry -> {
        entry.setResolvedAt(Instant.now());
        deviceBlacklistRepository.save(entry);
    });
```

Clears the device blacklist entry atomically with the payment transaction.

---

## OtpCleanupScheduler

```java
@Component
@RequiredArgsConstructor
public class OtpCleanupScheduler {

    private final OtpRecordRepository otpRecordRepository;

    @Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
    public void cleanupExpiredOtps() {
        int deleted = otpRecordRepository.deleteByExpiresAtBefore(Instant.now());
        log.info("OTP cleanup: deleted {} expired records", deleted);
    }
}
```

Same pattern as `IdempotencyKeyCleanupScheduler` from Sprint 7.

---

## New Exceptions

- `OtpExpiredException` → 400 Bad Request
- `OtpInvalidException` → 400 Bad Request
- `PhoneNotVerifiedException` → 400 Bad Request
- `PhoneAlreadyRegisteredException` → 409 Conflict
- `OutstandingDuesException` → 402 Payment Required

All wired into existing `GlobalExceptionHandler` (`@ControllerAdvice`).

---

## Tasks

### S8-01 — Add phoneNumber to User entity and migration
- [ ] Add `phoneNumber VARCHAR(15) UNIQUE NOT NULL` to `User`
- [ ] Write migration SQL (add nullable → backfill → add constraint)
- [ ] Update `RegisterRequestDTO` with `phoneNumber` + `deviceId` fields
- [ ] `userRepository.existsByPhoneNumber()`

### S8-02 — OtpRecord entity + repository
- [ ] `OtpRecord` entity with all fields
- [ ] `OtpRecordRepository`:
  - `findByPhoneNumberAndVerifiedFalseAndExpiresAtAfter`
  - `findByPhoneNumberAndVerifiedTrueAndExpiresAtAfter`
  - `deleteByPhoneNumberAndVerifiedFalse`
  - `deleteByExpiresAtBefore`

### S8-03 — SmsProvider interface + MockSmsProvider
- [ ] `SmsProvider` interface
- [ ] `MockSmsProvider` with `@Profile("dev")` logging OTP to console
- [ ] Wire into `OtpServiceImpl`

### S8-04 — Msg91SmsProvider
- [ ] `Msg91Properties` with `@ConfigurationProperties(prefix = "msg91")`
- [ ] `Msg91SmsProvider` with `@Profile("prod")` calling MSG91 OTP API
- [ ] `application-prod.properties` template (gitignored values)
- [ ] Add `msg91.auth-key`, `msg91.template-id`, `msg91.sender-id` to `.gitignore`

### S8-05 — OtpServiceImpl
- [ ] `generateOtp()` using `SecureRandom`
- [ ] `sendOtp()`: delete existing unverified → generate → save → send
- [ ] `verifyOtp()`: find active unverified → compare → mark verified
- [ ] Unit tests: `sendOtp_success`, `sendOtp_replacesExistingOtp`,
      `verifyOtp_success`, `verifyOtp_expired`, `verifyOtp_wrongOtp`

### S8-06 — POST /auth/otp/send
- [ ] `OtpController` (new) with `POST /auth/otp/send`
- [ ] Request: `OtpSendRequestDTO { phoneNumber }`
- [ ] Response: 200 `{ "message": "OTP sent" }`
- [ ] Integration test: `sendOtp_success_200`

### S8-07 — POST /auth/otp/verify
- [ ] `POST /auth/otp/verify` in `OtpController`
- [ ] Request: `OtpVerifyRequestDTO { phoneNumber, otp }`
- [ ] Response: 200 `{ "message": "Phone verified" }`
- [ ] Integration tests: `verifyOtp_success_200`, `verifyOtp_expired_400`,
      `verifyOtp_wrong_400`

### S8-08 — Update POST /auth/register
- [ ] Add `phoneNumber` + `deviceId` to `RegisterRequestDTO`
- [ ] Check OTP verified before proceeding
- [ ] Check phone uniqueness
- [ ] Delete used `OtpRecord` after successful registration
- [ ] Integration tests: `register_success`, `register_phoneNotVerified_400`,
      `register_phoneDuplicate_409`

### S8-09 — Add deviceId to POST /auth/login + JWT
- [ ] Add `deviceId` to `LoginRequestDTO`
- [ ] `JwtService.generateToken(user, deviceId)` — add `deviceId` claim
- [ ] `JwtService.extractDeviceId(token)`
- [ ] Update `JwtAuthFilter` to extract and store `deviceId` in `SecurityContext`
- [ ] Integration test: `login_success_jwtContainsDeviceId`

### S8-10 — DeviceBlacklist entity + repository
- [ ] `DeviceBlacklist` entity with all fields
- [ ] `DeviceBlacklistRepository`:
  - `findActiveByDeviceId(deviceId)` — where `resolvedAt IS NULL`
  - `findBySyncEventId(syncEventId)`
- [ ] Index on `(device_id, resolved_at)`

### S8-11 — Write DeviceBlacklist on DISPUTED sync outcome
- [ ] Update `SyncEventProcessor` Step 9
- [ ] Extract `deviceId` from `passengerQrToken` via `JwtUtil.extractDeviceId()`
- [ ] Save `DeviceBlacklist` entry when outcome = DISPUTED
- [ ] Unit tests: `processEvent_disputed_writesBlacklist`,
      `processEvent_synced_noBlacklist`

### S8-12 — QR gate: dues check + device blacklist check
- [ ] Update `GET /user/qr` handler
- [ ] Inject `ticketRepository` + `deviceBlacklistRepository`
- [ ] Check 1: `existsByUserIdAndStatus(userId, DISPUTED)` → 402
- [ ] Check 2: `findActiveByDeviceId(deviceId)` → 402
- [ ] `OutstandingDuesException` → `GlobalExceptionHandler` → 402 with
      `QrBlockedResponseDTO`
- [ ] Integration tests:
      `getQr_noDisputed_success`,
      `getQr_userHasDisputed_402`,
      `getQr_deviceBlacklisted_402`,
      `getQr_deviceBlacklistedDifferentUser_402`

### S8-13 — Clear DeviceBlacklist on self-pay
- [ ] Update `WalletServiceImpl.payViaWallet()`
- [ ] After ticket → PAID, resolve `DeviceBlacklist` entry for `syncEventId`
- [ ] Unit tests: `payViaWallet_disputed_clearsBlacklist`,
      `payViaWallet_disputed_qrUnblocksAfterPayment`

### S8-14 — OtpCleanupScheduler
- [ ] `OtpCleanupScheduler` — `@Scheduled(cron = "0 0 2 * * *")`
- [ ] `deleteByExpiresAtBefore(Instant.now())`
- [ ] Unit test: `cleanup_deletesExpiredOtps`

### S8-15 — GET /admin/analytics/blacklisted-devices
- [ ] Query active `DeviceBlacklist` entries
- [ ] Response: list of `BlacklistedDeviceDTO`
- [ ] Wire into `AnalyticsController`
- [ ] Integration test: `getBlacklistedDevices_success`

### S8-16 — Postman collection
- [ ] `POST /auth/otp/send` — happy path
- [ ] `POST /auth/otp/verify` — happy path
- [ ] `POST /auth/register` — with phoneNumber + deviceId
- [ ] `POST /auth/login` — with deviceId
- [ ] `GET /user/qr` — clean user (success)
- [ ] `GET /user/qr` — user with DISPUTED ticket (402)
- [ ] `GET /user/qr` — device blacklisted different account (402)
- [ ] `POST /payments/wallet` — pay DISPUTED → QR unblocked (verify with GET /user/qr)
- [ ] `GET /admin/analytics/blacklisted-devices`

---

## Design Decisions

### Why phone number uniqueness closes the primary gap
One SIM = one BusLink account. A passenger creating a second account to evade
dues must use a different SIM — a meaningful real-world barrier. Most fare evaders
are opportunistic, not determined attackers with multiple SIMs.

### Why DeviceBlacklist over DeviceWhitelist
A whitelist of trusted devices requires caching millions of deviceIds on every
conductor device and keeping them in sync — impractical at city scale. A blacklist
is server-side only, written only on DISPUTED outcomes (rare), checked only at QR
generation (online). Zero conductor-side complexity.

### Why deviceId stays in passenger QR JWT (unchanged from Sprint 7)
deviceId in the QR is an audit field — tells the server which device was used at
boarding time. It feeds the DeviceBlacklist on DISPUTED. It is NOT an enforcement
field at scan time (conductor offline, can't check blacklist). This is intentional
and honest about the offline constraint.

### Why OTP is registration-only, not login
OTP at every login would frustrate legitimate multi-device users. The security
value of OTP is establishing phone ownership once at account creation — the unique
phone constraint then prevents duplicate accounts. Login OTP adds friction with
no additional security benefit given the phone uniqueness guarantee.

### SmsProvider interface pattern
`SmsProvider` interface with `@Profile`-based implementations means zero business
logic changes to swap SMS providers. `MockSmsProvider` (dev) logs to console —
adequate for test coverage. `Msg91SmsProvider` (prod) calls MSG91 REST API —
Indian provider, appropriate for BMTC Bangalore context. Credentials in gitignored
`application-prod.properties`, loaded via `Msg91Properties`
`@ConfigurationProperties` — same pattern as Sprint 7's `SyncProperties`.

### 402 Payment Required for QR block
HTTP 402 is semantically correct for "payment required to proceed." Returns
structured `QrBlockedResponseDTO` with `amount` field so the passenger app can
render a specific "Pay ₹XX to unlock" screen without parsing the message string.

### SecureRandom for OTP generation
`SecureRandom` uses OS-level entropy (`/dev/urandom` on Linux) — cryptographically
unpredictable. `Random` is predictable given enough observations and unsuitable
for security-sensitive token generation. Static field instantiation avoids the
expensive re-seeding cost on every call.

---

## Test Count Target

| Category | Tests |
|---|---|
| OtpServiceImpl unit | 5 |
| OtpController integration | 3 |
| AuthService register integration | 3 |
| AuthService login integration | 1 |
| SyncEventProcessor unit (blacklist) | 2 |
| QR gate integration | 4 |
| WalletService (blacklist clear) | 2 |
| OtpCleanupScheduler unit | 1 |
| Admin blacklisted devices | 1 |
| **Total new** | **22** |

Prior total: 106. Sprint 8 target: **128 tests**.

---

## Plan Review Against the Code (2026-11-01)

Checked before starting, per `CLAUDE.md` §4. Each item is settled as a
deviation below when its step comes up.

1. `User.mobileNo` already exists (UNIQUE, NOT NULL, Indian-mobile `@Pattern`
   on `UserSignUpRequestDTO`) — S8-01's column + migration are unnecessary.
2. `User.deviceId` exists and is copied into the passenger QR, but nothing ever
   sets it, so the QR's `deviceId` claim is always `null`. S8-11 as written
   would insert `null` into a NOT NULL column on every DISPUTED event.
3. `Ticket` has no `syncEventId`; the link is `SyncEvent.resultTicketId`
   (S8-13's lookup must change).
4. The app has no Spring profiles, so a `@Profile("dev")`-only `SmsProvider`
   leaves no bean and startup fails.
5. `/auth/refresh` mints access tokens without `deviceId`.
6. `POST /user/qr/refresh` also issues a QR — the gate must cover it too.
7. The suite has no integration tests (Mockito unit tests + `contextLoads`).
8. Naming: `JwtUtil` (not `JwtService`), `JwtAuthenticationFilter`,
   `UserSignUpRequestDTO` / `UserLoginRequestDTO`, `UserServiceImpl`; tokens are
   HMAC-signed, not `privateKey`.
9. Errors use the `ApiResponse` envelope; the planned 402 body is a different
   shape.
10. `.gitignore` lists files, not keys — ignore `application-prod.properties`.
11. OTP gaps to discuss: no limit on wrong `verify` attempts; no rate limit on
    `send`.

---

## Deviations from the Plan (discussed, approved)

### D1 — Keep `mobileNo`; S8-01's column + migration skipped (2026-11-01)
- **Chosen:** keep the existing `User.mobileNo` (UNIQUE, NOT NULL, validated
  by `@Pattern("^[6-9]\\d{9}$")` at signup) as the phone identity anchor. The
  plan's "one SIM = one account" rule is already enforced by the DB.
- **Rejected:** renaming to `phoneNumber` everywhere — a schema change plus a
  breaking API field rename, with zero extra fraud protection.
- **Effect on the plan:** wherever the plan says `phoneNumber`, read
  `mobileNo` (entity, `OtpRecord`, DTOs). No DB migration for S8-01.

### D2 — Duplicate-mobile pre-check in `register` (2026-11-01)
- **Problem:** a duplicate `mobileNo` was already blocked by the UNIQUE
  constraint (→ `DataIntegrityViolationException` → 409), but with a generic
  message, unlike the clear "Email already registered".
- **Chosen:** `UserRepository.existsByMobileNo()` checked in
  `AuthServiceImpl.register` after the email check → `ConflictException`
  "Mobile number already registered" (409).
- **Known limitation:** two simultaneous signups with the same number can both
  pass the pre-check; the DB constraint still rejects the second (generic 409).
  The pre-check is for the message, the constraint is the real guard.
- **Tests:** `AuthServiceImplTest.register_duplicateMobileNo` (throws, nothing
  saved). `AuthServiceImplTest` 6/6.

### D3 — S8-04 (MSG91) moved to the end of the sprint (2026-11-01)
- **Chosen:** implement S8-04 after S8-15, just before S8-16 (Postman).
- **Why:** nothing in the sprint depends on it — OTP flows and tests run on
  `MockSmsProvider`; MSG91 needs account keys + a DLT-approved template to
  test for real. Fraud defences first, production plumbing last.
- **Cost:** the real SMS path is untested until late in the sprint.

### D4 — OTPs stored in Redis with a TTL, not a Postgres table; S8-14 dropped (2026-11-01)
- **Chosen:** each OTP is a Redis key (e.g. `otp:<mobileNo>`) with a 5-minute
  TTL; Redis deletes it automatically on expiry.
- **Rejected:** the planned `OtpRecord` table + `OtpCleanupScheduler` — works,
  but needs a manual `expiresAt` check and a nightly purge for data that only
  lives 5 minutes.
- **Deciding fact (checked in code):** there is no `CacheErrorHandler`, so a
  Redis outage already fails `@Cacheable` fare/route-stop lookups (ticket issue,
  sync). Redis is already a hard production dependency; OTP adds no new one.
  (Corrected an earlier claim that a Redis outage "only makes things slower".)
- **Effect on the plan:** S8-02 becomes a Redis-backed OTP store instead of an
  entity + repository; **S8-14 (`OtpCleanupScheduler`) is dropped** — its test
  comes out of the 22-test target.

---

## Carry Forward (not in Sprint 8 scope)

- **OTP purpose for forgot password.** D8 refuses `send` for registered
  numbers; password reset needs the opposite. When forgot-password is built,
  OTPs need a purpose (`SIGNUP` / `PASSWORD_RESET`) with separate Redis keys
  (`otp:code:SIGNUP:<mobile>` …) so a signup code can never reset a password.

- **Cache failure fallback.** With no `CacheErrorHandler`, a Redis outage turns
  every `@Cacheable` call into an exception (fares → ticket issue + sync fail).
  Fix: a `CacheErrorHandler` that logs and treats Redis errors as cache misses,
  so the method runs and reads Postgres. Found 2026-11-01; schedule in a later
  sprint (Docker/deployment hardening is a natural fit).

### D5 — OTP is submitted inside `POST /auth/register`; S8-07 (`/auth/otp/verify`) dropped (2026-11-01)
- **Problem:** with separate verify and register calls, the server (stateless
  HTTP) needs something to carry "verified" from one request to the next;
  otherwise `register` can be called directly, skipping the OTP.
- **Chosen:** `UserSignUpRequestDTO` gains `otp`; `register` compares it with
  the Redis key `otp:<mobileNo>` and creates the account in the same request,
  then deletes the key. Proof and account creation happen together, so nothing
  needs carrying and nobody can register a number they don't hold.
- **Rejected:** (A) a Redis "verified" flag keyed by mobile — leaves a small
  hole: anyone who knows the number can register on it in the window after
  the owner verifies; (B) a signed verification token from `/verify` — closes
  that hole but adds a new token type for what C does with less code.
- **Trap handled in code:** if `register` fails for another reason (e.g. email
  taken), the OTP key is **not** deleted, so the user can fix the form and
  resubmit without a new SMS.
- **Effect on the plan:** S8-05's `verifyOtp` becomes a check used by
  `register`; S8-07 and its 3 integration tests are dropped; the planned
  `OtpVerifyRequestDTO` is not needed.

### D6 — Max 5 wrong OTP attempts, then the OTP is deleted (2026-11-01)
- **Problem:** a 6-digit OTP has 1,000,000 values; with unlimited tries a
  script at ~1,000/s covers ~30% of them inside the 5-minute TTL, and resends
  give it more rounds.
- **Chosen:** a Redis attempt counter alongside the OTP (same TTL). Each wrong
  `otp` in `register` increments it; the 5th wrong attempt deletes the OTP and
  the user must request a new one. Odds per SMS: 5 / 1,000,000.
- **Rejected:** (A) no limit (plan) — brute-forceable; (C) also locking the
  number after repeated failures — lets anyone lock a real user out by typing
  wrong codes for their number.
- **Related:** repeated resends (5 fresh guesses each) are handled by the send
  limit (next decision), not a lockout.

### D7 — OTP send limits: 60 s cooldown + max 5 sends per number per hour (2026-11-01)
- **Problem:** every send costs an SMS (~₹0.15–0.25). Unlimited sends allow
  SMS pumping (bill), harassment of one number, and unlimited fresh guess
  rounds (5 per OTP, D6).
- **Chosen:** Redis `otp-cooldown:<mobileNo>` (TTL 60 s) + counter
  `otp-sends:<mobileNo>` (TTL 1 h, max 5). Over either limit → **429 Too Many
  Requests**. Worst case per number: 120 SMS/day, 600 guesses/day (0.06%).
- **Rejected:** (A) no limit (plan); (B) cooldown only — still 1,440 SMS and
  7,200 guesses per number per day.
- **Known limitation:** limits are per number. An attacker spraying one SMS
  each to many different numbers is not stopped; that needs per-IP limits or
  a CAPTCHA (proxies, shared mobile-carrier IPs) — out of scope.
- **Learning note:** `docs/OTP_VERIFICATION.md`.

### D8 — `otp/send` refuses already-registered numbers with 409, no SMS (2026-11-01)
- **Chosen:** `sendOtp` checks `existsByMobileNo` first → 409 "Mobile number
  already registered"; no SMS, no rate-limit counters touched.
- **Rejected:** always sending (to hide whether a number has an account) — it
  wastes an SMS, sends the real owner an unrequested OTP, and hides nothing:
  `register` already reveals "Mobile number already registered" (D2).
- **Known limitation:** `send` is an account-enumeration oracle for mobile
  numbers — accepted, since signup already exposes the same fact; rate limits
  (D7) slow bulk probing per number.

### D9 — Spring profiles (`dev` / `prod`) select the SMS provider (2026-11-01)
- **Chosen:** `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}` in
  `application.properties`; new `application-dev.properties` and
  `application-prod.properties` (non-secret settings only — secrets stay env
  vars, like Razorpay). `MockSmsProvider` is `@Profile("dev")`,
  `Msg91SmsProvider` (S8-04, end of sprint) is `@Profile("prod")`.
- **Why:** the plan assumed profiles that didn't exist (no `SmsProvider` bean →
  startup failure). Effort is about the same as a per-feature switch, and it
  sets up the dev/prod split the deployment sprint will need (SQL logging,
  `ddl-auto`, etc.).
- **Rejected:** `sms.provider=mock|msg91` via `@ConditionalOnProperty` — equally
  small and more precise per feature, but means one switch per integration.
- **Deviation from plan text:** no gitignored `application-prod.properties`
  holding MSG91 keys; keys come from env vars (`MSG91_AUTH_KEY` …).
- **Known limitation:** if prod forgets `SPRING_PROFILES_ACTIVE=prod` it runs
  the mock (OTPs logged, no SMS) — `MockSmsProvider` logs a loud startup
  warning.

### Step 2 implemented — OTP store, mock SMS, OTP service (S8-02/03/05, 2026-11-01)
- `OtpProperties` (`otp.ttl-minutes=5`, `max-attempts=5`,
  `resend-cooldown-seconds=60`, `max-sends-per-hour=5`), `SmsProvider` port in
  `gateway/`, `MockSmsProvider` (`@Profile("dev")`, startup WARN),
  `TooManyRequestsException` → 429, `OtpService` / `OtpServiceImpl`
  (`StringRedisTemplate`). Redis keys: `otp:code:`, `otp:attempts:`,
  `otp:cooldown:`, `otp:sends:` + mobileNo.
- **Simplifications vs plan text:** expired/wrong OTP reuse the existing
  `ValidationException` (400) instead of new `OtpExpiredException` /
  `OtpInvalidException`; OTP stored as plain text in Redis (5-minute life,
  Redis access already implies full cache compromise — hashing stops nothing
  realistic).
- **Known limitations:** `INCR` then `EXPIRE` are two commands — a crash
  between them leaves a counter with no TTL (number stuck over the cap until
  the key is deleted); a Lua script / `SET … EX` pattern would close it,
  judged not worth it at this scale. The cooldown is consumed even when the
  hourly cap rejects the send, and if the SMS call throws.
- **Tests:** `OtpServiceImplTest` 8 (send success / registered 409 / cooldown
  429 / hourly cap 429; verify correct / expired / wrong counts attempt / 5th
  wrong deletes), `OtpPropertiesTest` 1. **What mocks can't prove:** real Redis
  TTL expiry and `SET NX` atomicity — verified later in Postman + `redis-cli`.
  `contextLoads` passes against live Docker (profile `dev`, mock WARN logged).

### D10 — New `OtpController` for `POST /auth/otp/send` (2026-11-01)
- **Chosen:** a dedicated `OtpController` (`@RequestMapping("/auth/otp")`), as
  the plan says.
- **Why:** forgot-password via OTP is planned later — OTP endpoints get one
  home instead of growing `AuthController`.
- **Rejected:** adding the method to `AuthController` (only one OTP endpoint
  today after D5).
- **Also:** `/auth/otp/send` added to the `permitAll` list in `SecurityConfig`
  (without it the public endpoint returns 401).

### Step 3 implemented — `POST /auth/otp/send` (S8-06, 2026-11-01)
- `OtpSendRequestDTO` (same `@Pattern` as signup), `OtpController`,
  `SecurityConfig` permit. Returns `ApiResponse` "OTP sent" (200).
- **Live smoke test** (app on `dev`, real Redis), `7000000001`: send → 200 and
  `[MOCK SMS]` logged; immediate resend → 429; Redis `otp:code` TTL 300 s,
  `otp:cooldown` 60 s, `otp:sends` = 1 TTL 3600 s; invalid number → 400;
  registered number → 409. Test keys deleted afterwards.
- **Tests:** no controller test — the suite has no MVC/integration test setup
  (plan review item 7); behaviour proven by the smoke test above and the
  Postman run (S8-16). API.md updated.

### D11 — OTP deleted inside `register`, not after commit (2026-11-01)
- **Problem:** Redis isn't part of the Postgres transaction. Deleting the OTP
  inside `register` happens before the commit; if the commit then fails, the
  user isn't created but the OTP is gone.
- **Chosen:** delete inside `register` (`otpService.clearOtp` after the wallet
  save). Bad case = one extra SMS after a rare DB failure.
- **Rejected:** `TransactionSynchronization.afterCommit` — survives a failed
  commit, but only prevents that one resend. Kept as an interview point.

### Step 4 implemented — register requires the OTP (S8-08, 2026-11-01)
- `UserSignUpRequestDTO` gains `otp` (`@NotBlank`, `^\d{6}$`).
  `AuthServiceImpl.register` order: email → mobile → `verifyOtp` → save user +
  wallet → `clearOtp`.
- **Tests:** `AuthServiceImplTest` 7 — `register_success` (InOrder: verify →
  saves → clear), duplicate email/mobile (`verifyNoInteractions(otpService)`:
  OTP untouched), `register_wrongOtp_400AndNothingSaved`.
- **Live smoke test** (`7000000002`): missing `otp` → 400; wrong → 400,
  `otp:attempts`=1; correct → 200 + tokens, `otp:code` deleted; same OTP again
  with a new email → 409. Leaves dev user `otp-smoke-7000000002@buslink.com`.
- **Breaking change:** existing Postman register requests now need `otp` —
  fixed in S8-16. API.md updated.

---

## Progress & Where We Left Off (updated 2026-11-01)

**Done:** Steps 1–4 (Phase 1: phone + OTP) — S8-01 (D1, D2), S8-02/03/05
(D4, D6–D9), S8-06 (D10), S8-08 (D5, D11). S8-07 and S8-14 dropped. Committed
on `feature/security-hardening`. Tests so far: 106 → **117**, full suite green (`AuthServiceImplTest`
+2, `OtpServiceImplTest` 8, `OtpPropertiesTest` 1).

**Step order (agreed, D3):**
```
 1. Phone number (S8-01)                ✅   7. Write blacklist on DISPUTED (S8-11)
 2. OTP store + mock SMS + service      ✅   8. QR gate, both QR endpoints (S8-12)
 3. POST /auth/otp/send (S8-06)         ✅   9. Clear blacklist on self-pay (S8-13)
 4. Register needs OTP (S8-08)          ✅  10. (S8-14 dropped, D4)
 5. deviceId (S8-09)                ◀ NEXT  11. Admin blacklisted-devices (S8-15)
 6. DeviceBlacklist entity (S8-10)          12. MSG91 provider (S8-04, D3)
                                            13. Postman + full suite (S8-16)
```

**Open question — Q12 (Step 5): where does the backend keep `deviceId`?**
Context: `deviceId` is an app-generated tag sent at register/login — client-
supplied, so a modified app can fake it; the device blacklist is a speed bump,
the unique SIM (D1) is the strong barrier (plan's "99%" claim is optimistic —
record as a limitation). Bug found: `User.deviceId` is never set, so the
passenger QR always carries `deviceId: null`.

| | A: JWT claim only (plan) | B: `User.deviceId` = last register/login device | C: both |
|---|---|---|---|
| Fixes null QR claim | ❌ not alone | ✅ | ✅ |
| JWT / filter / refresh changes | yes | none | yes |
| Gate checks | calling device | last-login device | calling device |
| Spoof-proof | no | no | no |

Recommendation: **B** — fixes the null-QR bug with least code; A/C add work
but no protection (the tag is client-supplied either way). Pending scenario to
reason through: `e7` owes dues on phone D1, creates `e8`, logs in on D1 — does
B block `e8`?

**Other open items:**
- Existing Postman register requests need the new `otp` field (S8-16).
- Dev DB has smoke-test user `otp-smoke-7000000002@buslink.com`.
- Later in the sprint: the 402 response shape vs the `ApiResponse` envelope
  (plan review item 9); controller/integration tests vs unit tests (item 7).
