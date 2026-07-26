# PROJECT_CONTEXT.md

This document always represents the **current state** of the project. Update it whenever a sprint finishes.

## Project

BusLink

## Current Sprint

Sprint 3

## Current Objective

Sprint 3 is closed. Introduced the Route domain end-to-end: updated `Route`/`Ticket`
entities, a new `RouteStop` entity, conductor auth (`ROLE_CONDUCTOR`, separate from
passenger), role-based `SecurityConfig`, a full Route/Fare/Bus service layer, admin
and conductor-facing controllers, Route 500K seed data, unit tests, and Postman
verification.

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

## Current Architecture

Backend only.

Frontend postponed.

Spring Boot 4.1.0 (see `docs/ARCHITECTURE.md` for the version-bump rationale).

## Current Database

PostgreSQL.

## Current Infrastructure

Docker Compose.

## Next Planned Milestone

Sprint 4 — not yet planned. Likely scope: ticket issuance flow and wallet deduction
(both explicitly out of Sprint 3 scope), possibly real admin authentication (no
`ROLE_ADMIN` login path exists yet — see Notes below). Payment flows remain Sprint 5,
Redis caching Sprint 6, Flyway remains deferred. Sprint plan to be drafted in Notion,
then `docs/sprints/Sprint-04.md` generated once approved.

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
