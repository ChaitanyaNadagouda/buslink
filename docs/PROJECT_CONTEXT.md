# PROJECT_CONTEXT.md

This document always represents the **current state** of the project. Update it whenever a sprint finishes.

## Project

BusLink

## Current Sprint

Sprint 5

## Current Objective

Sprint 5 is closed. Integrated Razorpay (test mode) via ngrok for real webhook
delivery: `PaymentGatewayPort`/`RazorpayGatewayAdapter` (gateway abstraction, only
class touching the Razorpay SDK directly), wallet recharge flow (`POST /payments/
recharge/initiate` → webhook → credit wallet + overdraft recovery), UPI ticket
payment flow (`POST /payments/ticket/upi/initiate` → webhook → ticket PAID),
`WebhookController`/`WebhookServiceImpl` (signature-verified, public endpoint), unit
tests, and full 23-step Postman + ngrok + Razorpay test-mode live verification (guided
step-by-step in Postman Desktop, same pattern as Sprint 2/3/4).

## Completed

- ✓ Installed WSL Ubuntu 24.04
- ✓ Configured IntelliJ with WSL
- ✓ Installed Java 21
- ✓ Installed Maven
- ✓ Installed Git
- ✓ Configured GitHub SSH
- ✓ Installed GitHub CLI
- ✓ Installed Node.js LTS
- ✓ Installed npm
- ✓ Installed Claude Code
- ✓ Installed Docker Desktop
- ✓ Enabled Docker WSL Integration
- ✓ Verified Docker installation
- ✓ Installed PostgreSQL using Docker Compose
- ✓ Installed pgAdmin using Docker Compose
- ✓ Successfully connected pgAdmin to PostgreSQL
- ✓ Generated Spring Boot project (`backend/`, Spring Boot 4.1.0 — bumped from planned 3.x, see `docs/ARCHITECTURE.md`)
- ✓ Added Sprint 1 dependencies (webmvc, data-jpa, postgresql, security, lombok, validation, springdoc-openapi 3.0.3)
- ✓ Verified clean build (`./mvnw clean install`)
- ✓ Passenger auth (register/login/refresh), QR generation, secured profile/QR endpoints (Sprint 2)
- ✓ Conductor auth (`ROLE_CONDUCTOR` JWT), role-based `SecurityConfig` (`ROLE_PASSENGER`/`ROLE_CONDUCTOR`/`ROLE_ADMIN`)
- ✓ Route/RouteStop domain: entities, repositories, `RouteService`/`FareService`/`BusService` + impls
- ✓ Admin (`AdminRouteController`, `AdminBusController`) and conductor-facing (`RouteController`) REST APIs
- ✓ `DataSeeder` — Route 500K, 29 stops, 1 bus, 1 conductor, idempotent on a non-empty `route` table
- ✓ `FareServiceImplTest` (5), `RouteServiceImplTest` (8) — all passing
- ✓ Postman verification — Conductor Auth/Route/Fare/Admin folders, all assertions passing against live app + DB
- ✓ `IdempotencyKey` entity/repository, `TicketRepository`/`TransactionRepository`/`PaymentRepository`
- ✓ `Ticket` entity updates (`sourceStop`→`originStop` rename, `paidAt`), `TicketStatus.TERMINATED` (plus a DB check-constraint gap caught and fixed — `ddl-auto=update` doesn't alter existing constraints)
- ✓ `WalletProperties`/`TicketProperties` (`@ConfigurationProperties` records) for overdraft limit and idempotency TTL
- ✓ `TicketService`/`TicketServiceImpl` — idempotent ticket issuance (full conductor/bus/route/stop validation chain + fare calc), pending-list, terminate, passenger ticket history/detail
- ✓ `WalletService`/`WalletServiceImpl` — wallet payment with overdraft support and optimistic-lock-protected deduction, balance/transaction-history reads
- ✓ `TicketController`, `PassengerController`, `PaymentController` — 8 new REST endpoints, all role-gated via `SecurityConfig`
- ✓ `TicketServiceImplTest` (8), `WalletServiceImplTest` (7) — all passing
- ✓ Postman verification — `Ticket`/`Payment`/`Passenger` folders (15 new requests), all 20 verification steps passing, run in Postman Desktop against a live app + DB
- ✓ Razorpay SDK, `RazorpayProperties`/`RazorpayConfig`, `PaymentGatewayPort`/`RazorpayGatewayAdapter` gateway abstraction (Sprint 5)
- ✓ `PaymentService`/`WebhookService` + impls — wallet recharge (with overdraft recovery) and UPI ticket payment, both confirmed via real signed webhooks through ngrok
- ✓ `WebhookController` — public `POST /webhooks/razorpay`, signature-verified inside the handler, not by Spring Security
- ✓ `RazorpayGatewayAdapterTest` (8), `PaymentServiceImplTest` (6), `WebhookServiceImplTest` (7) — all passing
- ✓ 23-step Postman + ngrok + Razorpay test-mode live verification — all steps passing, including a real bug found and fixed mid-verification (webhook idempotency guard wrongly treated a `FAILED` payment attempt as terminal, dropping a later real success — see `docs/sprints/Sprint-05.md`)

## Current Architecture

Backend only.

Frontend postponed.

Spring Boot 4.1.0 (see `docs/ARCHITECTURE.md` for the version-bump rationale).

## Current Database

PostgreSQL.

## Current Infrastructure

Docker Compose.

## Next Planned Milestone

Sprint 6 — not yet planned. Likely scope per `ARCHITECTURE.md`'s roadmap: admin
authentication, admin CRUD, analytics, Redis caching (all explicitly deferred from
Sprint 5). Awaiting the next sprint plan from Notion.

**Sprint 5 — closed (2026-08-23).** All Definition of Done items verified
individually and checked: Razorpay SDK/config/gateway abstraction in place,
`PaymentGatewayPort`/`RazorpayGatewayAdapter` verified via 8 passing
`RazorpayGatewayAdapterTest` tests, recharge/UPI-ticket-payment initiation
endpoints returning correct Razorpay order details, both flows confirmed via real
signed webhook delivery through ngrok (overdraft recovery: -₹40 → ₹160 exactly, 1
DEBIT + 1 CREDIT), invalid-signature and duplicate-webhook security checks both
passing, all 6 `PaymentServiceImplTest` and 7 `WebhookServiceImplTest` tests
passing, all 23 end-to-end verification steps passing. One real bug found and
fixed during S5-21's live verification: Razorpay sends one webhook per payment
*attempt*, not per order, so an earlier declined attempt's `payment.failed`
webhook permanently stuck a `Payment` row at `FAILED`, silently dropping the
later successful attempt's `payment.captured` webhook — fixed by only treating
`SUCCESS` as a terminal status in the idempotency guard, with a dedicated
regression test added. Two checkout-account quirks hit during verification
(external to the codebase, not bugs): UPI unavailable at checkout on this test
account, and the generic international test Visa card rejected — worked around
using Razorpay's domestic test Mastercard instead. One gap found during plan
review, before implementation began (`Transaction.referenceId` is `NOT NULL`, but
the plan's overdraft-recovery step needed a `referenceId(null)` `Transaction`) —
resolved by reusing `payment.getPaymentId()` for both the recovery DEBIT and
recharge CREDIT; see `Sprint-05.md`'s gap-review section for the full reasoning.
`feature/payment-gateways` merged into `dev`, build clean.

**Sprint 4 — closed (2026-07-30).** 12 of 16 Definition of Done items verified
individually and checked; 4 left honestly unchecked rather than rubber-stamped:
`idempotency_key` table and the `ticket` table's `paid_at`/`origin_stop`/`source_stop`
changes were confirmed via `psql \d`, not an actual look in the pgAdmin UI (same
DDL-log-vs-pgAdmin distinction Sprint 1 drew); the `GET /conductor/tickets/pending`
ordering (`ASC` by `issuedAt`) and `GET /passenger/tickets` ordering ("newest first")
claims were never exercised against 2+ simultaneous entries in this sprint's testing,
only ever 0 or 1 at a time. The merge item itself was pending until this closure. All 8
`TicketServiceImplTest` and 7 `WalletServiceImplTest` tests passing, all 20 Postman
verification steps passing (run in Postman Desktop by the user this time, not
curl-driven by the assistant — see `docs/DEVELOPMENT_LOG.md` for why that changed
mid-sprint). One DB-level gap caught and fixed beyond the plan's own text: a Sprint-1-era
Hibernate-generated `ticket_status_check` constraint still only allowed the original 4
`TicketStatus` values — `ddl-auto=update` never touches existing constraints, so adding
`TERMINATED` to the Java enum alone would have compiled and passed mocked tests while
failing at the DB layer the first time a real `terminate` call tried to persist it;
proved with a rollback-wrapped `INSERT` before fixing it via `psql`.
`feature/ticket-wallet-payment` merged into `dev`, build clean.

**Sprint 3 — closed (2026-07-26).** All Definition of Done items verified
individually: `route`/`route_stop`/`ticket` schema changes confirmed in pgAdmin,
conductor login returning tokens with a non-null `routeId`, all 4 stop/fare Postman
checks passing against the seeded Route 500K (29 stops), `ROLE_PASSENGER` correctly
rejected with `403` on conductor endpoints, no-token requests to `/admin/**`
correctly rejected with `401`, all 13 `FareServiceImplTest`/`RouteServiceImplTest`
unit tests passing, and all Postman assertions passing in Postman Desktop against a
live app + DB. `RouteServiceImplTest` was added beyond the sprint's original task
list after finding it declared in Scope but missing a task number/DoD line — closed
as part of S3-30 rather than left unfulfilled. One design gap caught and fixed
during S3-21/S3-23: `RouteStopRepository`'s stop-name search used `Containing`
(substring match) instead of `StartingWith` (prefix match), which would have
returned extra false-positive stops on real seeded data — see `DEVELOPMENT_LOG.md`
for the full trace. `feature/route-fare-conductor-auth` merged into `dev`, build
clean, all tests passing.

**Sprint 2 — closed (2026-07-21).** All Definition of Done items verified individually:
register/login/refresh issuing JWT access + refresh tokens, QR token generation at
registration, secured `/user/profile` and `/user/qr` endpoints, all 5 Postman requests
passing against a live app + DB (including direct pgAdmin checks), Swagger UI still
reachable after `SecurityConfig` lockdown, and all 5 `AuthServiceImplTest` unit tests
passing. Two bugs found and fixed during verification: unauthenticated requests
returned 403 instead of 401 (missing `AuthenticationEntryPoint`), and duplicate-email
registration returned 400 instead of 409 (wrong exception type) — see
`DEVELOPMENT_LOG.md` for root causes. `feature/auth-register-login-refresh-uerProfile-
QR-generation` merged into `dev` (`4b7af17`), `dev` pushed to `origin/dev`, build clean.

**Sprint 1 — closed (2026-07-08).** All Definition of Done items verified, including the
final one (9 tables visually confirmed in pgAdmin — previously only checked via Hibernate's
DDL log). S1-28/S1-29 (repository + service stub) moved to Sprint 2, to be built together
with the rest of the auth flow as one coherent unit rather than a stub built twice.

## Notes

- Sprint 1 delivered infrastructure only — no business logic, no REST APIs, no auth exist
  yet. `SecurityConfig` is still the Sprint 1 permit-all stub; Sprint 2's S2-05 replaces it.
- All 9 entities created and verified in Postgres: `User`, `Wallet`, `Transaction`, `Payment`, `Route`, `Bus`, `Conductor`, `Ticket`, `SyncEvent` — confirmed via both Hibernate DDL log and a visual pgAdmin check, app starts clean, `ddl-auto=update`.
- Swagger UI verified working (credential-free) at `/swagger-ui/index.html`.
- `backend/` (and `docs/`, `infrastructure/`) committed and pushed to GitHub (`buslink` monorepo); branch strategy (`main` → `dev` → `feature/*`) is in place: `dev` is the integration branch where feature branches land and accumulate, `main` only ever merges from `dev` (not directly from feature branches). Sprint 1 merged from `feature/project-setup` into `dev` (`ae3d60e`). Sprint 2 merged from `feature/auth-register-login-refresh-uerProfile-QR-generation` into `dev` (`4b7af17`), `dev` pushed to `origin/dev`; the feature branch's remote copy was left one commit behind since it's disposable after merge (no PR workflow in use).
- Sprint 3: all doc-closure updates (this entry included) were committed on `feature/route-fare-conductor-auth` *before* merging into `dev` — a deliberate change from Sprint 1/2's pattern (where the closure commit landed on `dev` right after merging, so it could cite the merge commit's hash). This means the merge commit hash isn't recorded here; `git log` on `dev` is authoritative for that.
- `/admin/**` has no live authentication path as of Sprint 3 close — `AdminRouteController`/`AdminBusController` are fully implemented and correctly reject unauthenticated requests (`401`), but nothing can mint a `ROLE_ADMIN` JWT yet (no `Admin` entity/principal/login endpoint). Route 500K and all Sprint 3 seed data were created by `DataSeeder` bypassing the HTTP layer entirely, not through the admin API. Real admin auth is deferred, likely Sprint 4+ — same recipe as conductor auth (separate `UserDetailsService`, JWT role claim), not designed in detail yet.
- No wallet-recharge endpoint exists as of Sprint 4 close — `POST /payments/wallet` can only *debit* an existing balance. Sprint 4's own Postman verification had to top up test wallets via direct `psql UPDATE` statements (₹150, then ₹20, then -₹80 for the overdraft tests) since there's no HTTP path to do it. Wallet recharge (and overdraft recovery on recharge) is explicit Sprint 5 scope.
- **Resolved in Sprint 5:** `POST /payments/recharge/initiate` now lets a passenger top up their wallet via Razorpay, with overdraft auto-recovered on recharge. `psql`-driven balance manipulation is still used to *simulate* a pre-existing overdraft state for testing (Sprint 5's own S5-21 Step 9), since there's no legitimate way to overdraw a wallet outside of real ticket payments — that part of the pattern is inherent to test setup, not a gap.
- `/admin/**` still has no live authentication path as of Sprint 5 close (same gap noted at Sprint 3/4 close) — real admin auth remains Sprint 6 scope per `ARCHITECTURE.md`.
