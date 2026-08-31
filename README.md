# ApnaTutor — Backend

Spring Boot REST API for the ApnaTutor tutor marketplace.

A self-contained project: it builds, tests and runs entirely from this directory and needs nothing from the frontend.

## Stack

| | |
|---|---|
| Language | Java 26 |
| Framework | Spring Boot 4.1.1 (Web MVC, Security, Data JPA, Validation, Actuator) |
| Database | PostgreSQL 18, native install — **no Docker** |
| Migrations | Flyway, forward-only |
| Build | Maven via the bundled wrapper (no global Maven needed) |

## First-time setup

**1. Prerequisites** — a JDK (26 is what this targets) and PostgreSQL 18 with `psql` on your PATH.

**2. Create the databases.** From this directory:

```powershell
$env:PGPASSWORD = "postgres"
psql -U postgres -h localhost -f scripts\db-setup.sql
```

Creates the `apnatutor` role and the `apnatutor_dev` and `apnatutor_test` databases, with the `pg_trgm` extension on both. Idempotent — safe to re-run.

**3. Configure.** Copy `.env.example` to `.env` and adjust if your Postgres differs from the defaults. Generate a JWT secret with:

```powershell
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

**4. Run.**

```powershell
.\mvnw.cmd spring-boot:run
```

API on `http://localhost:8080`. Check it with `curl http://localhost:8080/actuator/health` — you want `status: UP` and `db: UP`.

## Testing

```powershell
.\mvnw.cmd verify
```

Integration tests run against the **real `apnatutor_test` database**, not an in-memory one. Testcontainers would be the usual choice, but this project has no Docker (ADR #5), so tests extend `AbstractIntegrationTest`, which activates the `test` profile and lets Flyway rebuild the schema from scratch each run.

This means the test database must exist on any machine that runs the suite — step 2 above handles it, and CI must do the same.

## Resetting the database

```powershell
.\scripts\db-reset.ps1            # apnatutor_dev
.\scripts\db-reset.ps1 -Database test
```

Drops and recreates the database so Flyway replays every migration from V1 on the next start. This is what proves migrations work from zero rather than only as increments against a long-lived local database.

## Layout

```
src/main/java/com/apnatutor/
├── auth/           OTP, JWT, roles, refresh tokens
├── user/           accounts, student & tutor profiles
├── catalog/        subjects, boards, grades, locations
├── search/         tutor search + filters
├── requirement/    student requirements
├── lead/           unlocks, caps, lead pricing
├── billing/        wallet, ledger, packages, Razorpay
├── review/         reviews + moderation
├── verification/   documents, badges, admin queue
├── notification/   email/SMS adapters
├── admin/
└── common/         errors, pagination, config, audit
```

Modules communicate through service interfaces only — never another module's repository or entity.

## Conventions

- Flyway migrations are **forward-only**. Never edit one that has been applied; write a new one. `ddl-auto` is `validate`, so any entity/schema drift fails the build.
- Money is stored **in paise as `BIGINT`**, never floating point. Credits are integers.
- Entities never leave the service layer — controllers speak DTOs.
- Every error response carries a stable `ErrorCode`; internals are logged, never returned.

Full rules and the decision log: [`../docs/SOURCE_OF_TRUTH.md`](../docs/SOURCE_OF_TRUTH.md).
