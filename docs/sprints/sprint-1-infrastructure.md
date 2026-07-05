# Sprint 1 — Development Infrastructure

## Goal

Stand up a reproducible local development environment: PostgreSQL + pgAdmin via
Docker Compose, followed by a Spring Boot skeleton that connects to it. Nothing
runs on the host directly — everything is containerized.

## Directory structure

```
buslink/
├── backend/          # Spring Boot application (Sprint 1, next step)
├── infrastructure/    # Env config and anything deployment-related
├── docs/              # Architecture notes, ADRs, sprint write-ups (this file)
├── scripts/           # Helper shell scripts
├── docker-compose.yml
├── .gitignore
└── README.md
```

Separating infra, docs, and app code at the top level means every future file
has an obvious home instead of accumulating ad hoc in the root.

## `.gitignore`

Excludes `infrastructure/.env` (secrets), IDE files (`.idea/`, `*.iml`),
compiled Java output (`target/`, `*.class`), and logs.

The most common beginner mistake in backend projects is committing secrets to
Git history. Once a credential is in a commit it stays in history forever
unless it's rewritten — so this discipline has to exist *before* the first
commit, not be bolted on after. IDE/build artifacts are excluded because
they're machine-specific and regeneratable; committing them just creates
noisy diffs and merge conflicts.

## `infrastructure/.env` and `infrastructure/.env.example`

Two files, same variable names, different purpose:

- **`.env`** — real local credentials, gitignored, exists only on this machine.
- **`.env.example`** — same variable names with empty values, **committed**.

This is the [12-Factor App](https://12factor.net/config) "config via
environment" pattern. Credentials never belong in code or version control,
but anyone cloning the repo still needs to know *which* variables they must
supply to run it. `.env.example` is that contract — self-documenting
onboarding with zero secrets exposed.

## `docker-compose.yml`

Two services — `postgres` and `pgadmin` — on a shared Docker network, each
backed by a named volume.

### Why Docker Compose over alternatives

- **Plain `docker run`** — works for one container, but juggling network,
  volume, and env flags by hand for multiple containers is unmanageable and
  undocumented.
- **Kubernetes** — orchestrates production workloads across a cluster;
  overkill for one laptop running two containers.
- **Installing Postgres natively on Windows/WSL** — pollutes the host,
  versions drift from what would run in production containers, and it's
  painful to reset to a clean slate.

Compose wins: declarative (one YAML file = the whole environment), version
controllable, and the same mental model used by most real teams for local
dev dependencies before graduating to Kubernetes manifests for deployment.

### Image pinning

`postgres:16-alpine` and `dpage/pgadmin4:8.12` — never `:latest`.
Infrastructure-as-code should be reproducible. With `:latest`, a
`docker compose up` run months from now could silently pull a different
Postgres major version and break things in a way that's hard to diagnose.
Pinning means the file fully describes the environment on its own.

### `env_file:` vs `${VAR}` interpolation — a real gotcha

`env_file:` injects variables *into the running container* (Postgres sees
`POSTGRES_USER` and creates that user/database on first boot). `${VAR}`
written directly in the YAML is resolved by the Compose CLI itself, before
any container starts — and Compose only auto-reads a `.env` file for that
purpose if it sits at the project root next to `docker-compose.yml`. Since
our `.env` deliberately lives in `infrastructure/`, that auto-discovery
doesn't happen.

The fix applied here: port numbers are hardcoded directly in the compose
file (they're topology, not secrets), and the healthcheck uses
`$$POSTGRES_USER` (escaped `$`) so Compose passes the literal string through
untouched and the *container's own shell* expands it from the environment
`env_file` actually gave it.

### Healthcheck (`pg_isready`)

A container reports "running" the instant its process starts, not when it's
ready to accept connections. Postgres does real work on first boot
(initializing the data directory, creating the user/database). Without a
healthcheck, pgAdmin — or later, Spring Boot — could try to connect during
that window and fail with a confusing connection-refused error.

### `depends_on: condition: service_healthy`

Plain `depends_on` only waits for the container to *exist*, not to be
*ready*. Tying it to the healthcheck means pgAdmin genuinely waits until
Postgres can accept connections.

### Named volumes (`postgres_data`, `pgadmin_data`)

A container's writable filesystem is ephemeral — delete the container, lose
the data. Named volumes are managed by Docker independently of container
lifecycle, so `docker compose down` (which removes containers) leaves the
database intact; only `docker compose down -v` would wipe it. This is what
makes local dev safe to iterate on.

### Bridge network (`buslink_network`)

Compose gives each service DNS resolution by service name on this network —
pgAdmin connects to Postgres using hostname `postgres`, not `localhost` or
an IP. This mirrors how service discovery works in real containerized
deployments (Kubernetes does the same thing conceptually), so it's a good
habit even at local-dev scale.

### `restart: unless-stopped`

Containers survive a Docker daemon restart (e.g. after WSL/Docker Desktop
restarts) without needing to manually re-run `docker compose up`, but won't
auto-restart if deliberately stopped.

## Gotcha hit during first `docker compose up`

pgAdmin crash-looped on startup with:

```
'admin@buslink.local' does not appear to be a valid email address.
```

pgAdmin validates `PGADMIN_DEFAULT_EMAIL` with the `email_validator` library,
which rejects `.local` as a TLD even though `CHECK_EMAIL_DELIVERABILITY` is
disabled (delivery-checking off just skips the DNS/MX lookup, not basic
syntax/TLD validation). Fixed by using `admin@buslink.dev` instead.

Also worth noting: editing `.env` and running `docker compose restart` is
**not enough** — `restart` just restarts the existing container process, it
doesn't re-read `env_file`. Env vars are only applied at container
*creation*, so a change requires `docker compose up -d --force-recreate <service>`
(or `down` + `up`).

## Status at end of this note

- Directory structure, `.gitignore`, `.env`/`.env.example`, and
  `docker-compose.yml` are written.
- `docker compose up -d` run successfully — both containers healthy and verified:
  - Postgres: `psql` query executed against the `buslink` database (v16.14).
  - pgAdmin: HTTP 302 (redirect to login) on `localhost:5050`.
- Spring Boot project does not exist yet.
- Nothing has been committed to Git yet.
