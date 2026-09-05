# ApnaTutor — V1 Task Tree

> **This file owns task status.** It is the one place a box gets ticked. PLAN.md describes the shape and reasoning of each milestone; PROGRESS.md is the dated journal; PENDING.md is the filtered view of what is open, blocked, or undecided. If those disagree with this file about whether something is done, **this file is right**.

**Legend:** `☑` done · `▶` in progress · `☐` not started · `⊘` blocked · `⏸` deferred

**Task IDs are permanent.** Reference them in commits (`M1-03: add OTP rate limiting`) and never renumber — a stale ID in git history is worse than a gap in the sequence.

| Milestone | Tasks | Done |
|---|---|---|
| M0 — Foundation | 12 | 12 ☑ |
| M1 — Accounts, profiles & trust | 12 | 8 ☑, 2 ▶ — **backend complete; frontend M1-11/M1-12 remain** |
| M2 — Discovery & SEO | 8 | 6 ☑, 2 ▶ |
| M3 — Requirements & the lead loop | 11 | 11 ☑ — **complete, front to back** |
| M4 — Credits & payments | 8 | 8 ☑ — **complete, front to back** |
| M5 — Reviews, admin & trust | 11 | 5 ☑ — **reviews, moderation, replies and rating aggregation complete** |
| M6 — Polish & launch | 10 | **deferred at your request** |
| **V1 total** | **72** (271 subtasks) | **50** |

---

## Two sequencing corrections to the original plan

Found while breaking the work down. Both are dependency inversions that would have caused a stall mid-milestone.

**1. Catalog moved from M2 → M1.** A tutor profile cannot be built without subjects, boards, grades and locations to attach to it — the onboarding wizard's central screen is "what do you teach, and where". Leaving the catalog in M2 would have meant either a half-built profile or throwaway scaffolding. Catalog schema and seed is now `M1-06`, before tutor profiles at `M1-08`. M2 keeps everything genuinely about *discovery*: search, masking, public pages, SEO.

**2. Credit ledger moved from M4 → M3.** The unlock endpoint spends credits, so it cannot be built or tested without a wallet and ledger. Splitting them across milestones would have left M3 unfinishable. The ledger and wallet are now `M3-05`; M4 keeps what is genuinely about *money coming in*: Razorpay, packages, invoices, refunds. Admin credit grants in `M3-05` let the whole M3 loop be tested before any payment code exists.

---

## M0 — Foundation ☑

Complete 2026-08-31, commit `02208c3`.

