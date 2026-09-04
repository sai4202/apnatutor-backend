# ApnaTutor backend

Spring Boot REST API for the ApnaTutor tutor marketplace. **Self-contained** — everything this project needs is inside `backend/`. Never reference a file outside this directory.

Product rules live in `../docs/SOURCE_OF_TRUTH.md` (canonical), task status in `../docs/TASKS.md`.

## Commands

All from `backend/`. There is no global Maven — always use the wrapper.

```powershell
.\mvnw.cmd spring-boot:run     # API on :8080
.\mvnw.cmd verify              # unit + integration tests
.\mvnw.cmd clean compile       # fast compile check
.\scripts\db-setup.sql         # via psql -U postgres -f scripts\db-setup.sql
.\scripts\db-reset.ps1         # wipe apnatutor_dev + replay migrations
```

## Environment

- **Windows + PowerShell.** `&&` does not chain — use `;` or `if ($?) { }`.
- **Java 26**, not an LTS (ADR #4). Fallback is Temurin 21 if a bytecode-manipulating library breaks.
- **PostgreSQL 18 native Windows service**, no Docker (ADR #5). Databases `apnatutor_dev` and `apnatutor_test`, role `apnatutor`/`apnatutor`, superuser password `postgres`. Local only.
- **No Testcontainers** — it needs Docker. Integration tests extend `AbstractIntegrationTest`, which runs against the real `apnatutor_test` database.
- Config comes from `backend/.env`, loaded by `spring.config.import` in `application.yml`. Add any new variable to `.env.example` in the same commit.

## Spring Boot 4 gotchas

Boot 4 renamed things, so most tutorials and Boot 3 answers will not match this `pom.xml`. Check it before assuming a dependency name.

- Web starter is `spring-boot-starter-webmvc`, **not** `spring-boot-starter-web`.
- Test support is split per-starter (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, …) instead of one `spring-boot-starter-test`.
- **`CorsConfigurationSource` cannot be injected by type** — `mvcHandlerMappingIntrospector` also implements it, so injection fails with `NoUniqueBeanDefinitionException`. Call the `corsConfigurationSource()` bean method directly. Watch for the same trap with other types Spring MVC implements incidentally.
- **Jackson 3 fails on null for primitives.** Boot 4 ships Jackson 3, which flipped `FAIL_ON_NULL_FOR_PRIMITIVES` to `true`. An omitted `boolean` or `int` in a request body is now a hard parse error, surfacing as `MALFORMED_REQUEST` rather than defaulting to `false`/`0` as it did under Jackson 2. **Box the primitives in request DTOs** (`Boolean`, `Integer`) and default them explicitly in the service — that keeps "absent" representable without disabling the check globally.
- **springdoc must be 3.x**, pinned via the `springdoc.version` property. The 3.x line is the Boot 4 line; 2.x targets Boot 3 / Framework 6 and will not work here. Most tutorials still say 2.x.
- **Checking whether a dependency version exists:** read `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml`. The `search.maven.org/solrsearch` API caches `latestVersion` and lags real releases — it already caused one wrong "unavailable" call on this project.

## Rules that are not negotiable

Explained in `../docs/SOURCE_OF_TRUTH.md` §4. They exist so v2 (booking, lesson payments, commission) stays additive instead of becoming a rewrite.

1. **`credit_transactions` is append-only.** No UPDATE, no DELETE. `credit_wallets.balance` is a cache; the ledger is authoritative. Any disagreement means the ledger is right.
2. **`lead_unlocks` is a generic engagement record** carrying `engagement_type`. A v2 booking is another type on the same connection graph.

Plus:

- **Money is stored in paise as `BIGINT`.** Never float. Column names end in `_paise`. Credits are integers, never fractional.
- **Flyway migrations are forward-only.** Never edit an applied migration — write a new one. `ddl-auto` is `validate` everywhere, including local, so entity/schema drift fails the build.
- **Entities never leave the service layer.** Controllers speak DTOs (records).
- **Cross-module calls go through service interfaces**, never another module's repository or entity.
- **Every error response carries an `ErrorCode`.** Never a bare status. `ErrorCode` names are API contract — adding is free, renaming is breaking.
- **Never leak internals in an error response.** Stack traces, SQL and class names are logged, never returned.
- **Secrets come from env vars only.** Nothing secret is committed.
- `unlock_cost_credits` is **locked onto the requirement at creation**. Repricing must never move the cost of a lead a tutor is already looking at.

## Module layout

`src/main/java/com/apnatutor/` — one package per bounded module: `auth`, `user`, `catalog`, `search`, `requirement`, `lead`, `billing`, `review`, `verification`, `notification`, `settings`, `storage`, `audit`, `admin`, `common`. Each has `controller / service / repository / domain / dto`.

`audit` is the one every module may depend on and which depends on none of them: it records what admins do, so putting it inside `admin` would invert the dependency for the services in `user`, `requirement` and `billing` that describe their own changes to it.
