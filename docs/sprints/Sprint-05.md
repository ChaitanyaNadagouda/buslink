# Sprint 5

## Goal

Integrate Razorpay (test mode) with ngrok for real webhook delivery. Implement
wallet recharge flow (Razorpay order → webhook confirm → credit wallet + overdraft
recovery) and UPI ticket payment flow (Razorpay order → webhook confirm → ticket
PAID). By end of sprint a passenger can recharge their wallet and pay for a ticket
via Razorpay's checkout (which surfaces PhonePe, GPay, UPI, Card options) with
real webhook confirmation hitting the backend through ngrok.

---

## Scope

- One-time setup: Razorpay test account, ngrok, webhook registration
- `RazorpayProperties` — `@ConfigurationProperties` for key-id, key-secret, webhook-secret
- Razorpay SDK dependency in `pom.xml`
- **Payment gateway abstraction** — `PaymentGatewayPort` interface (`createOrder`,
  `verifyWebhookSignature`, `parseWebhookEvent`, `getPublicKeyId`) +
  `RazorpayGatewayAdapter` implementation, so `PaymentServiceImpl`/
  `WebhookServiceImpl` depend only on our own domain-shaped contract, never on
  `RazorpayClient`/`RazorpayProperties` directly — see "Mid-sprint design
  decision" below
- Wallet recharge flow:
  - `POST /payments/recharge/initiate` (ROLE_PASSENGER) → gateway order → Payment (PENDING)
  - `POST /webhooks/razorpay` → signature verify → credit wallet → overdraft recovery → Payment (SUCCESS)
- Overdraft recovery on recharge — 2 transaction records (explicit DEBIT recovery + CREDIT recharge)
- UPI ticket payment flow:
  - `POST /payments/ticket/upi/initiate` (ROLE_PASSENGER) → gateway order → Payment (PENDING)
  - Same `/webhooks/razorpay` endpoint handles both recharge + ticket payment (differentiated by `PaymentPurpose`)
  - On ticket payment success → ticket → PAID (no wallet deduction)
- `WebhookController` — public endpoint, no JWT, verified via the gateway port's signature check
- Unit tests — `PaymentServiceImplTest`, `WebhookServiceImplTest`, `RazorpayGatewayAdapterTest`
- End-to-end Postman + ngrok + Razorpay test mode verification

**Out of scope:** Admin auth (Sprint 6), Admin CRUD (Sprint 6),
Analytics (Sprint 6), Redis caching (Sprint 6), Flyway (deferred),
WebSocket (Sprint 7). Full gateway-agnosticism at the API/frontend layer
(e.g. abstracting the checkout widget itself) is also out of scope — the
`razorpayKeyId` field in the initiate-payment response DTOs stays
Razorpay-specific, since the frontend integration is inherently tied to
whichever gateway's checkout UI is in use. Only the *backend's* internal
dependency on the Razorpay SDK is being abstracted.

---

## Pre-existing gaps found during plan review (2026-08-03)

Checked this plan against the actual current `Payment`/`Transaction` entity state
before starting — `Payment` and its `PaymentMode`/`PaymentPurpose`/`PaymentStatus`
enums match the plan exactly (unlike Sprint 4, where a similar check caught a real
mismatch). One real gap found and resolved:

