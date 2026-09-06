# ARCHITECTURE.md

This document explains the overall architecture of BusLink — the high-level design and the reasoning behind it. It intentionally avoids implementation details (those belong in code, `docs/`, and inline comments); this is the "why" and "shape" of the system, not the "how."

---

## Project Vision

BusLink is a production-quality BMTC (Bangalore transit) digital ticketing and transport platform, built as a flagship backend engineering portfolio project. The primary goal is to learn enterprise backend engineering by building a system the way a real engineering team would: layered architecture, containerized infrastructure, migration-managed schema, secured APIs, and documentation that tracks decisions as they're made — not a toy CRUD app.

The domain (BMTC ticketing) was chosen because it naturally requires the kinds of systems a backend engineer is expected to design in industry: authentication, payments, real-time validation (QR scanning at boarding), and reporting — while still being scoped enough for one person to build end-to-end.

---

## High-Level Architecture

BusLink is currently a **backend-only, monolithic, layered application** running on containerized infrastructure:

- **Application layer** — a single Spring Boot service (monolith, not microservices) exposing REST APIs.
- **Data layer** — PostgreSQL, schema-versioned via Flyway.
- **Infrastructure layer** — Docker Compose orchestrating the app and its dependencies (Postgres, pgAdmin) as isolated, reproducible containers.

A monolith is the deliberate starting point, not an oversight: microservices solve organizational and independent-scaling problems that don't exist yet for a single-developer project in its first sprints. Splitting services prematurely would add network boundaries, distributed transactions, and deployment complexity without a corresponding benefit. The layered architecture inside the monolith (see below) keeps the codebase modular enough to extract services later *if* a real need for that emerges (e.g., QR validation needing independent scaling under load).

---

## Technology Choices

| Concern | Choice |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Build tool | Maven |
| Database | PostgreSQL |
| Schema migrations | Flyway |
| Auth | Spring Security + JWT |
| Object mapping | MapStruct |
| Boilerplate reduction | Lombok |
| API docs | Swagger (springdoc-openapi 3.0.3) |
| Testing | JUnit, Mockito |
| Local infra | Docker, Docker Compose |
| Payment gateway | Razorpay (test mode), abstracted behind `PaymentGatewayPort` |
| Cache | Redis (`redis:7-alpine`), via Spring's cache abstraction (`@Cacheable`/`@CacheEvict`) — Sprint 6 |
| Frontend (future) | React |

### Note on Spring Boot version (2026-07-05)

Sprint 1 was originally scoped for Spring Boot 3.x. By the time the project was generated, Initializr's default had moved to **Spring Boot 4.1.0** — 3.x had become the trailing legacy line (latest patch `3.5.16`). We chose to generate on 4.1.0 rather than pin to legacy 3.x, since this project favors current industry practice over matching an already-stale plan.

This carries real shape changes worth remembering when reading Boot-3-era tutorials/docs:

