# ApnaTutor — Roadmap

**Shape and reasoning per milestone — what each one is for and when it is finished.** Deliberately no checkboxes below M0: duplicated status lists drift, and then nobody trusts either one.

| File | Owns |
|---|---|
| [SOURCE_OF_TRUTH.md](./SOURCE_OF_TRUTH.md) | Rules, numbers, data model, decision log — canonical |
| **PLAN.md** (this file) | Milestone shape, rationale, done-criteria |
| [TASKS.md](./TASKS.md) | **Task status.** The only place a box gets ticked |
| [PENDING.md](./PENDING.md) | Open decisions, blockers, debt, risks |
| [PROGRESS.md](./PROGRESS.md) | Dated journal of what actually shipped |

**Goal of v1:** a parent posts a tuition requirement for free; matching tutors see it with contact details masked; a tutor spends credits to unlock it; ApnaTutor earns from credit-package sales. No lesson payments, no scheduling, no chat.

---

## M0 — Foundation

- [x] Install Node 24.19.0 LTS, Git 2.55.0
- [x] Install PostgreSQL 18.6 (native Windows service `postgresql-x64-18`), `psql` on PATH
- [x] Backend scaffold — Spring Boot 4.1.1, Java 26, Maven wrapper
- [x] Frontend scaffold — Next.js 16.3.3, React 19.2.8, TypeScript, Tailwind 4
- [x] `scripts/db-setup.sql` — role `apnatutor`, databases `apnatutor_dev` + `apnatutor_test`, `pg_trgm`
- [x] `scripts/db-reset.ps1` — drop, recreate, re-migrate
- [x] `application.yml` + test profile, single repo-root `.env` read by both apps
- [x] Flyway baseline migration `V1__baseline.sql` (`pg_trgm`, `set_updated_at()` trigger fn)
- [x] `SecurityConfig` — default-deny, public health/auth/docs routes, CORS, BCrypt
- [x] Health check reachable from the frontend (proves the whole chain)
- [x] `git init`, `.gitignore`, `.env.example`
- [x] `CLAUDE.md` + the three `docs/` files
- [ ] Global error handler, `ApiError` shape, pagination wrapper — *deferred to M1, where the first real endpoints need them*
- [ ] springdoc-openapi at `/swagger-ui` — *deferred to M1, nothing to document yet*

**Done when:** ~~`mvnw verify` is green, `npm run build` is clean, backend `/actuator/health` reports `db: UP`, and the frontend renders data fetched from the backend.~~ ✅ **All four verified 2026-08-31.**

---

## M1 — Accounts, profiles & trust
`M1-01` … `M1-12` · [tasks](./TASKS.md#m1--accounts-profiles--trust)

Everything needed before there is anything to search for. Phone-first identity (OTP, JWT, refresh rotation), the catalog that profiles hang off, tutor and student profiles, file upload, and the admin verification ladder.

**Contains the deferred M0 plumbing** (`M1-01`) — error shape, global handler, pagination, springdoc, idempotency infrastructure. Built now because there are finally real endpoints to shape it against.

**Catalog moved here from M2** (`M1-06`). A tutor profile cannot be built without subjects, boards, grades and locations to attach to — "what do you teach, and where" is the centre of the onboarding wizard. Leaving it in M2 would have meant throwaway scaffolding.

**Done when:** a tutor registers by phone, completes a full profile, uploads an ID, and an admin approves it — end to end in the browser.

---

## M2 — Discovery & SEO
`M2-01` … `M2-08` · [tasks](./TASKS.md#m2--discovery--seo)

Now purely about being found: search and filters, the contact-masking boundary, public tutor pages, and the programmatic city×subject landing pages that are the primary acquisition channel.

The masking work (`M2-03`) deserves disproportionate care. Contact details *are* the product — a leak does not degrade the business model, it removes it.

**Done when:** search returns correct, fast results (`EXPLAIN ANALYZE` clean on the hot path) and a city×subject page renders complete HTML with JavaScript disabled.

---

## M3 — Requirements & the lead loop
`M3-01` … `M3-11` · [tasks](./TASKS.md#m3--requirements--the-lead-loop)

The core transaction, and the milestone the product lives or dies on. Requirements, lead pricing locked at creation, the masked lead feed, and the unlock endpoint.

**Credit ledger moved here from M4** (`M3-05`). Unlocking spends credits, so it cannot be built or tested without a wallet and ledger — splitting them across milestones would have left M3 unfinishable. Admin credit grants let the whole loop be exercised before any payment code exists.

**Done when:** the full loop works, and a concurrency test proves the cap of 5 holds under parallel unlocks with no loser charged.

---

## M4 — Credits & payments
`M4-01` … `M4-08` · [tasks](./TASKS.md#m4--credits--payments)

Money coming in: packages, Razorpay checkout, the signature-verified webhook, signup bonus, expiry, refunds and invoices.

The whole milestone reduces to one property — **credits are granted exactly once**, whatever the network does.

**Done when:** a test-mode payment grants credits exactly once even if the webhook fires three times, and a replayed ledger reconciles to the cached balance.

---

## M5 — Reviews, admin & platform trust
`M5-01` … `M5-10` · [tasks](./TASKS.md#m5--reviews-admin--trust)

Reviews with moderation, the full admin console, rate limiting, audit logging, abuse reporting, and DPDP data rights.

Note `M5-10.3`: account deletion must anonymise ledger rows, never delete them. Financial history has to survive a departed user.

**Done when:** an admin can run the marketplace end to end without touching the database.

---

## M6 — Polish & launch

`M6-01` … `M6-10` · [tasks](./TASKS.md#m6--polish--launch)

Making it usable by a real parent on a mid-range Android phone, then shipping it: responsive and accessibility passes, demo data, the automated money-path E2E, performance, deployment, observability, legal pages, and swapping every console stub for a real provider.

**Done when:** the money path passes end to end against a staging deploy on a real domain, and `M6-10.5` confirms no dev stub survives into production.

---

## v2 backlog (deliberately deferred)

In-app chat · calendar & lesson booking · lesson payments with escrow + commission · tutor payouts · video classes · coaching-institute accounts · lesson packages · Q&A and articles content hub · mobile apps · Elasticsearch · ranking/recommendations · referral programme · multi-role accounts

The two SoT invariants — append-only ledger, generic engagement record — exist so this list is additive rather than a rewrite.

---

## Open questions

Moved to [PENDING.md §1](./PENDING.md), alongside blockers, deliberate debt and risks, so that everything unresolved lives in one place.