- **`Transaction.referenceId` is `@Column(nullable = false)`, but the plan's
  `processRecharge` overdraft-recovery step builds a `Transaction` with
  `referenceId(null)`** (the plan's own code comment even hedges `// or a special
  marker`, suggesting this was left deliberately open). As written, saving that
  recovery `Transaction` would fail at the DB layer with a NOT NULL violation the
  first time overdraft recovery actually ran — not caught by `@Valid` or compile
  checks, only surfaces against a real database.
  **Why the plan wanted `null` in the first place:** `referenceId` normally means
  "the specific thing this transaction is about" (a `ticketId` for a ticket
  payment, a `paymentId` for a recharge). But overdraft recovery doesn't
  correspond to one new event — it's the ledger acknowledging that this recharge
  is settling a negative balance that accumulated from earlier overdraft-allowed
  ticket payments (which already got their own DEBIT records when they happened).
  There's no single natural entity this recovery row is "about."
  **Decision (confirmed 2026-08-03):** reuse `payment.getPaymentId()` as the
  `referenceId` for **both** the DEBIT recovery and the CREDIT recharge
  `Transaction`. Reframes `referenceId` slightly — not "what this money is about"
  but "what event caused this ledger entry to be written" — which is genuinely
  the same recharge `Payment` for both rows. No schema change needed. (Alternatives
  considered: making the column nullable — bigger change, and `ddl-auto=update`
  wouldn't drop the existing `NOT NULL` constraint either, same class of gap as
  Sprint 4's `ticket_status_check` issue; or a sentinel/marker UUID — rejected as
  a footgun for anyone querying the table later without knowing the convention.)
  The `processRecharge` pseudocode below is updated to reflect this.

---

## Mid-sprint design decision: Payment Gateway Port/Adapter (2026-08-04)

Raised by the user before S5-05: since this sprint integrates a payment gateway
for the first time, should the integration be abstracted behind an interface
now, so a future second gateway wouldn't require rewriting business logic?

**Decision: yes.** `PaymentGatewayPort` (interface) + `RazorpayGatewayAdapter`
(implementation) added to the plan, in a new `gateway`/`gateway/impl` package
pair (mirrors the existing `service`/`service/impl` split):

```java
public interface PaymentGatewayPort {
    GatewayOrder createOrder(BigDecimal amount, String currency, String receipt);
    boolean verifyWebhookSignature(String rawPayload, String signatureHeader);
    GatewayWebhookEvent parseWebhookEvent(String rawPayload);
    String getPublicKeyId();
}
```

- `GatewayOrder` (record: `gatewayOrderId`) and `GatewayWebhookEvent` (record:
  `GatewayEventType type`, `gatewayOrderId`) are new domain-shaped DTOs —
  deliberately a minimal projection of "the two facts our business logic needs
  from any webhook" (did it succeed, which order), not a generic mirror of
  Razorpay's full JSON shape. `GatewayEventType` (`SUCCESS`/`FAILED`) lives in
  the `gateway` package rather than the shared `enums/` package — it's an
  internal contract of this abstraction, not a persisted/entity-level concept,
  so keeping it colocated with the port is higher-cohesion than centralizing it.
- `getPublicKeyId()` was found necessary while working through
  `PaymentServiceImpl`'s pseudocode below, beyond the 3 methods originally
  discussed — the initiate-payment response DTOs need to hand the frontend a
  key to open the gateway's checkout widget, and without this method
  `PaymentServiceImpl` would need `RazorpayProperties` injected directly,
  defeating the point.
- `RazorpayConfig` still exposes the `RazorpayClient` bean (S5-05, unchanged) —
  but now `RazorpayGatewayAdapter` is the *only* class in the codebase that
  touches `RazorpayClient`, `Utils.verifyWebhookSignature`, or Razorpay's raw
  JSON webhook shape. `PaymentServiceImpl`/`WebhookServiceImpl` depend only on
  `PaymentGatewayPort`.
- Genuine side benefit, not just swappability: `WebhookServiceImpl` no longer
  needs `RazorpayProperties` injected at all (the webhook secret stays
  encapsulated inside the adapter), and `WebhookServiceImplTest`'s
  `handleWebhook_invalidSignature` test gets simpler — mock
  `paymentGatewayPort.verifyWebhookSignature(...)` to return `false` directly,
  instead of the original plan's "stub `webhookSecret()` and compute the
  expected signature in the test" workaround.
- **New test added beyond the original plan:** `RazorpayGatewayAdapterTest`
  (S5-08) — the adapter now contains real logic we wrote (JSON parsing of the
  webhook payload, mapping `payment.captured`/`payment.failed` to
  `GatewayEventType`), which the original plan never had a dedicated test for
  since that logic used to live inline in `WebhookServiceImpl`. Flagged here
  explicitly, same as Sprint 3's `RouteServiceImplTest` addition, rather than
  silently expanding scope.
- **Exception handling (found 2026-08-04, verified against the actual SDK jar):**
  `OrderClient.create(...)` and `Utils.verifyWebhookSignature(...)` both
  declare `throws RazorpayException` (checked). Since `PaymentGatewayPort`'s
  methods deliberately declare no checked exceptions (a port shouldn't leak a
  gateway-specific exception type), `RazorpayGatewayAdapter` must catch
  `RazorpayException` internally in both methods — the original plan never
  said what happens there, which would've defaulted to `GlobalExceptionHandler`'s
  generic `500 "An unexpected error occurred"` handler, giving no useful signal
  for something like a bad test-mode key or a brief Razorpay outage. Decision:
  - `createOrder` — catch and rethrow as a new `PaymentGatewayException`
    (unchecked, `exception/`), mapped in `GlobalExceptionHandler` to
    **502 Bad Gateway** — distinguishes "upstream gateway failed" from a
    client-input error.
  - `verifyWebhookSignature` — catch and return `false` (fail-closed) rather
    than propagate. If verification can't even run, the safe default is
    "treat as invalid" — never let an exception accidentally bypass the
    security check.
- **Explicitly out of scope:** abstracting the webhook *endpoint* itself
  (`/webhooks/razorpay` stays a Razorpay-specific URL — that's inherent, since
  only Razorpay will ever call it) and abstracting the `razorpayKeyId` field
  in response DTOs (the frontend's checkout integration is tied to Razorpay's
  widget regardless of backend abstraction). This decision only removes the
  backend's *internal* coupling to the Razorpay SDK.

All task numbers from S5-06 onward were renumbered to make room for the new
Gateway Abstraction section (S5-06/07/08); every task below reflects the
renumbered, current sequence.

---

## Bug found during S5-21 live verification (2026-08-23)

**Symptom:** the overdraft-recovery recharge (Steps 9–14) silently failed the
first time it was run live — wallet stayed at -₹40 after a ₹200 recharge that
Razorpay's own dashboard showed as `paid`, with the `Payment` row stuck
`FAILED` forever.

**Root cause:** Razorpay allows multiple payment *attempts* against the same
*order* (a passenger's card can be declined, then they retry with another
card, all against one `order_id`). Razorpay sends one webhook **per attempt**,
not one per order — an earlier declined attempt's `payment.failed` webhook
arrived and flipped our `Payment` row (keyed one-per-order via
`gatewayReferenceId`) to `FAILED` *before* the eventual successful attempt's
`payment.captured` webhook arrived. `WebhookServiceImpl`'s idempotency guard
was:
```java
if (payment.getStatus() != PaymentStatus.PENDING) { return; } // wrong
```
This treated any non-`PENDING` status — including a merely-one-attempt-failed
`FAILED` — as fully processed, so the later real success webhook was silently
dropped. The wallet was never credited, and the `Payment` row never left
`FAILED`, even though the order genuinely succeeded on Razorpay's side.

**Fix:** only `SUCCESS` is actually terminal for a `Payment`. Changed the
guard to:
```java
if (payment.getStatus() == PaymentStatus.SUCCESS) { return; } // correct
```
A `FAILED` webhook for one attempt no longer blocks a later `SUCCESS` webhook
for the same order from being processed. `SUCCESS` remains the only state
that stops reprocessing, so the original duplicate-webhook idempotency
guarantee (Step 23) is unaffected — verified by `handleWebhook_alreadyProcessed`
(unchanged, already asserted against `SUCCESS`) and a new dedicated regression
test, `handleWebhook_recharge_success_afterPriorFailedAttempt`.

**Trade-off accepted:** a `FAILED` webhook still updates `payment.status =
FAILED` and logs the stand-in failure notification on *every* declined
attempt within a still-open order — so a passenger who fails twice before
succeeding produces 2 logged "failure" notifications ahead of the real
success. Harmless today since notifications are just a `log.info` placeholder
(no real push infrastructure exists yet — see Sprint 4/`ARCHITECTURE.md`), but
worth revisiting once real notifications are built (Sprint 6+): a passenger
shouldn't be alarmed over an attempt they immediately retried.

**Also discovered during this same verification pass:** Razorpay's own test
account had UPI disabled (dashboard "Payment Methods" had no toggle for it,
likely pending account activation/KYC even in test mode) and rejected the
generic international test Visa card (`4111 1111 1111 1111`, "international
card not accepted"). Verification proceeded with Razorpay's domestic test
Mastercard (`5267 3181 8797 5449`) instead — a checkout-account-configuration
detail external to our code, not a backend bug. `WebhookServiceImpl` is
correctly payment-method-agnostic (reacts only to `payment.captured`/
`payment.failed` + the order ID), so this substitution didn't weaken the
verification of any backend flow.

---

## One-Time Setup (Before Writing Any Code)

- [x] SETUP-01 — Create Razorpay test account (already had one)
- [x] SETUP-02 — Install and run ngrok — installed to `~/Developer/tools/ngrok`,
      authtoken configured, tunnel live at
      `https://populace-grab-swan.ngrok-free.dev` → `localhost:8080`
      (stable free-tier subdomain — confirmed it survives a tunnel restart)
- [x] SETUP-03 — Webhook registered in Razorpay dashboard for `payment.captured`,
      `payment.failed` → `https://populace-grab-swan.ngrok-free.dev/webhooks/razorpay`
- [x] SETUP-04 — Credentials added to `infrastructure/.env`
      (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`);
      placeholder entries added to `infrastructure/.env.example`

---

## Tasks

### Dependency

- [x] S5-01 — Add Razorpay Java SDK to `pom.xml`:
  ```xml
  <dependency>
      <groupId>com.razorpay</groupId>
      <artifactId>razorpay-java</artifactId>
      <version>1.4.5</version>
  </dependency>
  ```
  Verify: `./mvnw dependency:tree | grep razorpay` — artifact resolved
  Verify: `./mvnw compile clean` — BUILD SUCCESS

### Configuration

- [x] S5-02 — Create `RazorpayProperties.java` in `config/` —
  `@ConfigurationProperties(prefix="razorpay")` record:
  - `keyId` (String)
  - `keySecret` (String)
  - `webhookSecret` (String)
  - Follows exact same pattern as `WalletProperties` and `TicketProperties`
    from Sprint 4

- [x] S5-03 — Add Razorpay config to `application.properties`:
  ```
  razorpay.key-id=${RAZORPAY_KEY_ID}
  razorpay.key-secret=${RAZORPAY_KEY_SECRET}
  razorpay.webhook-secret=${RAZORPAY_WEBHOOK_SECRET}
  ```

- [x] S5-04 — Create `RazorpayPropertiesTest.java` in `src/test/` —
  `ApplicationContextRunner` verifies all 3 fields bind correctly
  from environment variables. Same pattern as `WalletPropertiesTest`
  and `TicketPropertiesTest` from Sprint 4.
  Verify: `./mvnw test -Dtest=RazorpayPropertiesTest` — passes

- [x] S5-05 — Create `RazorpayConfig.java` in `config/` —
  `@Configuration` class that exposes `RazorpayClient` as a `@Bean`:
  ```java
  @Bean
  public RazorpayClient razorpayClient(RazorpayProperties props)
      throws RazorpayException {
      return new RazorpayClient(props.keyId(), props.keySecret());
  }
  ```
  Centralized — `RazorpayGatewayAdapter` (S5-07) is the only class that
  injects this bean; nothing else in the codebase constructs or touches
  `RazorpayClient` directly.
  Verify: `./mvnw compile clean` — BUILD SUCCESS; app boots clean against
  live Postgres + Razorpay credentials in env

### Gateway Abstraction

- [x] S5-06 — Create `PaymentGatewayPort.java` interface in new `gateway/`
  package, plus its supporting types:
  - `PaymentGatewayPort` — `createOrder(BigDecimal amount, String currency,
    String receipt)`, `verifyWebhookSignature(String rawPayload, String
    signatureHeader)`, `parseWebhookEvent(String rawPayload)`,
    `getPublicKeyId()`
  - `GatewayOrder` (record) — `gatewayOrderId` (String)
  - `GatewayWebhookEvent` (record) — `type` (`GatewayEventType`),
    `gatewayOrderId` (String)
  - `GatewayEventType` (enum) — `SUCCESS`, `FAILED`
  - `PaymentGatewayException` (unchecked, `exception/`) — thrown by the
    adapter when the underlying gateway SDK call fails; mapped in
    `GlobalExceptionHandler` to `502 Bad Gateway` (see "Mid-sprint design
    decision" above, exception-handling sub-section)
  - See "Mid-sprint design decision" above for full rationale
  - Verify: `./mvnw compile clean` — BUILD SUCCESS

- [x] S5-07 — Create `RazorpayGatewayAdapter.java` in `gateway/impl/`,
  `@Component implements PaymentGatewayPort`, injects `RazorpayClient`
  (S5-05 bean) + `RazorpayProperties`:
  - `createOrder` — builds the `JSONObject` order request (amount → paise,
    currency, receipt), calls `razorpayClient.orders.create(...)`, maps the
    result to `GatewayOrder`. Catches `RazorpayException` (checked, thrown by
    the SDK) → rethrows as `PaymentGatewayException` (unchecked) → `502`.
  - `verifyWebhookSignature` — delegates to `Utils.verifyWebhookSignature(
    rawPayload, signatureHeader, razorpayProperties.webhookSecret())`.
    Catches `RazorpayException` → returns `false` (fail-closed) rather than
    propagating.
  - `parseWebhookEvent` — parses the raw JSON, maps `"payment.captured"` →
    `GatewayEventType.SUCCESS` / `"payment.failed"` → `GatewayEventType.FAILED`,
    extracts `payload.payment.entity.order_id`
  - `getPublicKeyId` — returns `razorpayProperties.keyId()`
  - Only class in the codebase referencing `com.razorpay.*` types directly
  - Verify: `./mvnw compile clean` — BUILD SUCCESS

- [x] S5-08 — Create `RazorpayGatewayAdapterTest.java` in `src/test/` —
  **added beyond the original plan** (see "Mid-sprint design decision" above):
  - `createOrder_success_returnsGatewayOrder`
  - `createOrder_razorpayExceptionThrown_throwsPaymentGatewayException`
  - `verifyWebhookSignature_validSignature_returnsTrue`
  - `verifyWebhookSignature_invalidSignature_returnsFalse`
  - `verifyWebhookSignature_razorpayExceptionThrown_returnsFalse` (fail-closed,
    via `Mockito.mockStatic(Utils.class)`)
  - `parseWebhookEvent_paymentCaptured_returnsSuccess`
  - `parseWebhookEvent_paymentFailed_returnsFailed`
  - `getPublicKeyId_returnsConfiguredKeyId`
  - **8 tests, not 6** — split the originally-planned "valid + invalid" case
    into two separate test methods (matches this project's one-scenario-per-test
    convention, e.g. `WalletServiceImplTest`) and added an explicit `createOrder`
    happy-path test that wasn't separately named before.
  - Verify: `./mvnw test -Dtest=RazorpayGatewayAdapterTest` — 8/8 pass

### Security Config Update

- [x] S5-09 — Update `SecurityConfig.java` — add new endpoint rules:
  ```
  POST /payments/recharge/initiate    → ROLE_PASSENGER
  POST /payments/ticket/upi/initiate  → ROLE_PASSENGER
  POST /webhooks/razorpay             → permit all (no JWT — webhook from Razorpay)
  ```
  Note: `/webhooks/razorpay` must be permit-all because Razorpay hits it
  directly — it has no JWT. Security is handled by the gateway port's
  signature verification (S5-07) inside the handler itself, not by Spring
  Security.
  Verify: `./mvnw compile clean`, app boots clean, existing Sprint 4
  flows unaffected (POST /payments/wallet still requires ROLE_PASSENGER)

### DTOs

- [x] S5-10 — Request DTOs in `dto/request/` (Java records):
  - `RechargeInitiateRequestDTO` — amount (@NotNull, @DecimalMin="1.00")
  - `TicketUpiPaymentInitiateRequestDTO` — ticketId (@NotNull)
  - `RazorpayWebhookRequestDTO` — NOT a record — raw webhook payload
    arrives as a plain JSON String (Razorpay sends raw body for signature
    verification; parsing into a typed DTO before verifying the signature
    would lose the raw bytes needed for HMAC). Handled as `String` in
    controller, parsed manually after verification (inside the adapter).

- [x] S5-11 — Response DTOs in `dto/response/` (Java records):
  - `RechargeInitiateResponseDTO` — paymentId (UUID, our internal ID),
    razorpayOrderId (String), amount (BigDecimal), currency (String, "INR"),
    razorpayKeyId (String — frontend needs this to open Razorpay checkout;
    stays Razorpay-named deliberately, see "Mid-sprint design decision" above)
  - `TicketUpiPaymentInitiateResponseDTO` — paymentId (UUID),
    razorpayOrderId (String), amount (BigDecimal), currency (String),
    razorpayKeyId (String), ticketId (UUID)
  - `RechargeConfirmResponseDTO` — paymentId, amountCredited,
    walletBalanceAfter, overdraftRecovered (BigDecimal — 0 if no overdraft),
    status
  - Verify: `./mvnw compile clean` — BUILD SUCCESS

### Payment Service

- [x] S5-12 — Create `PaymentService.java` interface in `service/`

- [x] S5-13 — Create `PaymentServiceImpl.java` in `service/impl/`, injects
  `PaymentGatewayPort` (not `RazorpayClient`/`RazorpayProperties`):

  **`initiateRecharge(RechargeInitiateRequestDTO, UUID userId)`:**
  1. Validate amount > 0 (DTO constraint handles, service double-checks)
  2. Fetch wallet → validate ACTIVE
  3. Create gateway order via the port:
     ```java
     UUID paymentId = UUID.randomUUID();
     GatewayOrder order = paymentGatewayPort.createOrder(
         request.amount(), "INR", paymentId.toString());
     ```
  4. Save `Payment`:
     - `paymentId` = paymentId (generated above, not DB-default — needed as
       the order receipt before the row exists)
     - `userId` = userId
     - `amount` = request.amount()
     - `mode` = PaymentMode.UPI
     - `purpose` = PaymentPurpose.WALLET_TOPUP
     - `status` = PaymentStatus.PENDING
     - `gatewayReferenceId` = order.gatewayOrderId()
     - `referenceId` = null (WALLET_TOPUP has no ticket)
  5. Return `RechargeInitiateResponseDTO` with `order.gatewayOrderId()` and
     `paymentGatewayPort.getPublicKeyId()`
  - `@Transactional`

  **`initiateTicketUpiPayment(TicketUpiPaymentInitiateRequestDTO, UUID userId)`:**
  1. Fetch ticket → `ticketRepository.findByTicketIdAndUserId(ticketId, userId)`
     → throw `ResourceNotFoundException` if absent
  2. Validate ticket status = ISSUED
     → throw `ValidationException("Ticket is not awaiting payment")`
  3. Check no existing PENDING UPI payment for this ticket:
     → `paymentRepository.findByReferenceIdAndStatus(ticketId, PENDING)`
       — **deviation from the plan's own `findByReferenceId(ticketId)`
       (single-result `Optional`, found 2026-08-09):** `Payment.referenceId`
       has no uniqueness constraint (only `gatewayReferenceId` does), so a
       ticket that accumulates a `FAILED` payment row from an earlier retry
       and then gets a new `PENDING` row on a later retry would have 2 rows
       sharing `referenceId` — the next `findByReferenceId` call would then
       throw `IncorrectResultSizeDataAccessException` on a completely
       ordinary "retry after a failed UPI payment" flow. Scoping the query
       to `status = PENDING` keeps it a safe single-result lookup, since
       this method only ever creates a new row when no `PENDING` row
       already exists for that ticket — at most one can exist at a time by
       construction. `PaymentRepository.findByReferenceId` (added ahead in
       Sprint 4, never actually used until now) replaced outright rather
       than kept alongside the new method.
     → if found → return existing payment's orderId (idempotency —
       passenger tapped "Pay via UPI" twice)
  4. Create gateway order via the port (same as recharge, amount =
     ticket.totalFare)
  5. Save `Payment`:
     - `purpose` = PaymentPurpose.TICKET_PAYMENT
     - `referenceId` = ticket.ticketId
     - `gatewayReferenceId` = order.gatewayOrderId()
     - `status` = PaymentStatus.PENDING
  6. Return `TicketUpiPaymentInitiateResponseDTO`
  - `@Transactional`

  Verify: `./mvnw compile clean` — BUILD SUCCESS

### Webhook Service

- [x] S5-14 — Create `WebhookService.java` interface in `service/`

- [x] S5-15 — Create `WebhookServiceImpl.java` in `service/impl/`, injects
  `PaymentGatewayPort` (not `RazorpayProperties` — the webhook secret stays
  encapsulated inside the adapter):

  **`handleRazorpayWebhook(String rawPayload, String razorpaySignature)`:**

  **Step 1 — Signature verification (CRITICAL security step):**
  ```java
  boolean isValid = paymentGatewayPort.verifyWebhookSignature(
      rawPayload, razorpaySignature);
  if (!isValid) {
      throw new ValidationException("Invalid webhook signature");
  }
  ```
  Reject immediately if signature invalid — do not process.

  **Step 2 — Parse the event via the port:**
  ```java
  GatewayWebhookEvent event = paymentGatewayPort.parseWebhookEvent(rawPayload);
  // event.type() -> GatewayEventType.SUCCESS or FAILED
  // event.gatewayOrderId() -> matches Payment.gatewayReferenceId
  ```

  **Step 3 — Find our Payment by gatewayReferenceId:**
  ```java
  Payment payment = paymentRepository
      .findByGatewayReferenceId(event.gatewayOrderId())
      .orElseThrow(() -> new ResourceNotFoundException(
          "Payment", "gatewayReferenceId", event.gatewayOrderId()));
  ```

  **Step 4 — Idempotency check:**
  ```java
  if (payment.getStatus() != PaymentStatus.PENDING) {
      // Already processed — Razorpay may retry webhooks
      // Return 200 OK to stop retries, do nothing
      return;
  }
  ```

  **Step 5 — Handle by event type:**

  If `GatewayEventType.SUCCESS`:
  - If `payment.purpose == WALLET_TOPUP` → call `processRecharge(payment)`
  - If `payment.purpose == TICKET_PAYMENT` → call `processTicketPayment(payment)`
  - `payment.status` = SUCCESS, `payment.gatewayReferenceId` already set

  If `GatewayEventType.FAILED`:
  - `payment.status` = FAILED
  - Save payment
  - Push notification to passenger: "Payment failed — please try again"
    — **deviation (found 2026-08-09):** no notification/push infrastructure
    exists anywhere in the codebase (`ARCHITECTURE.md`'s own roadmap lists
    Notifications as unbuilt, likely message-queue-based, future scope).
    Building real push delivery is out of scope for this sprint. Implemented
    as a `log.info` naming the intended recipient (`userId`) and message —
    a placeholder marking where a real notification call goes later, not a
    real notification.

  **Implementation note:** `payment.setStatus(...)` + the single
  `paymentRepository.save(payment)` happen once, in `handleRazorpayWebhook`
  itself after the `SUCCESS`/`FAILED` branch, rather than duplicated inside
  each private helper as literally shown below (both `processRecharge` and
  `processTicketPayment`'s step 4 said "save payment") — avoids a redundant
  second save of the same row within one transaction.

  **`processRecharge(Payment payment)`** — private method:
  1. Fetch wallet by `payment.userId`
  2. Overdraft recovery:
     ```java
     BigDecimal currentBalance = wallet.getBalance();
     if (currentBalance.compareTo(BigDecimal.ZERO) < 0) {
         // Balance is negative — recover overdraft first
         BigDecimal overdraftAmount = currentBalance.abs();

         // Record 1: DEBIT for overdraft recovery
         // referenceId = payment.getPaymentId() (not null — see the
         // "Pre-existing gaps found during plan review" section above for why)
         Transaction recovery = Transaction.builder()
             .userId(payment.getUserId())
             .amount(overdraftAmount)
             .type(TransactionType.DEBIT)
             .status(TransactionStatus.SUCCESS)
             .referenceId(payment.getPaymentId())
             .build();
         transactionRepository.save(recovery);
     }
     ```
  3. Credit full recharge amount:
     ```java
     // Record 2: CREDIT for recharge
     wallet.setBalance(currentBalance.add(payment.getAmount()));
     // e.g. -40 + 200 = 160

     Transaction credit = Transaction.builder()
         .userId(payment.getUserId())
         .amount(payment.getAmount())
         .type(TransactionType.CREDIT)
         .status(TransactionStatus.SUCCESS)
         .referenceId(payment.getPaymentId())
         .build();
     transactionRepository.save(credit);
     walletRepository.save(wallet);
     ```
  4. Save payment (status = SUCCESS) — see "Implementation note" above;
     handled by the caller instead
  - Returns `RechargeConfirmResponseDTO` (built in S5-11 but otherwise
    unreferenced anywhere in the original plan text — resolved by having
    this method return it and `handleRazorpayWebhook` `log.info` it after a
    successful recharge, giving a money-movement event structured,
    queryable logging rather than leaving the DTO dead code)
  - `@Transactional`

  **`processTicketPayment(Payment payment)`** — private method:
  1. Fetch ticket by `payment.referenceId`
  2. Validate status = ISSUED (guard against double-processing)
  3. Update ticket → PAID, set `paidAt` = Instant.now()
  4. Save ticket, save payment (status = SUCCESS)
  - No wallet deduction — UPI charges externally via Razorpay
  - No Transaction record — payment is external, not internal wallet movement
  - `@Transactional`

  Verify: `./mvnw compile clean` — BUILD SUCCESS

### New Repository Method

- [x] S5-16 — Add to `PaymentRepository.java` in `repository/`:
  - `Optional<Payment> findByGatewayReferenceId(String gatewayReferenceId)`
  - Needed by `WebhookServiceImpl` to look up payment by Razorpay order ID
  - Verify: `./mvnw compile clean`, app boots clean (no
    `PropertyReferenceException` — `gatewayReferenceId` field exists
    on `Payment` entity from Sprint 1)

### Controllers

- [x] S5-17 — Create `PaymentController.java` in `controller/`
  (Note: Sprint 4 already created `PaymentController` for wallet payment.
  Add new endpoints to the existing class — do not create a duplicate):
  - `POST /payments/recharge/initiate` (ROLE_PASSENGER)
    → extract `userId` from `@AuthenticationPrincipal UserPrincipal`
    → `PaymentServiceImpl.initiateRecharge()`
    → `ApiResponse<RechargeInitiateResponseDTO>`
  - `POST /payments/ticket/upi/initiate` (ROLE_PASSENGER)
    → `PaymentServiceImpl.initiateTicketUpiPayment()`
    → `ApiResponse<TicketUpiPaymentInitiateResponseDTO>`
  - Verify: `./mvnw compile clean`

- [x] S5-18 — Create `WebhookController.java` in `controller/`:
  - `POST /webhooks/razorpay` — NO `@AuthenticationPrincipal`,
    NO JWT — this endpoint is called by Razorpay, not a user:
    ```java
    @PostMapping("/webhooks/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
        @RequestBody String rawPayload,
        @RequestHeader("X-Razorpay-Signature") String signature) {

        webhookService.handleRazorpayWebhook(rawPayload, signature);
        return ResponseEntity.ok("OK");
    }
    ```
  - Returns plain `"OK"` string (not `ApiResponse`) — Razorpay expects
    HTTP 200 with any body to confirm receipt. Non-200 triggers retries.
  - `@RequestBody String` (not a DTO) — raw body required for
    HMAC signature verification before parsing
  - Missing `X-Razorpay-Signature` header → Spring returns 400
    before entering handler (acceptable — Razorpay always sends it)
    — **bug found and fixed (2026-08-10), live-verified, not just assumed
    from the plan text:** this assumption was wrong for this codebase.
    `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)`
    intercepts *all* exceptions thrown during request handling —
    including `MissingRequestHeaderException`, raised by Spring during
    argument resolution before the controller method runs — so it was
    actually returning `500 "An unexpected error occurred"` (confirmed via
    live curl + the app log: `org.springframework.web.bind.
    MissingRequestHeaderException: Required request header
    'X-Razorpay-Signature' ... is not present`), masking a client error as
    a server error. Same class of bug as Sprint 2's missing
    `AuthenticationEntryPoint` (403→401): a framework-level exception falls
    through to the generic handler because nothing claims it specifically.
    Fixed by adding a dedicated `@ExceptionHandler(MissingRequestHeaderException.class)`
    to `GlobalExceptionHandler`, mapped to `400`, same pattern as every
    other specific handler there. Re-verified live: missing header → `400`
    `"Missing required header: X-Razorpay-Signature"`.
  - Verify: `./mvnw compile clean`, app boots clean — done, plus live curl
    verification of both new `PaymentController` endpoints (`401`
    unauthenticated) and the webhook endpoint (`400` missing header,
    `400` invalid signature — confirms `permitAll()` still lets the
    request reach the handler rather than blocking it at the security layer)

  **Unrelated pre-existing test flake fixed along the way (2026-08-10):**
  the full suite run for this task surfaced `JwtUtilTest.
  isTokenValid_returnsFalse_forTamperedToken` failing — a test already
  flagged as flaky in Sprint 4's dev log ("cleared on re-run"). Rather than
  re-assert that label, traced the actual cause: the test always tampers
  with the token's *last* character, but base64url's final character in a
  segment can carry "don't-care" padding bits (forced to `0`, not derived
  from real signature bytes) that decoders don't always validate — roughly
  1-in-4 of the time, the `'a'`↔`'b'` swap only touches those bits, so the
  "tampered" token decodes to byte-identical content and still validates.
  Confirmed empirically: 3 failures across 7 runs before the fix, 0
  failures across 8 runs after. Fixed by tampering a character in the
  middle of the header segment (index 5) instead of the token's last
  character — deterministic regardless of run, since only a segment's
  *final* character can have don't-care bits. Not a security issue in
  `JwtUtil` itself, purely a test-design flaw.

### Testing & Verification

- [x] S5-19 — Unit tests: `PaymentServiceImplTest.java` in `src/test/`:
  - Mock: `PaymentRepository`, `WalletRepository`, `TicketRepository`,
    `PaymentGatewayPort` (mock the port, not the Razorpay SDK — no real
    HTTP calls or SDK types in this test)
  - `initiateRecharge_success` — gateway order created, Payment saved
    with PENDING status, correct orderId in response
  - `initiateRecharge_inactiveWallet` → `ValidationException` thrown
  - `initiateTicketUpiPayment_success` — Payment saved with TICKET_PAYMENT
    purpose, correct ticketId linked
  - `initiateTicketUpiPayment_ticketNotFound`
    → `ResourceNotFoundException` thrown
  - `initiateTicketUpiPayment_ticketAlreadyPaid`
    → `ValidationException` thrown
  - `initiateTicketUpiPayment_existingPendingPayment` — returns existing
    orderId without creating a new Payment (idempotency)
  - Verify: `./mvnw test -Dtest=PaymentServiceImplTest` — all pass

- [x] S5-20 — Unit tests: `WebhookServiceImplTest.java` in `src/test/`:
  - Mock: `PaymentRepository`, `WalletRepository`, `TicketRepository`,
    `TransactionRepository`, `PaymentGatewayPort` (mock
    `verifyWebhookSignature`/`parseWebhookEvent` directly — no need to
    compute a real HMAC signature in the test, unlike the original plan)
  - `handleWebhook_recharge_success_noOverdraft` — wallet credited,
    1 CREDIT transaction, payment → SUCCESS
  - `handleWebhook_recharge_success_withOverdraft` — wallet balance
    was -₹40, recharged ₹200: 1 DEBIT (₹40 recovery) + 1 CREDIT (₹200),
    final balance ₹160, payment → SUCCESS
  - `handleWebhook_ticketPayment_success` — ticket → PAID, no wallet
    deduction, no Transaction record, payment → SUCCESS
  - `handleWebhook_invalidSignature` → `ValidationException` thrown,
    no DB changes made (mock `verifyWebhookSignature` → `false`)
  - `handleWebhook_alreadyProcessed` — payment status already SUCCESS →
    returns silently, no duplicate processing (idempotency)
  - `handleWebhook_paymentFailed` — payment → FAILED, ticket unchanged
  - Verify: `./mvnw test -Dtest=WebhookServiceImplTest` — all pass
  - Full suite: `./mvnw test` — all pass

- [x] S5-21 — End-to-end verification (ngrok + Razorpay test mode):

  **Prerequisites:**
  - ngrok running: `ngrok http 8080`
  - Razorpay webhook URL updated to current ngrok URL in dashboard
  - App running with Razorpay env vars loaded
  - Razorpay test card: `4111 1111 1111 1111` (always succeeds in test mode)
  - Razorpay test UPI: `success@razorpay` (always succeeds in test mode)

  **Wallet recharge flow (no overdraft):**
  1. `POST /auth/login` (passenger) → `{{passengerToken}}`
  2. `GET /passenger/wallet/balance` → note current balance
  3. `POST /payments/recharge/initiate` (passenger token, amount=200)
     → expect: `razorpayOrderId`, `razorpayKeyId`, `amount=200`
  4. Open Razorpay checkout with returned `orderId` + `keyId`
     (use Razorpay's test checkout page or their mobile SDK test harness)
  5. Pay with test UPI `success@razorpay`
  6. Watch ngrok terminal — expect incoming POST to `/webhooks/razorpay`
  7. `GET /passenger/wallet/balance`
     → expect balance increased by ₹200
  8. `GET /passenger/wallet/transactions`
     → expect 1 CREDIT transaction, amount=200

  **Wallet recharge flow (with overdraft):**
  9.  Update wallet to -₹40 via psql (simulate overdraft from Sprint 4)
  10. `POST /payments/recharge/initiate` (amount=200)
  11. Pay via Razorpay test checkout
  12. Watch ngrok → webhook received
  13. `GET /passenger/wallet/balance` → expect ₹160 (-40+200)
  14. `GET /passenger/wallet/transactions`
      → expect: 1 DEBIT (₹40 overdraft recovery) + 1 CREDIT (₹200)
      → ordered newest first

  **UPI ticket payment flow:**
  15. Issue a ticket via conductor: `POST /tickets/issue`
      → note `ticketId`, `totalFare`
  16. `POST /payments/ticket/upi/initiate` (passenger token, ticketId)
      → expect: `razorpayOrderId`, `ticketId` in response
  17. Pay via Razorpay test checkout (amount = ticket totalFare)
  18. Watch ngrok → webhook received
  19. `GET /passenger/tickets/{ticketId}`
      → expect status=PAID, paidAt non-null
  20. `GET /passenger/wallet/balance`
      → expect UNCHANGED (UPI payment — no wallet deduction)
  21. `GET /passenger/wallet/transactions`
      → expect NO new transaction (UPI is external, not internal wallet)

  **Security verification:**
  22. POST `/webhooks/razorpay` with tampered payload (wrong signature)
      → expect 400 "Invalid webhook signature"
  23. POST `/webhooks/razorpay` same valid payload twice
      → expect 200 OK on second call but no duplicate processing
      (idempotency — payment already SUCCESS)

### Git

- [x] S5-22 — Commit and merge `feature/payment-gateways` into `dev`
  (branch actually named `feature/payment-gateways`, not
  `feature/razorpay-payments` as originally planned — functionally identical)
  - Ensure `infrastructure/.env` (with real keys) is in `.gitignore` ✅
  - Only `infrastructure/.env.example` (with placeholder values) is committed
  - Verify: `./mvnw compile clean` on dev post-merge

---

## Dependencies

| Dependency | Status |
|---|---|
| Spring Boot 4.1.0 project | ✅ Done (Sprint 1) |
| Payment entity with PaymentPurpose, PaymentMode, PaymentStatus enums | ✅ Done (Sprint 1) |
| Transaction entity with TransactionType, TransactionStatus enums | ✅ Done (Sprint 1) |
| Wallet with @Version (optimistic locking) | ✅ Done (Sprint 1) |
| PaymentRepository (findByReferenceId) | ✅ Done (Sprint 4) |
| TransactionRepository | ✅ Done (Sprint 4) |
| WalletRepository | ✅ Done (Sprint 2) |
| TicketRepository (findByTicketIdAndUserId) | ✅ Done (Sprint 4) |
| GlobalExceptionHandler (ValidationException, ResourceNotFoundException) | ✅ Done (Sprint 2) |
| Passenger auth (ROLE_PASSENGER JWT) | ✅ Done (Sprint 2) |
| Razorpay test account + ngrok | ✅ Done (this sprint, SETUP-01 to SETUP-04) |
| Admin auth | ⏳ Sprint 6 |
| Overdraft rule defined | ✅ Done (Sprint 4 — WalletProperties) |

---

## Definition of Done

- [x] Razorpay SDK in `pom.xml`, `RazorpayClient` bean wired via `RazorpayConfig`
- [x] `RazorpayProperties` binds correctly (verified via `RazorpayPropertiesTest`)
- [x] `PaymentGatewayPort`/`RazorpayGatewayAdapter` in place —
      `PaymentServiceImpl`/`WebhookServiceImpl` depend only on the port,
      never on `RazorpayClient`/`RazorpayProperties` directly
      (verified via `RazorpayGatewayAdapterTest`)
- [x] `POST /payments/recharge/initiate` returns Razorpay `orderId` + `keyId`
- [x] Razorpay test payment → ngrok → `/webhooks/razorpay` → wallet credited
- [x] Overdraft recovery: balance -₹40, recharge ₹200 →
      1 DEBIT (₹40) + 1 CREDIT (₹200), final balance ₹160 — required a bug
      fix mid-verification, see "Bug found during S5-21 live verification"
      above; second live attempt confirmed correct
- [x] `POST /payments/ticket/upi/initiate` returns Razorpay `orderId`
- [x] Razorpay test payment → webhook → ticket PAID, wallet unchanged,
      no Transaction record created
- [x] Invalid webhook signature → 400, no DB changes
- [x] Duplicate webhook → 200 OK, no duplicate processing
- [x] All 8 `RazorpayGatewayAdapterTest` tests pass, including the
      `PaymentGatewayException`/fail-closed-signature-verification cases
- [x] All 6 `PaymentServiceImplTest` tests pass
- [x] All 7 `WebhookServiceImplTest` tests pass (7, not the originally
      planned 6 — `handleWebhook_recharge_success_afterPriorFailedAttempt`
      added as a regression test for the bug above)
- [x] All 23 end-to-end verification steps pass (ngrok + Razorpay test mode)
- [x] `infrastructure/.env` not committed, `.env.example` has placeholders
- [x] `feature/payment-gateways` merged into `dev`, build clean
