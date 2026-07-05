# PROJECT_CONTEXT.md

This document always represents the **current state** of the project. Update it whenever a sprint finishes.

## Project

BusLink

## Current Sprint

Sprint 1

## Current Objective

Establish complete backend development infrastructure before writing application code.

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

Per `docs/sprints/Sprint-01.md` (the active sprint file — source of truth for scope): generate the Spring Boot project, scaffold the flat package structure, create all JPA entities, connect to PostgreSQL, and verify the app starts with all tables created. No business logic, REST APIs, auth, or Flyway (Flyway is explicitly deferred to post-LLD).

S1-01, S1-02, S1-03 complete. Remaining: S1-04 through S1-30.

## Notes

- No business logic has been implemented yet.
- No entities have been created.
- No REST APIs have been developed.
- No authentication exists yet.
- `backend/` is currently untracked in git — not yet pushed to GitHub (S1-04 pending).
