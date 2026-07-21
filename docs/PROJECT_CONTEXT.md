# PROJECT_CONTEXT.md

This document always represents the **current state** of the project. Update it whenever a sprint finishes.

## Project

BusLink

## Current Sprint

Sprint 2

## Current Objective

Implement JWT-based authentication for passengers: repository/service layer carried over
from Sprint 1, QR token generation at registration, and the first secured REST endpoints
(register, login, refresh, profile, QR fetch).

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

## Current Architecture

Backend only.

Frontend postponed.

Spring Boot 4.1.0 (see `docs/ARCHITECTURE.md` for the version-bump rationale).

## Current Database

PostgreSQL.

## Current Infrastructure

Docker Compose.

## Next Planned Milestone

Sprint 3 — not yet planned. Scope will cover conductor auth and role-based endpoint
security beyond `ROLE_PASSENGER` (both explicitly out of scope for Sprint 2); wallet
recharge/payment flows remain Sprint 5, Flyway remains deferred. Sprint plan to be
drafted in Notion, then `docs/sprints/Sprint-03.md` generated once approved.

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