- ☑ `M0-01` Install Node 24.19.0 LTS + Git 2.55.0
- ☑ `M0-02` Install PostgreSQL 18.6 as native Windows service, `psql` on PATH
- ☑ `M0-03` Backend scaffold — Spring Boot 4.1.1, Java 26, Maven wrapper
- ☑ `M0-04` Frontend scaffold — Next.js 16.3.3, React 19.2.8, Tailwind 4
- ☑ `M0-05` `backend/scripts/db-setup.sql` — role, both databases, `pg_trgm`
- ☑ `M0-06` `backend/scripts/db-reset.ps1` — from-zero rebuild
- ☑ `M0-07` `application.yml` + test profile
- ☑ `M0-08` Per-project config — `backend/.env`, `frontend/.env.local` (ADR #11)
- ☑ `M0-09` `V1__baseline.sql` — `pg_trgm`, `set_updated_at()` trigger function
- ☑ `M0-10` `SecurityConfig` — default-deny, public allowlist, CORS, BCrypt
- ☑ `M0-11` Chain verified end to end: Next → Boot → Postgres all `UP`, server-rendered
- ☑ `M0-12` `git init`, per-project `.gitignore` / `.env.example` / `README.md` / `CLAUDE.md`, shared `docs/`

---

## M1 — Accounts, profiles & trust

**Goal:** a tutor registers by phone, builds a complete profile, uploads an ID, and an admin approves it.

### ▶ `M1-01` Shared web plumbing
*Deferred out of M0 deliberately — there were no endpoints to shape it against.*
- ☑ `M1-01.1` `ApiError` record + `ErrorCode` enum (stable machine codes, SoT §6)
- ☑ `M1-01.2` `@RestControllerAdvice` — validation, auth, forbidden, not-found, conflict, fallback
- ☑ `M1-01.3` `PageResponse<T>` wrapper matching the SoT pagination contract
- ☑ `M1-01.4` springdoc-openapi 3.1.0 + `/swagger-ui` — **unblocked**; 3.x is the Boot 4 line (2.x targets Boot 3)
- ☑ `M1-01.5` Correlation-ID filter + structured request logging
- ▶ `M1-01.6` `Idempotency-Key` infrastructure — **table done** (`V3__idempotency.sql`); interceptor still to write. Needed by M3-07 and M4-02, so it can wait until there is a money-moving endpoint to wrap.

### ☑ `M1-02` Identity schema
- ☑ `M1-02.1` `V2__identity.sql` — `users`, `otp_codes`, `refresh_tokens`
- ☑ `M1-02.2` Indexes: unique on `phone`, unique **case-insensitive partial** on `email`
- ☑ `M1-02.3` Attach `set_updated_at` trigger to each table
- ☑ `M1-02.4` JPA entities + repositories; enums as strings, never ordinals
- ☑ `M1-02.5` `ddl-auto: validate` passes — and immediately earned its keep by catching a `CHAR`/`VARCHAR` drift on `token_hash`

### ☑ `M1-03` OTP flow
- ☑ `M1-03.1` `SmsSender` interface + `ConsoleSmsSender` dev stub, selected by config
- ☑ `M1-03.2` `OtpService` — generate via `SecureRandom`, **BCrypt-hash before storing**, verify, consume
- ☑ `M1-03.3` Rate limits (SoT §3.4): 10 min TTL, 5 attempts/code, 5 sends/hour/phone
- ☑ `M1-03.4` `POST /auth/otp/request`, `POST /auth/otp/verify`
- ☑ `M1-03.5` Registration path: verify OTP on an unknown phone → create user with chosen role. Self-registering as `ADMIN` is refused.
- ☑ `M1-03.6` Tests: happy path, wrong code, replay, superseded code, attempt cap, send cap
- ☑ `M1-03.7` Enumeration defence — byte-identical response for registered and unregistered numbers, asserted by test
- ☑ `M1-03.8` `PhoneNumbers` E.164 normalisation, so one human cannot become two accounts by typing a space differently *(added — not in the original breakdown)*

### ☑ `M1-04` JWT sessions
- ☑ `M1-04.1` `TokenService` — issue/validate via Spring Security Nimbus, secret from env, **startup fails if under 32 bytes**
- ☑ `M1-04.2` Access token 15 min; refresh token 30 days, **stored as SHA-256, never in the clear**
- ☑ `M1-04.3` Refresh rotation + reuse detection (a replayed token revokes the whole family)
- ☑ `M1-04.4` HttpOnly / Secure / SameSite=Strict refresh cookie, path-scoped to `/api/v1/auth`
- ☑ `M1-04.5` **CSRF defence on the refresh endpoint** — repays PENDING T1. `SameSite=Strict` plus a required `X-Refresh-Request` header that HTML forms cannot set.
- ☑ `M1-04.6` Bearer validation via Spring's `oauth2ResourceServer` — no hand-rolled auth filter
- ☑ `M1-04.7` `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`

### ▶ `M1-05` Authorization
- ☑ `M1-05.1` `CurrentUser` record + argument resolver; throws rather than injecting null
- ☑ `M1-05.2` `@EnableMethodSecurity` on; JWT `role` claim mapped to a `ROLE_` authority
- ☑ `M1-05.3` Ownership — **structural, not a check**. No endpoint accepts a profile id to edit; they all act on the token's own user, so no parameter tampering can reach another tutor's profile.
- ☑ `M1-05.4` Tests: unauthenticated, tampered token, student-blocked-from-tutor-endpoints, and tutor-vs-tutor isolation

### ☑ `M1-06` Catalog schema & seed *(moved from M2 — see corrections above)*
- ☑ `M1-06.1` `V4__catalog.sql` — `subjects` (self-referencing tree), `boards`, `grade_levels`, `locations`
- ☑ `M1-06.2` Seed subject taxonomy — **70 subjects across 7 categories**: School Tuition, Exam Preparation, Languages, Computers & IT, Music & Dance, Study Abroad Tests, Hobbies & Sports
- ☑ `M1-06.3` Seed 10 boards (CBSE, ICSE, 5 state boards, IB, IGCSE, NIOS) and 19 grade levels
- ▶ `M1-06.4` Seed locations — 10 cities live; Hyderabad 20 localities, Bengaluru 14, others shallow. **Depth still pending decision D1**; deliberately shallow elsewhere because empty city×subject pages are an SEO liability (`M2-08.4`).
- ☑ `M1-06.5` Unique slugs on subjects and locations, chosen to read naturally in a URL
- ☑ `M1-06.6` Read-only catalog endpoints under `/api/v1/public/catalog`, cached 6h

### ☑ `M1-07` Student profile
- ☑ `M1-07.1` `V6__profiles.sql` — `student_profiles` (deliberately thin; every field asked for is a chance to abandon the funnel)
- ☑ `M1-07.2` Create-on-first-read, read and update endpoints, location validated against the catalog
- ☑ `M1-07.3` Tests — 5, including tutor-blocked and student-vs-student isolation

### ☑ `M1-08` Tutor profile
- ☑ `M1-08.1` `V6__profiles.sql` — `tutor_profiles`, `tutor_subjects`, `tutor_locations`, `tutor_qualifications`
- ☑ `M1-08.2` Core CRUD — bio, photo, gender, experience, languages, demo, availability
- ☑ `M1-08.3` Fees in **paise** as `BIGINT`, with unit and negotiable flag
- ☑ `M1-08.4` Subject selection with per-subject grades and boards; categories rejected as unteachable
- ☑ `M1-08.5` Teaching modes + serviceable locations + travel radius
- ☑ `M1-08.6` Qualifications — add, remove, and document upload (private, admin-readable only)
- ☑ `M1-08.7` Completeness scoring, weighted by what a parent decides on, plus a plain-language "what's missing" list
- ☑ `M1-08.8` Publish / unpublish — refuses below 60%, and auto-unpublishes if an edit drops it below
- ☑ `M1-08.9` Tests — 7 unit + 10 integration

### ▶ `M1-09` File storage
- ☑ `M1-09.1` `FileStorage` interface + `LocalFileStorage`, provider chosen by config
- ☑ `M1-09.2` Upload endpoints with per-kind size caps and **magic-byte type detection** — the declared type and filename are never consulted
- ☑ `M1-09.3` Uploaded filenames discarded entirely; stored as `kind/uuid.ext`
- ☑ `M1-09.4` Split serving: `/public/files/**` serves public kinds only (404, not 403, for anything else); `/admin/files/**` is the sole route to an ID or education document
- ☐ `M1-09.5` Image resize/compress for profile photos — deferred; a 5 MB cap is holding for now, and this is a cost optimisation rather than a correctness one
- ☑ `M1-09.6` Tests — 16, covering disguised HTML, SVG, PHP, five path-traversal shapes, size caps and per-kind type rules

### ☑ `M1-10` Verification
- ☑ `M1-10.1` `V7__verification.sql` — `verifications`, with a partial unique index allowing resubmission after rejection but not duplicate live requests
- ☑ `M1-10.2` Submit endpoints for ID and education documents, stored as private file kinds
- ☑ `M1-10.3` Admin queue (oldest first) with approve/reject; **the rejection reason is mandatory at the database level**, and review is one-shot so the audit trail survives
- ☑ `M1-10.4` `verification_level` derivation — **PHONE → ID → EDUCATION, with email as a separate badge rather than a rung.** Taken literally the original ladder would have made ID unreachable for any tutor without an email, since email is optional here; see `VerificationLevel`.
- ☑ `M1-10.5` Badges and level on the public profile response
- ☑ `M1-10.6` `levelFor()` returns `ID_VERIFIED` — the hook M4 needs for the signup bonus

### ▶ `M1-11` Frontend — auth
- ☑ `M1-11.1` Phone entry + OTP screens
- ☑ `M1-11.2` Role choice at signup (Student/Parent vs Tutor)
- ☑ `M1-11.3` `AuthProvider` — access token **in memory only**, refresh-on-load from the HttpOnly cookie, `authFetch` with one automatic refresh-and-retry on 401
- ☑ `M1-11.4` `RequireRole` guard + role-based redirect after sign-in
- ☑ `M1-11.5` Error states driven by `ErrorCode`, never message text
- ☐ `M1-11.6` Resend cooldown on the OTP screen

### ▶ `M1-12` Frontend — profiles
- ☑ `M1-12.1` Tutor onboarding wizard — steps are tabs rather than a forced sequence, each saves on its own, server-driven completeness meter
- ☑ `M1-12.2` Subject picker against the live catalog
- ☑ `M1-12.3` Location + travel radius picker
- ☑ `M1-12.4` Photo upload with preview; ID and education document upload on the dashboard
- ☑ `M1-12.5` Profile editing — the wizard *is* the editor, so there is one place a profile is changed rather than two that can diverge
- ☐ `M1-12.6` Student profile screen — backend done (`M1-07`), UI pending
- ☑ `M1-12.7` Tutor dashboard with verification status *(added — not in the original breakdown)*

---

## M2 — Discovery & SEO

**Goal:** a parent finds relevant tutors fast, and a city×subject page ranks.

### ☑ `M2-01` Search service
- ☑ `M2-01.1` Query builder — subject, location, board, grade, mode, fee, gender, experience, rating, verified-only, free text
- ☑ `M2-01.2` Sorting: relevance, rating, fee both ways, experience, recently active — from an enum, so nothing arbitrary reaches the ORDER BY
- ☑ `M2-01.3` Pagination via `PageResponse`, size capped at 50
- ☑ `M2-01.4` `is_published` is not optional and not a parameter
- ☑ `M2-01.5` 12 integration tests across filters, visibility and paging

### ▶ `M2-02` Search performance
- ☑ `M2-02.1` GIN trigram index for `ILIKE %term%`, which a btree cannot serve
- ☑ `M2-02.2` Partial composite indexes per sort order, plus reverse-direction indexes on the join tables the EXISTS subqueries actually probe
- ☑ `M2-02.3` `explainSearch()` exposed for `EXPLAIN ANALYZE`
- ☐ `M2-02.4` Seed realistic volume and re-measure — **deferred: with a handful of rows Postgres correctly prefers a sequential scan, so asserting index use today would prove nothing.** Needs the M6-04 demo dataset.

### ☑ `M2-03` Contact masking
- ☑ `M2-03.1` `ContactMasking` — one implementation for phone, name and email
- ☑ `M2-03.2` **Structural, not masked**: public DTOs carry no contact fields at all, so no code path can leak one
- ☑ `M2-03.3` Search and profile responses asserted to contain no phone, email, date of birth, document URL or `userId`

### ☑ `M2-04` Public endpoints
- ☑ `M2-04.1` `GET /public/tutors` search, plus `/facets` for sidebar counts
- ☑ `M2-04.2` `GET /public/tutors/{id}` public profile
- ☑ `M2-04.3` Cache headers — 5 minutes on search, since a stale result is worse than a slow one

### ☑ `M2-05` Frontend — search
- ☑ `M2-05.1` Search page wired to the real endpoint, with a filter sidebar
- ☑ `M2-05.2` `SearchResultCard` — wider than the hero card, verification badge given real prominence
- ☑ `M2-05.3` **Filters are links, not checkboxes** — state lives in the URL, so results are shareable, the back button works, and it functions without JavaScript
- ☑ `M2-05.4` Empty state routes to posting a requirement; "New tutor" rather than a 0.0 rating, which reads as bad instead of new

### ☑ `M2-06` Frontend — tutor profile page
- ☑ `M2-06.1` Full profile layout — about, subjects, qualifications, class details
- ☑ `M2-06.2` CTA plus an explanation of why no phone number is shown
- ☑ `M2-06.3` No contact detail anywhere on the page
- ☑ `M2-06.4` Now reads the real endpoint; example profiles survive only at their own slugs and are `noindex`

### ▶ `M2-07` SEO landing pages
- ☑ `M2-07.1` `/tutors/[city]/[subject]` — server-rendered, zero client JavaScript
- ☐ `M2-07.2` `/tutors/[city]/[locality]/[subject]` — **deferred: 10 cities × 70 subjects is already 700 pages with no tutors on them.** Adding locality depth multiplies thin pages before there is supply to fill them.
- ☑ `M2-07.3` Unique title, meta and H1 per page, generated from live catalog data
- ☑ `M2-07.4` Internal linking — related subjects in the same city, and the same subject in other cities
- ☑ `M2-07.5` Renders fully without JavaScript

### ☑ `M2-08` SEO plumbing
- ☑ `M2-08.1` `sitemap.xml` generated from the live catalog — **714 URLs**
- ☑ `M2-08.2` `robots.txt`, disallowing authenticated areas and the API to protect crawl budget
- ☑ `M2-08.3` JSON-LD — `Person` with `AggregateRating` on profiles, `Service` on landing pages
- ☑ `M2-08.4` Canonical URLs; a combination with no tutors is `noindex, follow` until it has something to show

---

## M3 — Requirements & the lead loop

**Goal:** the core marketplace transaction works end to end. This is the milestone the product lives or dies on.

### ☑ `M3-01` Requirements schema
- ☑ `M3-01.1` `V9__requirements.sql` — `requirements`, `lead_unlocks` *(V9, not V6 — verification took V7 and search indexes V8)*
- ☑ `M3-01.2` `lead_unlocks` carries `engagement_type` (SoT Invariant 2)
- ☑ `M3-01.3` **Unique constraint on `(requirement_id, tutor_id)`** — a tutor must never be charged twice for one lead
- ☑ `M3-01.4` Entities + repositories

### ☑ `M3-02` Lead pricing
- ☑ `M3-02.1` `LeadPricingService` implementing the SoT §3.1 budget bands — now DB-backed and admin-editable
- ☑ `M3-02.2` Online-only multiplier, rounded up — the multiplier is a setting, not a constant
- ☑ `M3-02.3` **Price locked onto the requirement at creation** — repricing must never move a lead's cost under a tutor
- ☑ `M3-02.4` Table-driven tests across every band boundary — 22 cases, no Spring context

### ☑ `M3-03` Requirement endpoints
- ☑ `M3-03.1` Post a requirement (student only)
- ☑ `M3-03.2` List/edit/close own requirements
- ☑ `M3-03.3` Mark HIRED / CLOSED
- ☑ `M3-03.4` View tutors who unlocked, with contacts revealed
- ☑ `M3-03.5` Validation against catalog IDs

### ☑ `M3-04` Requirement lifecycle
- ☑ `M3-04.1` Expiry scheduled job — `RequirementExpiryJob`, hourly, one bulk UPDATE; the window is a setting
- ☑ `M3-04.2` Status transitions: OPEN → CAPPED / HIRED / CLOSED / EXPIRED
- ☑ `M3-04.3` Guard illegal transitions

### ☑ `M3-05` Credit ledger & wallet *(moved from M4 — see corrections above)*
- ☑ `M3-05.1` `V10__billing.sql` — `credit_wallets`, `credit_transactions`
- ☑ `M3-05.2` **Append-only ledger** (SoT Invariant 1) — DB trigger rejecting UPDATE and DELETE
- ☑ `M3-05.3` Balance derivation from the ledger, honouring expiry
- ☑ `M3-05.4` `credit_wallets.balance` as a cache, written in the same transaction
- ☑ `M3-05.5` Reconciliation check: replayed ledger must equal cached balance
- ☑ `M3-05.6` Admin credit grant — lets M3 be tested before any payment code exists
- ☑ `M3-05.7` `GET /tutor/leads/wallet` + transaction history
- ☐ `M3-05.8` Scheduled credit expiry — **deferred to M4** with the rest of credit lifecycle; nothing can expire until credits can be bought

### ☑ `M3-06` Lead feed
- ☑ `M3-06.1` Match on tutor's subjects × serviceable locations
- ☑ `M3-06.2` Masked projection — no name, phone or exact address, enforced structurally by the DTO
- ☑ `M3-06.3` Exclude already-unlocked, capped, closed and expired requirements
- ☑ `M3-06.4` Show unlock cost and remaining slots
- ☑ `M3-06.5` Sorting and pagination — newest first, page size capped at 50
- ☑ `M3-06.6` **Published profiles only** — added after the fact; see the note below

### ☑ `M3-07` Unlock endpoint — **the critical path**
- ☑ `M3-07.1` `POST /tutor/leads/{id}/unlock`, replay-safe — **no `Idempotency-Key` header**; see the note below
- ☑ `M3-07.2` Single transaction: check cap → check balance → ledger debit → record unlock → reveal
- ☑ `M3-07.3` Insufficient balance → `INSUFFICIENT_CREDITS`, nothing written
- ☑ `M3-07.4` Cap reached → `LEAD_UNLOCK_CAP_REACHED`, **no credits debited**
- ☑ `M3-07.5` Optional intro message to the student
- ☑ `M3-07.6` Response reveals contact details
- ☑ `M3-07.7` Replaying an unlock returns the original result, never a second charge

### ☑ `M3-08` Cap enforcement & concurrency
- ☑ `M3-08.1` Enforce the cap (SoT §3.2) — the number is a setting, locked onto each requirement at posting
- ☑ `M3-08.2` Transition to `CAPPED`, remove from all other feeds
- ☑ `M3-08.3` **Concurrency test: 10 tutors race for 5 slots — 5 win, no loser is charged, the ledger reconciles**
- ☑ `M3-08.4` Pessimistic `SELECT … FOR UPDATE`, with a unique index underneath as the real guarantee

### ☑ `M3-09` Notifications
- ☑ `M3-09.1` `MailSender` + `SmsSender` interfaces with console dev stubs
- ☑ `M3-09.2` `V11__notifications.sql` + `NotificationService`
- ☑ `M3-09.3` Tutor: new matching lead — fan-out capped at 4× the enquiry's unlock cap
- ☑ `M3-09.4` Student: a tutor unlocked your requirement
- ☑ `M3-09.5` Tutor: low credit balance — threshold is a setting
- ☑ `M3-09.6` Rows written in the caller's transaction, **delivered after commit** via `@TransactionalEventListener`

### ☑ `M3-10` Frontend — student side
- ☑ `M3-10.1` Post-requirement form (mobile-first; fillable before sign-in, with a live price quote)
- ☑ `M3-10.2` Requirement dashboard
- ☑ `M3-10.3` Responding-tutors list with revealed contacts
- ☑ `M3-10.4` Mark hired / close

### ☑ `M3-11` Frontend — tutor side
- ☑ `M3-11.1` Lead feed with masked previews
- ☑ `M3-11.2` Unlock confirmation showing cost and resulting balance
- ☑ `M3-11.3` Post-unlock contact reveal
- ☑ `M3-11.4` My-leads list
- ☑ `M3-11.5` Wallet balance visible throughout, and in the header for signed-in users

### Two behaviour changes made while closing M3

Both were found by walking the money path rather than by a failing test, and both are recorded in SoT §3.

**`M3-06.6` — only published tutors see leads.** The feed matched on subjects and locations alone, so a tutor who had never published could unlock a lead. That puts a stranger on a parent's phone with no profile for the parent to check them against, which is precisely what the verification ladder exists to prevent. `findLeadFeedFor` and `findTutorsToNotify` now both require `is_published`, and they must stay in step — notifying a tutor about a lead their feed will not show them sends them to an empty screen.

**`M3-07.1` — replay safety without an `Idempotency-Key`.** The task asked for the header. A repeated unlock now returns the unlock the tutor already holds, which achieves the same guarantee structurally and covers the case the header would have missed anyway: a tutor on a patchy mobile connection whose request succeeded but whose response never arrived. Answering that retry with `LEAD_ALREADY_UNLOCKED` left them charged and holding nothing — the worst outcome the money path can produce. The charge is still exactly once, guarded by the row lock and the unique index. `LEAD_ALREADY_UNLOCKED` remains in `ErrorCode` and is still returned when two of a tutor's own requests race.

---

## M4 — Credits & payments

**Goal:** money comes in, exactly once, and provably.

### ☑ `M4-01` Packages
- ☑ `M4-01.1` `V13__packages_and_payments.sql` — `credit_packages`, `payments`, `payment_webhook_events`
- ☑ `M4-01.2` Packages are **admin-editable rows**, not seed constants — the prices are still a hypothesis (PENDING D3), but being wrong now costs a settings edit rather than a release
- ☑ `M4-01.3` Package listing endpoint, plus admin create/reprice/retire
- ☑ `M4-01.4` Retiring the **last** active package is refused — an empty storefront is an outage, not a pricing decision

### ☑ `M4-02` Razorpay checkout
- ☑ `M4-02.1` Razorpay over its REST API, keys from env — **no SDK**; it is one POST and two HMACs, and the SDK would add `org.json` and a second HTTP client for less code than it saves
- ☑ `M4-02.2` Order creation endpoint, `payments` row in `CREATED`
- ☑ `M4-02.3` Amounts in paise throughout, read from the package server-side
- ☑ `M4-02.4` **Client-reported amounts are ignored** — a payload claiming ₹999,999 grants exactly the package's credits, and a test says so
- ☑ `M4-02.5` `StubPaymentGateway` when no keys are configured, so the whole flow was testable before a Razorpay account exists

### ☑ `M4-03` Webhook
- ☑ `M4-03.1` Signature verified against the **raw bytes**, constant-time; fails closed on a missing secret, missing header or mismatch
- ☑ `M4-03.2` **Three deliveries of one event grant credits once** — row lock, `credited_at`, and a unique index, in that order
- ☑ `M4-03.3` Raw payload persisted, invalid signatures included — a run of those is how anyone learns the endpoint is being probed
- ☑ `M4-03.4` Failed and cancelled payments handled; a failure arriving *after* a capture does not reverse it
- ☑ `M4-03.5` `StalePaymentJob` cancels orders never confirmed after two hours; a late webhook still credits them
- ☑ `M4-03.6` Nine tests: replay, out-of-order delivery, forged and absent signatures, unknown orders, inflated amounts

### ☑ `M4-04` Signup bonus
- ☑ `M4-04.1` Granted at `ID_VERIFIED` (SoT §3.3), in `REQUIRES_NEW` so it can never roll back the admin's trust decision
- ☑ `M4-04.2` **Partial unique index guarantees once-only** — the application check is only the friendly path
- ☑ `M4-04.3` Expiry from the `credits.bonus_validity_days` setting

### ☑ `M4-05` Credit expiry
- ☑ `M4-05.1` Daily job appending negative `EXPIRY` entries — never editing the grant
- ☑ `M4-05.2` Validity windows are settings, not constants
- ☑ `M4-05.3` Expiry warning, one notification per tutor rather than one per grant
- ☑ `M4-05.4` **Expiry never takes a balance below zero** — spent credits are not clawed back; see the note below
- ☑ `M4-05.5` `V15__credit_expiry_ledger.sql` — tracking which grants have been processed, outside the ledger

### ☑ `M4-06` Refunds & disputes
- ☑ `M4-06.1` `V14__refunds.sql` — `refund_requests`
- ☑ `M4-06.2` Tutor raises a dispute within the (configurable) window, with a reason code; one per unlock, enforced by a unique index
- ☑ `M4-06.3` Admin queue and decision workflow; a rejection **requires** a note
- ☑ `M4-06.4` Approved → `REFUND` ledger entry, unlock `REFUNDED`, **cap slot freed and the enquiry reopened**
- ☑ `M4-06.5` Two abuse signals, both flagging rather than blocking: a tutor's dispute rate, and a requirement several tutors have disputed

### ☑ `M4-07` Invoices
- ☑ `M4-07.1` Receipt endpoint and a printable receipt — **no PDF library**; the browser prints, and "save as PDF" is a dialog every user already knows
- ☑ `M4-07.2` GST fields present and nullable. **The question is still open (PENDING D6)** — the fields exist now because retrofitting tax onto historical transactions is genuinely unpleasant, and cost nothing to add

### ☑ `M4-08` Frontend — wallet
- ☑ `M4-08.1` Package selection and checkout
- ☑ `M4-08.2` Razorpay widget, opened with the server's order and the publishable key only
- ☑ `M4-08.3` Success / failure / **pending** — pending gets first-class treatment because the webhook, not the browser, is what credits the wallet
- ☑ `M4-08.4` Ledger history with a running balance
- ☑ `M4-08.5` Low-balance prompt at the point of unlock — the button *becomes* "Top up to unlock" rather than going dead
- ☑ `M4-08.6` Dispute UI on the tutor's own leads, deliberately not buried

### Two notes worth keeping

**A design the database rejected, correctly.** The expiry job first recorded a zero-amount `EXPIRY` entry against grants that had been fully spent before lapsing, purely so it would not reprocess them forever. `credit_transactions_amount_nonzero` refused it. The constraint was right: a zero-amount entry is not a movement of money. "Which grants have been through expiry" is bookkeeping *about* the ledger, not an entry in it, so it moved to `credit_grant_expiries` (V15).

**Expiry is capped at the current balance.** Credits are fungible, so a tutor granted 10 who has spent 8 still has a 10-credit grant on record when it lapses. Writing off the full 10 would take them to −2 and bill them for credits they already used and paid for. The deliberate consequence: spent credits are never clawed back, and given the choice the platform takes the loss.

---

## M5 — Reviews, admin & trust

**Goal:** an admin can run the marketplace without touching the database.

### ☑ `M5-01` Reviews schema & eligibility
- ☑ `M5-01.1` `V16__reviews.sql` — `reviews`, unique on `(tutor_id, student_id)`. **Not `V10`, as this line originally said: V10 is `billing`.** Both ids are user ids, matching `lead_unlocks`
- ☑ `M5-01.2` Eligibility: only a student connected to that tutor via an **`ACTIVE`** unlock — `LeadUnlockRepository.countEngagementsBetween`. A refunded unlock is one the tutor disowned as a bad lead, and letting that student rate them anyway makes every refund an invitation to retaliate
- ☑ `M5-01.3` Submit endpoint with validation. Resubmitting while pending edits the existing review rather than being refused as a duplicate

### ☑ `M5-02` Moderation
- ☑ `M5-02.1` Everything starts `PENDING` — the filter is in the repository query, not applied by callers
- ☑ `M5-02.2` Admin approve/reject queue, plus `unpublish` for a review reported after the fact
- ☑ `M5-02.3` Notify the tutor on publication (`REVIEW_PUBLISHED`), and the student on rejection (`REVIEW_REJECTED`) — silence is indistinguishable from a bug

### ☑ `M5-03` Tutor replies
- ☑ `M5-03.1` Exactly one reply per review, on a published review only
- ☑ `M5-03.2` Replies moderated too — **this needed schema the plan did not have.** `tutor_reply` was specified as a bare text column with nowhere for a reply to sit `PENDING`; it now carries `tutor_reply_status`, `tutor_reply_at`, `tutor_reply_moderated_by`, decided independently of the review

### ☑ `M5-04` Rating aggregation
- ☑ `M5-04.1` Recompute `avg_rating` / `review_count` on **every** transition into or out of `APPROVED`, not only approval — un-approving must move the average back
- ☑ `M5-04.2` Never accept an aggregate from the client. Structural: no request record has a field to bind one to, asserted over all of them by `ReviewDtoContractTest`
- ☑ `M5-04.3` `POST /admin/reviews/recompute-ratings` — backfill and drift repair, the counterpart to the ledger's `reconcile`

### ☑ `M5-05` Admin backend
- ☑ `M5-05.1` Verification queue — shipped at `M1-10` (`AdminVerificationController`)
- ☑ `M5-05.2` Review moderation — `AdminReviewController`, two queues (reviews, replies)
- ☑ `M5-05.3` User management, suspend/reinstate — `AdminUserController`, `UserAdminService`. Suspension is enforced at read time by the search query and the profile lookup, not by a flag; suspending a student takes down their live enquiries
- ☑ `M5-05.4` Credit adjustments and refund decisions — shipped at `M4-06` (`AdminCreditController`, `AdminRefundController`)
- ☑ `M5-05.5` Package and pricing management — shipped at `M4-01` (`AdminPackageController`, `AdminSettingsController`)
- ☑ `M5-05.6` Requirement moderation (spam, fake leads) — `AdminRequirementController`, `RequirementModerationService`. A takedown refunds every tutor who paid, in the same transaction
- ☑ `M5-05.7` Funnel metrics: signups, requirements, unlocks, revenue, conversion — `AdminMetricsController`, one snapshot in one transaction

### ☑ `M5-06` Admin frontend
- ☑ `M5-06.1` Layout, navigation, admin-only route guard — `/admin` layout, `AdminNav`, `RequireRole role="ADMIN"`, `robots: noindex`
- ☑ `M5-06.2` Screens for each M5-05 capability — verifications, reviews, disputes, enquiries, users, credits, pricing
- ☑ `M5-06.3` Document viewer for ID/education verification — `DocumentViewer`, blob-fetched with the bearer token, revoked on unmount, never loaded until asked for
- ☑ `M5-06.4` Metrics dashboard — `/admin`, with an inline-SVG daily series

### ☑ `M5-07` Rate limiting
- ☑ `M5-07.1` Auth endpoints (OTP already limited at M1-03) — 20/min per IP on `/auth/**`, on top of the per-phone hourly cap
- ☑ `M5-07.2` Unlock and search endpoints — search per IP in the filter; unlock **per tutor** in `LeadUnlockService`, and deliberately after the replay check
- ☑ `M5-07.3` Per-IP and per-user buckets — `RateLimiter`, token buckets in memory (debt T20 for the single-instance assumption)
- ☑ `M5-07.4` `429` with `Retry-After` — set by the filter, and by `GlobalExceptionHandler` for `RateLimitedException`

### ☑ `M5-08` Audit log
- ☑ `M5-08.1` `V18__audit.sql` — `audit_log` (**not `V17`**: that is `admin_moderation`, and **not `V11`**: that is `notifications`)
- ☑ `M5-08.2` Every admin action recorded with before/after — `AuditInterceptor` guarantees the entry exists because of the route; services describe what changed through `AuditContext`
- ☑ `M5-08.3` Every credit adjustment and refund recorded — `AdminCreditController`, `RefundService.approve`/`reject`
- ☑ `M5-08.4` Admin-visible, immutable — `GET /admin/audit` and `/admin/audit` in the console; `UPDATE` and `DELETE` raise at the database, as `credit_transactions` does

### ☑ `M5-09` Abuse reporting
- ☑ `M5-09.1` Report a tutor, student, review or requirement — `POST /reports`, authenticated only; a quiet report link on the public tutor profile
- ☑ `M5-09.2` Admin triage queue — `AdminAbuseReportController` and `/admin/reports`. Deciding is separate from acting, and `RefundService`'s dispute-rate signal now raises a `SYSTEM` report instead of a log line

### ☑ `M5-10` Data rights (DPDP)
- ☑ `M5-10.1` Data export — `GET /me/export`, served as a download, including the credit ledger
- ☑ `M5-10.2` Account deletion with a documented retention policy — `POST /me/delete`; policy in SOURCE_OF_TRUTH §3.10, and on the screen before the button
- ☑ `M5-10.3` **Deletion must not corrupt the financial ledger** — it anonymises and never removes. The phone becomes `+99` + the zero-padded id: unique by construction, valid E.164, and an unassigned country code so it cannot collide with a real number. `deletionKeepsTheLedgerIntact` is the test that fails if this is ever "simplified" into a DELETE

### ☑ `M5-11` Review screens — **added 2026-09-01**

Not in the original plan: `M5-06` covers only the admin UI, so the screens students and tutors
actually use had no task. A review nobody can write is dead code.

- ☑ `M5-11.1` Student writes and edits a review inline on the enquiry the tutor answered — where they already are when they have an opinion, rather than behind a separate "leave a review" flow
- ☑ `M5-11.2` Tutor sees reviews of them, pending included, with the one-reply composer (`/tutor/reviews`)
- ☑ `M5-11.3` Published reviews on the public tutor profile, server-rendered so a crawler sees them, with reviewer names masked to "Priya S."

---

## M6 — Polish & launch

**Goal:** something a real parent in India can use on a mid-range Android phone.

> **Partly blocked.** `M6-07` (deployment), `M6-10.1/.2/.4` (production providers) and `M6-01.3`
> (real-device testing) need a host account, provider credentials, a domain and a phone —
> none of which an implementation session can supply. Everything not blocked is done.

### ▶ `M6-01` Responsive pass
- ▶ `M6-01.1` Every screen mobile-first — the existing screens were built mobile-first; the whole end-to-end suite now also runs against a Pixel 7 viewport, so a desktop-only layout fails CI. **No exhaustive screen-by-screen audit has been done.**
- ☑ `M6-01.2` Touch targets, thumb reach, sane keyboard types on inputs — `type="tel"` and `inputMode` are set on every numeric and phone field; button sizes clear 44px at `md` and above
- ☐ `M6-01.3` Test on a real device — **blocked**: needs a physical Android phone

### ▶ `M6-02` States
- ☑ `M6-02.1` Empty states with a next action — every list has one; the admin queues say what an empty queue *means* rather than showing a blank panel
- ▶ `M6-02.2` Loading skeletons — spinners throughout, not skeletons. Adequate, not the best version
- ☑ `M6-02.3` Error states with recovery — every screen branches on `ErrorCode`, never on message text
- ☐ `M6-02.4` Offline / slow-network behaviour — not addressed

### ▶ `M6-03` Accessibility
- ☑ `M6-03.1` Keyboard navigation — skip link added; it was the real gap, since `<main id="main">` already existed with nothing pointing at it
- ☑ `M6-03.2` Labels and ARIA where needed — every control is labelled; `aria-pressed` on tab groups, `aria-current` on nav, `role="img"` with a label on the dashboard charts
- ☐ `M6-03.3` Contrast audit — not done. Needs a tool run over the built pages
- ☑ `M6-03.4` Visible focus states — global `:focus-visible` was already in place

### ☑ `M6-04` Seed & demo data
- ☑ `M6-04.1` Believable demo dataset — 8 tutors, 6 enquiries, 5 reviews, hand-written rather than generated
- ☑ `M6-04.2` One-command load — `--apnatutor.demo.seed=true`. Built through the real services, so if the seed runs, the flows work

### ☑ `M6-05` End-to-end tests
- ☑ `M6-05.1` Playwright setup — desktop and mobile projects, serial, no retries
- ☑ `M6-05.2` **The money path**, automated — post, unlock, contact revealed, replay charges nothing
- ☑ `M6-05.3` Search and SEO page rendering — asserted with **JavaScript disabled**, which is the only way to prove the content is server-rendered rather than hydrated. Found a real 500 on the city × subject pages

### ▶ `M6-06` Performance
- ☐ `M6-06.1` Image optimisation — not done
- ▶ `M6-06.2` Query budget per page; hunt N+1s — the known N+1s were closed as they were written (review name lookups at M5-01, catalog loaded once per list); no systematic budget exists
- ☐ `M6-06.3` Lighthouse pass on the SEO pages — not run
- ☐ `M6-06.4` Bundle size review — not done

### ▶ `M6-07` Deployment
- ☐ `M6-07.1` Choose host, provision — **blocked**: needs an account and a payment method
- ☐ `M6-07.2` Managed PostgreSQL with automated backups **and a tested restore** — **blocked**, same reason. The tested restore is the part that must not be skipped
- ☑ `M6-07.3` Production config, real secrets, `clean-disabled: true` — `application-prod.yml`; nothing secret has a default, so a missing variable fails at startup
- ☑ `M6-07.4` CI: build, test, migrate, deploy — `.github/workflows/ci.yml`: backend, frontend and end-to-end jobs. **Deploy is not wired**, because there is nothing to deploy to yet
- ☐ `M6-07.5` Domain + TLS — **blocked**: `D2`, whether `apnatutor.in` is available, is still unchecked
- ☐ `M6-07.6` Staging environment — **blocked**, follows `M6-07.1`

### ▶ `M6-08` Observability
- ▶ `M6-08.1` Error tracking — `Alerts` is the seam, with stable `kind` values an alerting rule can match. It logs today; adding a provider is a one-file change. Choosing one is `D8`
- ☑ `M6-08.2` Uptime monitoring on health endpoints — liveness and readiness probes enabled under `prod`, with only `health` and `info` exposed
- ☐ `M6-08.3` Analytics with conversion funnels — the funnel numbers exist at `/admin/metrics`; no third-party analytics. Needs a provider decision
- ☑ `M6-08.4` Alert on payment-webhook failures — a rejected signature and a failed processing attempt both raise one, as does a ledger mismatch

### ▶ `M6-09` Legal
- ▶ `M6-09.1` Terms of service — written, **needs a lawyer** and the company details
- ▶ `M6-09.2` Privacy policy (DPDP-aware) — written against what the code actually does, **needs a lawyer**
- ☑ `M6-09.3` **Refund policy — matches SoT §3.5 exactly**: the 7-day window, the eight reason codes, one dispute per lead, the freed cap slot, refunded credits carrying no expiry
- ☑ `M6-09.4` Tutor and student conduct guidelines — including the safety advice for home tuition

### ▶ `M6-10` Production providers
- ☐ `M6-10.1` Real SMS provider — **blocked**: `D5` is researched but undecided, and it needs an account
- ☐ `M6-10.2` Real email provider — **blocked**, needs an account
- ☑ `M6-10.3` S3-compatible file storage — `S3FileStorage`, works against S3, R2, B2, Spaces or MinIO. **Not tested against a live bucket**
- ☐ `M6-10.4` Razorpay live keys — **blocked**: needs a verified merchant account
- ☑ `M6-10.5` **Verify every console stub is gone from the production profile** — `DevModeGuard` now refuses a `prod` profile with console SMS, console mail or local storage
