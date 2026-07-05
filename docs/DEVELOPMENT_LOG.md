# DEVELOPMENT_LOG.md

This document records every significant milestone throughout the BusLink project. Append to it after every completed feature, sprint, or milestone.

---

# Sprint 0

## Development Environment

Completed

- Installed WSL Ubuntu 24.04
- Configured Linux development environment
- Installed Java 21
- Installed Maven
- Installed Git
- Configured GitHub SSH Authentication
- Installed Node.js LTS
- Installed npm
- Installed Claude Code
- Installed Docker Desktop
- Enabled Docker WSL Integration
- Configured IntelliJ to use WSL
- Created Developer workspace
- Created BusLink project structure

---

# Sprint 1

## Infrastructure

Completed

- Created Docker Compose
- Started PostgreSQL
- Started pgAdmin
- Verified database connectivity

Next

- ~~Generate Spring Boot project.~~ Done — see below.

## Spring Boot Project Generation

Completed

- Generated Spring Boot project via Initializr into `backend/` (Java 21, Maven, group `com.buslink`, artifact `buslink-backend`)
- Bumped target framework version from planned Spring Boot 3.x to **4.1.0** — 3.x had become the trailing legacy line by generation time; see `docs/ARCHITECTURE.md` for full rationale and resulting stack changes (starter renames, springdoc 3.0.3)
- Added Sprint 1 dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-security`, `lombok`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui:3.0.3`
- Verified clean build: `./mvnw clean install` succeeded, jar produced

Next

- Push `backend/` to GitHub (`buslink-backend` repo), define branch strategy (S1-04, S1-05).