- `spring-boot-starter-web` → **`spring-boot-starter-webmvc`** (Boot 4 splits the web starter explicitly between MVC and WebFlux).
- The single `spring-boot-starter-test` is now **split per starter** (`spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, etc.) rather than one bundle pulling in everything.
- `springdoc-openapi` is pinned to **3.0.3** (the 2.x line targets Spring Framework 6 / Boot 3; 3.x targets Spring Framework 7 / Boot 4). It has never been an Initializr-catalog dependency in either line and is added to `pom.xml` by hand.
- Spring Security 7.x (paired with Boot 4.1.0) moved `UsernamePasswordAuthenticationFilter` from `org.springframework.security.authentication` to **`org.springframework.security.web.authentication`** — Boot-3-era Spring Security tutorials/snippets will have the old import and fail to compile as-is (hit during Sprint 2, S2-05).
- Boot 4.1's default JSON engine is **Jackson 3**, under a new Maven groupId/package: `tools.jackson.databind.ObjectMapper`, not the classic `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2). `spring-boot-starter-jackson` (pulled in by `spring-boot-starter-webmvc`) only autoconfigures a bean of the new Jackson 3 type. Classic Jackson 2 classes can still be on the classpath via unrelated dependencies (here, `jjwt-jackson` pulls it in for jjwt's own internal claim serialization) without ever being Spring-managed — constructor-injecting `com.fasterxml.jackson.databind.ObjectMapper` fails at startup with "no bean of that type" even though the class resolves fine at compile time. Hit during Sprint 2 while building `JwtAuthenticationEntryPoint` (S2-22 verification pass).

Going forward: if a tech decision changes mid-implementation because a better/newer option surfaces, update this file and the active sprint file immediately rather than waiting for sprint close — these docs should never lag behind what's actually running.

### Reasons for choosing PostgreSQL

BusLink's domain is relational at its core — riders, routes, tickets, payments, and bus stops all have well-defined relationships and need transactional integrity (a payment and a ticket issuance must succeed or fail together). PostgreSQL is chosen over NoSQL alternatives because:

- **ACID transactions** are a hard requirement for anything touching payments or ticket state — losing or double-issuing a ticket is unacceptable.
- **Mature relational modeling** (foreign keys, constraints, joins) fits a domain with clear entity relationships better than a document store would.
- **Industry ubiquity** — Postgres is the default relational database in most production Spring Boot stacks, so learning it transfers directly to real engineering roles.
- It pairs naturally with Flyway for version-controlled schema evolution, which a NoSQL store wouldn't need in the same way.

### Reasons for using Docker

- **Reproducibility** — the entire local dev environment (Postgres, pgAdmin, eventually the app itself) is described in one `docker-compose.yml`, so it behaves identically regardless of host machine.
- **Isolation from the host** — nothing is installed natively on Windows/WSL; Postgres versions can't drift from what's declared in code.
- **Production parity** — containers are how real backend services are deployed today (directly, or via Kubernetes). Learning Docker now is learning the deployment unit the industry actually uses.
- **Easy reset** — a broken local environment can be torn down and rebuilt from the compose file in seconds, rather than debugging a polluted native install.

Full rationale for the specific compose decisions (healthchecks, named volumes, image pinning, etc.) is in `docs/sprint-1-infrastructure.md`.

### Reasons for using Layered Architecture

The codebase is organized into `controller` → `service` → `repository` layers (plus supporting `model`, `dto`, `mapper`, `config`, `security`, `exception`, `validator`, `util`, `constants` packages), because:

- **Separation of concerns** — HTTP concerns (controllers), business rules (services), and persistence (repositories) change for different reasons and at different rates. Mixing them makes both testing and reasoning harder.
- **Testability** — services can be unit-tested with mocked repositories; controllers can be tested independently of business logic.
- **Industry standard** — this is the default architecture for Spring Boot applications and the one most engineers will recognize and expect when reading the code.
- **Low coupling / high cohesion** — each layer depends only on the abstraction below it (e.g., service depends on a repository interface, not a JDBC implementation), which is what makes the system maintainable as it grows.

Layered architecture is chosen over a more elaborate Clean/Hexagonal Architecture (ports & adapters) for now because the added indirection isn't justified yet at this project's size — but the dependency direction (outer layers depend on inner ones, not vice versa) is kept compatible with evolving toward that later if the domain logic grows complex enough to warrant it.

### Reasons for postponing the React frontend until the backend is stable

- **Contract-first development** — the backend's REST API is the contract every future client (React web app, a future mobile app, third-party integrations) depends on. Building it first, and getting it right (resources, status codes, error shapes, auth flow), avoids reshaping a contract that a frontend has already been built against.
- **Learning focus** — the stated goal of this project is backend engineering depth. Splitting attention across a frontend before the backend fundamentals (security, migrations, layered design, testing) are solid would dilute that.
- **Independent verification** — Swagger/OpenAPI and tools like Postman/curl can fully exercise and validate the backend without a UI, so a frontend isn't required to prove the backend works.
- **Reduced coupling during early instability** — the backend schema and API shape are expected to change frequently in early sprints (entities, auth, ticketing flow all still being designed). Building a frontend against a moving target creates rework in both places simultaneously.

---

### Admin authentication (Sprint 6)

`ROLE_ADMIN` finally got a live login path, built the same way as `ROLE_CONDUCTOR` (Sprint 3): a **separate `Admin` entity** (not `User` with a role flag — admin has no wallet, QR token, or `deviceId`, so the field sets don't overlap), a separate `AdminPrincipal`/`AdminDetailsServiceImpl`, and a `"ADMIN"` role claim in the JWT. `JwtAuthenticationFilter` now resolves one of **three** principal types per request off that claim (`PASSENGER` → `UserDetailsServiceImpl`, `CONDUCTOR` → `ConductorDetailsServiceImpl`, `ADMIN` → `AdminDetailsServiceImpl`), each injected as a concrete class to avoid `NoUniqueBeanDefinitionException`. Every pre-existing `/admin/**` endpoint (built in Sprint 3, unreachable until now) works with an admin token; the gap noted at Sprint 3/4/5 close is closed.

### Caching strategy (Sprint 6)

**Problem:** two conductor-facing reads — the full stop list for a route (`GET /routes/{id}/stops`) and the per-stage fare between two stops (`GET /routes/{id}/fare`) — hit Postgres on every call, but the underlying data (route topology, `farePerStage`) changes only on rare admin edits.

**Choice:** Redis behind Spring's cache abstraction (`@Cacheable`/`@CacheEvict`), not hand-rolled caching or a read replica. Spring's abstraction keeps the caching declarative and out of the business logic; Redis (over an in-process cache like Caffeine) because it survives app restarts and is the same cache the future multi-instance deployment will need. A read replica is overkill for two endpoints with a tiny working set.

**Shape:**
- `route-stops` cache — keyed by `routeId`, holds `List<RouteStopResponseDTO>`. Evicted on `addStop(routeId)` (key-scoped) and `updateRoute` (all entries).
- `fare-calc` cache — keyed by `routeId + origin + destination`, holds a `FareRateDTO` (the per-stage rate, **no passenger-count-dependent `totalFare`**). `calculateFare` recomputes the total per request from the cached rate, so one entry serves every passenger mix. Evicted on `updateRoute` (all entries — a `farePerStage` change invalidates every stored rate).
- 1h TTL, JSON values (readable in `redis-cli`, no Java-serialization coupling).

**Two non-obvious implementation points** (full writeup in `docs/sprints/Sprint-06.md`):
1. `@Cacheable` is Spring-AOP-proxy-based, so a call from *inside* the same bean bypasses it. `calculateFare` calls the cached `getFareRate` via `self` — the bean's own proxy, injected back into `FareServiceImpl` with `@Lazy` to break the self-referential constructor cycle. The alternative (`AopContext.currentProxy()`) needs `exposeProxy = true` and is generally considered the worse smell.
2. On Boot 4.1 / Jackson 3, a generic JSON serializer deserializes the cached `List<RouteStopResponseDTO>` back to `List<LinkedHashMap>` (erased generic). `RedisConfig` registers a per-cache `JacksonJsonRedisSerializer` built with an explicit `JavaType` for the concrete element type.

Redis is used **as a cache only** — never as a `@RedisHash` repository store. (`spring-boot-starter-data-redis` auto-enables Redis-repository scanning, which logs harmless "could not identify store" noise at startup; candidate fix `spring.data.redis.repositories.enabled=false`, noted not actioned.)

---

## Current Architecture Diagram

```
                     ┌─────────────────────────────────────────┐
                     │   Clients: Postman / curl / Swagger UI    │
                     │   (passenger, conductor, admin JWTs)      │
                     └──────────────────┬──────────────────────┘
                                        │ HTTP :8080
                                        ▼
                       ┌──────────────────────────────────┐
                       │   Spring Boot App  (mvnw run)      │
                       │   controller → service → repo      │
                       │   Spring Security + JWT filter     │
                       │   (3 principal types by role claim)│
                       └───┬──────────────┬─────────────┬───┘
             cache (Redis) │              │ JDBC        │ HTTPS (SDK)
                           ▼              ▼             ▼
                 ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
                 │  Redis        │ │  PostgreSQL   │ │  Razorpay (test) │
                 │  :6379        │ │  :5432        │ │  + webhooks via  │
                 │  route-stops, │ │  vol:         │ │  ngrok (dev)     │
                 │  fare-calc    │ │  postgres_data│ └──────────────────┘
                 └──────────────┘ └───────┬──────┘
                                          │
                                   ┌──────┴──────┐
                                   │  pgAdmin     │
                                   │  :5050       │
                                   └─────────────┘
```

`postgres`, `pgadmin`, and `redis` run locally via Docker Compose (root
`docker-compose.yml`, `buslink_network`). The Spring Boot app runs on the host
(`./mvnw spring-boot:run`, env from `infrastructure/.env`) — app containerization
is deferred (Sprint 8 candidate). Razorpay is an external test-mode service;
webhook delivery in dev is tunnelled through ngrok (Sprint 5). Flyway is listed
in Technology Choices but not yet wired — schema is still `ddl-auto=update`.

---

## Future Architecture Roadmap

The following are anticipated additions, roughly in the order the domain requires them. Each will get its own architectural decision (problem → alternatives → chosen approach) when its sprint begins, not designed in detail upfront.

- **Authentication** — Spring Security + JWT for stateless API auth; likely role-based (rider vs. admin) from the start given the Admin Portal below. **Update (2026-07-08):** a second login method — mobile number + OTP — is planned alongside email/password, for riders who prefer it over remembering a password. `User.mobileNo` (Sprint 1, S1-10) was already added unique specifically to support this later. Sprint 2's email/password flow (`UserDetailsServiceImpl.loadUserByUsername(email)`, login/register DTOs) is built as one specific auth method, not the only one the system will ever support — OTP login is a parallel lookup/flow (by `mobileNo` instead of `email`) and a new endpoint/DTO, added in a future sprint once OTP delivery (SMS gateway) is in scope. No Sprint 2 rework anticipated; `JwtUtil` issues tokens off a `User`, independent of how they authenticated. **Update (2026-07-26, Sprint 3):** role-based auth landed — `ROLE_CONDUCTOR` added as a second, fully separate principal/auth path (`ConductorPrincipal`, `ConductorDetailsServiceImpl`, `POST /conductor/auth/login`) alongside `ROLE_PASSENGER`, plus `ROLE_ADMIN` wired into `SecurityConfig`'s rules. `JwtUtil` now embeds a `role` claim so `JwtAuthenticationFilter` can load the correct principal type per request. **`ROLE_ADMIN` has no live login path yet** — `/admin/**` is correctly secured and rejects unauthenticated requests, but nothing can mint an admin JWT (no `Admin` entity/principal). Sprint 3's seed data (`DataSeeder`) bypasses the HTTP/auth layer entirely rather than going through the (currently unreachable) admin API. Real admin auth — same recipe as conductor auth — is future scope, Sprint 4+. **Update (2026-09-06, Sprint 6):** landed — `Admin` entity + `AdminPrincipal` + `AdminDetailsServiceImpl`, `POST /admin/auth/login` → `ROLE_ADMIN` JWT, seeded `admin@buslink.com`. `JwtAuthenticationFilter` now does a 3-way principal resolution off the role claim. Every `/admin/**` endpoint is reachable with an admin token. `DataSeeder` still seeds bypassing HTTP (it now seeds the admin account too). See the "Admin authentication (Sprint 6)" section above.
- **Ticketing** — the core domain: routes, trips, fares, ticket issuance and lifecycle (issued → validated → expired). **Update (2026-07-06):** the fare service will need the real stop topology of a route (an ordered sequence of stops, each at a known stage/position) to compute a fare between any two stops a conductor selects. `Route` (Sprint 1, S1-13) deliberately only stores `originStop`/`destinationStop` for now — no stop-sequence modeling exists yet. This is scoped to whenever the Fare Service sprint begins, not designed upfront. **Update (2026-07-26, Sprint 3):** the real stop topology landed — `RouteStop` (ordered `stopSequence` + fare-relevant `stageNumber` per stop), with `Route.originStop`/`destinationStop`/`totalStops` kept as denormalized fields updated wherever a stop is added (`createRoute`, `addStop`), not just read once at creation. `FareServiceImpl.calculateFare` computes `stagesCrossed`/`adultFare`/`childFare`/`totalFare` off this topology. Ticket issuance itself (consuming this fare calculation to actually create a `Ticket`) remains Sprint 4 scope — this sprint only built the fare *engine*, not the issuance flow that will call it. **Update (2026-07-30, Sprint 4):** ticket issuance landed — `POST /tickets/issue` (`ROLE_CONDUCTOR`) reuses `TicketServiceImpl`'s own fare calculation (duplicated from `FareServiceImpl`'s logic rather than calling it directly, since issuance needs the resolved `RouteStop`/`Route` entities mid-flow for its own validation chain, not just a fare number), validates the full conductor→bus→route→stops chain, and creates the `Ticket` in `ISSUED` status — idempotent via a client-supplied `X-Idempotency-Key` header, backed by a new `IdempotencyKey` entity/table (24-hour TTL, configurable via `ticket.idempotency.ttl-hours`). `PUT /tickets/{ticketId}/terminate` lets a conductor void an unpaid ticket (`TicketStatus.TERMINATED`, a new value distinct from `CANCELLED`). The ticket-expiry scheduler (auto-expiring old unpaid tickets) remains Sprint 7 scope — `terminate` is a manual conductor action, not automatic expiry.
- **Payments** — integration with a payment gateway; will need idempotency handling so a network retry can't double-charge or double-issue a ticket. **Update (2026-07-06):** the `Payment` entity's shape was created early, in Sprint 1 (`docs/sprints/Sprint-01.md`, S1-31), once wallet top-up and direct non-wallet fare payment (UPI/card) both needed representing. Only the persistent shape exists — no gateway integration, webhook handling, or idempotency logic yet; that remains future work as described here. **Update (2026-07-30, Sprint 4):** wallet-based payment landed — `POST /payments/wallet` (`ROLE_PASSENGER`) debits `Wallet.balance` (optimistic-lock-protected via the `@Version` field added in Sprint 1, exercised in practice for the first time this sprint), allows going negative up to a configurable overdraft limit (`wallet.overdraft-limit`, default ₹100), records a `DEBIT` `Transaction`, and moves the `Ticket` to `PAID`. `Payment` (the external-gateway entity) is still untouched — this flow is wallet-only. Gateway integration (UPI/card), wallet recharge, and overdraft recovery-on-recharge all remain Sprint 5 scope. **Update (2026-08-23, Sprint 5):** real gateway integration landed — Razorpay (test mode), abstracted behind a `PaymentGatewayPort` interface (`gateway/` package) so `PaymentServiceImpl`/`WebhookServiceImpl` never depend on the Razorpay SDK directly; `RazorpayGatewayAdapter` (`gateway/impl/`) is the only class in the codebase that does. `POST /payments/recharge/initiate` and `POST /payments/ticket/upi/initiate` (both `ROLE_PASSENGER`) create a Razorpay order and a `PENDING` `Payment` row; the actual money movement only happens once a signed webhook (`POST /webhooks/razorpay`, `permitAll()` — Razorpay itself carries no JWT, security is the gateway port's HMAC signature check instead) confirms success or failure. On recharge success, overdraft is auto-recovered first (an explicit DEBIT `Transaction` reversing the negative balance, then a CREDIT for the full recharge — see `Sprint-05.md`'s "Pre-existing gaps found during plan review" for why both reuse `payment.getPaymentId()` as `referenceId`). On ticket-payment success, the `Ticket` moves straight to `PAID` with no wallet/`Transaction` involvement at all, since the money never touched the wallet.
  **Design lesson worth keeping in mind for any future gateway work:** a payment gateway's idempotency unit is often the individual *attempt*, not the logical *order* — Razorpay retries a declined checkout against the same order, sending a separate webhook per attempt. A live-verification pass in Sprint 5 (S5-21) caught the idempotency guard treating any non-`PENDING` `Payment` status as final, which silently dropped a real success webhook that arrived after an earlier attempt's failure webhook had already marked the row `FAILED`. Fixed by only treating `SUCCESS` as terminal. Full writeup in `Sprint-05.md`/`DEVELOPMENT_LOG.md`.
- **QR validation** — generating a verifiable QR per ticket and a fast validation endpoint for bus-side scanning; likely the first candidate for extraction into its own service if load/latency demands independent scaling.
- **Analytics** — reporting on ridership, revenue, and route usage; likely read-heavy and may eventually warrant a read replica or separate reporting store rather than querying the transactional database directly. **Update (2026-09-06, Sprint 6):** a first cut landed — 4 `GET /admin/analytics/*` endpoints (`revenue-by-route`, `tickets-per-day`, `top-routes`, `conductor-activity`) backed by JPQL aggregate `@Query` methods on `TicketRepository` (`Object[]` projections), querying the transactional `ticket` table directly. No pagination, no date filters, N+1 route/conductor-name enrichment in the service layer — all acceptable for an infrequently-hit single-admin endpoint. A separate reporting store / read replica stays on this roadmap for when analytics grows beyond a handful of full-table aggregates.
- **Notifications** — ticket confirmations, trip reminders; async by nature (likely a message queue rather than synchronous calls from the ticketing flow).
- **Admin Portal** — internal-facing UI/APIs for managing routes, fares, and viewing analytics; distinct auth/authorization needs from the rider-facing API. **Update (2026-09-06, Sprint 6):** the *API* side is now functional — admin auth + all `/admin/**` route/bus/conductor endpoints + `/admin/analytics`. Passenger management endpoints were explicitly dropped from Sprint 6 as low-value. A UI is still future (bundled with the React frontend).
- **Future React frontend** — a rider-facing web client built once the API contract is stable enough to build against without constant breakage.
- **Future deployment architecture** — moving from local Docker Compose to a real deployment target (e.g., a single VM with Compose as a first step, then likely a managed container platform or Kubernetes if/when multiple services exist).
- **Cloud deployment considerations** — managed Postgres vs. self-hosted, secrets management (replacing local `.env` files with a proper secrets manager), observability (logging/metrics/tracing), and CI/CD for automated build-test-deploy.

This roadmap will be revisited and refined as each sprint completes — treat it as directional, not a fixed spec.
