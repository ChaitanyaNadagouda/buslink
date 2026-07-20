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

Per `docs/sprints/Sprint-02.md` (the active sprint file — source of truth for scope): carry
over `UserRepository`/`WalletRepository`/`UserServiceImpl` stub from Sprint 1 (S1-28/S1-29),
then build JWT infrastructure (`JwtUtil`, `JwtAuthenticationFilter`,
`UserDetailsServiceImpl`), lock down `SecurityConfig`, implement the passenger auth flow
(register/login/refresh), QR token generation, and `UserController`
(`/user/profile`, `/user/qr`). Out of scope: conductor auth (Sprint 3), role-based security
beyond `ROLE_PASSENGER` (Sprint 3), wallet recharge/payment flows (Sprint 5), Flyway
(deferred).

**Sprint 1 — closed (2026-07-08).** All Definition of Done items verified, including the
final one (9 tables visually confirmed in pgAdmin — previously only checked via Hibernate's
DDL log). S1-28/S1-29 (repository + service stub) moved to Sprint 2, to be built together
with the rest of the auth flow as one coherent unit rather than a stub built twice.

## Notes

- Sprint 1 delivered infrastructure only — no business logic, no REST APIs, no auth exist
  yet. `SecurityConfig` is still the Sprint 1 permit-all stub; Sprint 2's S2-05 replaces it.
- All 9 entities created and verified in Postgres: `User`, `Wallet`, `Transaction`, `Payment`, `Route`, `Bus`, `Conductor`, `Ticket`, `SyncEvent` — confirmed via both Hibernate DDL log and a visual pgAdmin check, app starts clean, `ddl-auto=update`.
- Swagger UI verified working (credential-free) at `/swagger-ui/index.html`.
- `backend/` (and `docs/`, `infrastructure/`) committed and pushed to GitHub (`buslink` monorepo); Sprint 1 work merged from `feature/project-setup` into `dev` (`ae3d60e`); branch strategy (`main` → `dev` → `feature/*`) is in place. Sprint 2 work will land on a new `feature/auth` branch (S2-24).
