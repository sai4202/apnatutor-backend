| D5 | **SMS provider: MSG91 vs Gupshup vs Twilio vs Firebase** | `M6-10.1` — **now blocking** |Repaid 2026-09-04 (M6): the file is deleted, the homepage shows real published tutors best-rated first, and the `/tutors/[slug]` example branch is gone. The strip renders nothing at all when there are no published tutors — an empty strip beats a padded one. || T11 | ~~`lib/exampleTutors.ts` is hardcoded sample data~~ — **repaid**# ApnaTutor — Pending

> **What this file is for.** Not a copy of the unticked boxes in TASKS.md — that would just be a second list to forget to update. This tracks the things a checklist cannot hold: decisions nobody has made, work that is blocked and on what, debt taken on deliberately, and risks that could cost real time.
>
> **TASKS.md owns task status. This file owns everything that is stuck, undecided, or deferred.**

**Last reviewed:** 2026-09-04 (after the M6 pass)

---

## 1. Decisions needed from you

Nothing here can be resolved by writing code. Each one blocks or reshapes real work.

| # | Decision | Blocks | Why it matters |
|---|---|---|---|
| D1 | **Which city do we launch in?** | `M1-06.4` locality seed | Locality data depth and the entire SEO page set depend on it. Seeding 200 Hyderabad localities is a different job from seeding 12 metro cities shallowly. Getting this wrong means reseeding and, worse, changing URLs that were already indexed. |
| D2 | **Is `apnatutor.in` available?** | `M6-07.5` domain | Not yet checked. The name is already in the package namespace (`com.apnatutor`) and repo name. Cheap to change now, painful after launch. Worth checking this week even though it is not needed until M6. |
| D3 | **Real credit package prices** | ~~`M4-01.2`~~ — **no longer blocking** | The numbers remain my hypothesis rather than research, but packages are admin-editable rows, so launching with a guess costs a settings edit rather than a release. Still worth checking what tutors actually pay UrbanPro before launch — the guess sets the unit economics either way. |
| D4 | **Is the ₹10/credit accounting value right?** | ~~`M4-01.2`~~ — **no longer blocking** | Now the `billing.credit_value_paise` setting. Everything downstream still hangs off it; being editable means being wrong is recoverable, not that it does not matter. |
| D5 | **SMS provider: MSG91 vs Gupshup vs Twilio vs Firebase** | `M6-10.1` | Per-SMS cost in India varies severalfold, and OTP volume is the single largest per-user variable cost. **Not blocking anything** — dev mode's seeded test accounts cover development and demos entirely. Researched 2026-09-01: Firebase Phone Auth is free for only the first 10 SMS/day and then ~₹6 per verification in India, which is expensive at volume; MSG91 targets India directly and is the cheapest of the mainstream options; Twilio Verify is the easiest to integrate and the priciest. Firebase also shipped carrier-SIM-based verification in May 2026 with no per-message fee, worth evaluating if it covers Indian carriers. |
| D6 | **GST invoicing from day one?** | ~~`M4-07.2`~~ — **partially de-risked, still open** | The receipt carries `gstin` and `taxPaise` fields, both null. Adding nullable fields cost nothing; retrofitting tax onto historical transactions would have been genuinely unpleasant, so the shape is now ready either way. What is still needed from you: whether credit sales require GST-compliant invoices at launch, and the GSTIN if so. |
| D7 | **Do students ever need to see tutor contact details?** | `M2-03` masking rules | Currently one-directional: tutors unlock students. Whether a student can ever call a tutor directly changes the masking model and the whole lead economy. |
| D8 | **Error tracking and analytics providers** | `M6-08.1`, `M6-08.3` | Sentry or GlitchTip for errors; Plausible, Umami or PostHog for analytics. `Alerts` is already the seam for the first, so this is a signup and one file rather than a refactor. Worth deciding alongside the host, since some hosts bundle both. Not blocking anything before launch, but a launch with no error tracking means the first production bug is reported by a user rather than by the system. |

---

## 2. Blocked

Nothing is blocked.

**~~B1 — springdoc / `/swagger-ui`~~ — resolved same day, and it was never a real blocker.** I recorded it as blocked on the strength of Maven Central's `solrsearch` API reporting 2.8.6 as the latest springdoc. That field was stale. The repository's own `maven-metadata.xml` lists 3.0.0 through **3.1.0**, and the 3.x line is precisely the Spring Boot 4 line. Now on springdoc 3.1.0, serving OpenAPI 3.1.0 at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

> **Lesson worth keeping:** `search.maven.org/solrsearch` returns a cached `latestVersion` that lags real releases. For "does version X exist", read `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` instead — it is generated from the repository itself. Do not declare a dependency unavailable on the strength of the search API alone.

*(When something lands here, record what it is waiting on — a blocker with no named dependency tends to sit forever.)*

---

## 3. Deliberate debt

Taken on knowingly, with the repayment point named. This is not a list of mistakes — it is a list of decisions with a due date.

| # | Debt | Taken at | Repay at | Notes |
|---|---|---|---|---|
| T1 | ~~No CSRF protection on the refresh endpoint~~ — **repaid** | M0 | Repaid 2026-08-31 (`M1-04.5`) | Two independent defences on the cookie-authenticated route: `SameSite=Strict`, and a required `X-Refresh-Request` header that HTML forms cannot set and cross-origin scripts cannot send without passing a preflight. Covered by test `refreshRequiresCsrfHeader`. |
| T2 | **Mockito self-attaches as a JVM agent** | M0 | When it breaks | Warns on every test run. Future JDKs will forbid it; fix is an explicit `-javaagent` in Surefire. Plausibly bites sooner on Java 26 than it would on an LTS (ADR #4). |
| T3 | ~~No global error handler~~ — **done** `M1-01` | M0 | Repaid 2026-08-31 | `ApiError`, `ErrorCode`, `GlobalExceptionHandler`, `PageResponse`, `CorrelationIdFilter` all landed. API docs remain blocked separately as B1. |
| T4 | **Java 26 rather than an LTS** | M0 | If a library breaks | ADR #4. Fallback to Temurin 21 is documented. The risk is a bytecode-manipulating library (Mockito, Hibernate's enhancer) lagging the JDK. |
| T5 | **No Testcontainers** | M0 | Only if Docker is ever adopted | ADR #5. Consequence: the test database must exist on any machine running the suite. `scripts/db-setup.sql` handles it, but CI setup in `M6-07.4` must create it explicitly. |
| T6 | **Postgres full-text instead of Elasticsearch** | M2 | Only if search quality suffers | Deliberate — Elasticsearch is a lot of operational weight for a single-city launch. `M2-02.3` is the checkpoint that tells us if it is holding up. |
| T7 | **One role per account** | M0 | On real user demand | ADR #10. Someone who is both a parent and a tutor needs two accounts. |
| T8 | **Availability as free text** | M1 | v2 scheduling | `M1-08.2` stores availability as a note, not structured slots. Fine while there is no booking; structured slots arrive with the v2 calendar. |
| T9 | **Access tokens cannot be revoked mid-life** | M1 | Only if abuse demands it | Stateless JWTs are verified by signature, not looked up, which is what makes them cheap. The cost is that suspending a user leaves their current access token working for up to 15 minutes. Refresh is re-checked against account status, so the blast radius is one token lifetime. A revocation list would undo the statelessness; not worth it unless a real incident says otherwise. |
| T10 | ~~OTP rate limiting is per phone only~~ — **repaid** | M1 | Repaid 2026-09-04 (`M5-07`) | `RateLimitFilter` caps `/auth/**` per IP at 20/min, on top of the per-phone hourly cap that was already there. The two protect different things: the phone cap stops a bill being run up on one victim, the IP cap stops one attacker walking many numbers. |
| T13 | **The credit expiry job assumes a single application instance** | 2026-09-01 (M4) | `M6-07`, before scaling out | A stronger assumption than `RequirementExpiryJob`'s. That job's bulk UPDATE is naturally idempotent; this one writes ledger entries, and the "already processed?" check and the write are not atomic across processes. Two instances could each write off the same grant. Safe with one instance; a distributed lock is the first thing needed here if a second is ever added. |
| T12 | **The lead-feed and notify queries duplicate their matching rule** | 2026-09-01 (M3) | When a third caller appears | `findLeadFeedFor` and `findTutorsToNotify` in `RequirementRepository` encode the same subject × location × published predicate in two hand-written native queries, inverted. They must stay in step: notifying a tutor about a lead their feed will not show them sends them to an empty screen. Two copies is tolerable and both are commented; a third means extracting a shared SQL view. |
| T11 | ~~`lib/exampleTutors.ts` is hardcoded sample data~~ — **repaid** | 2026-09-01 | Repaid 2026-09-04 (M6) | Three fabricated tutor profiles powered the hero marquee and `/tutors/[slug]`. They were labelled and noindexed, and they were still fake listings on a marketplace. The file is deleted, the homepage shows real published tutors best-rated first, and it renders nothing at all when there are none — an empty strip beats a padded one. |
| T14 | **Review lists are unpaginated** | 2026-09-01 (M5) | When a tutor passes ~100 reviews | `publishedForProfile`, `aboutTutor` and `writtenBy` return every row. Correct today — nobody has more than a handful, and paginating a list of three is worse UX than not — and the moderation queues, which are the ones that could actually grow unbounded, are paginated already. The public profile read is the one to watch: it is the hot page, and it is cached for 5 minutes, which is what buys the time to fix it. |
| T15 | **A suspended tutor's live access token keeps working for up to 15 minutes** | 2026-09-04 (M5) | On a real incident | Not new — this is `T9` seen from the console. What is new is that there is now a button that makes it happen on purpose, and an admin who suspends someone for abusing a parent reasonably expects it to take effect now. Search and the profile page close immediately, so the visible surfaces are gone; what survives is the suspended user's own authenticated session, until their next refresh. The console now says so on the suspend screen (2026-09-04), which is the minimum; the honest fix is a short revocation list keyed on the suspension, not a general one. |
| T16 | **The lead feed does not check the poster's account status** | 2026-09-04 (M5) | If a second path to suspension appears | `findLeadFeedFor` and `findTutorsToNotify` filter on `r.status = 'OPEN'`, and suspending a student moves their live enquiries to `REMOVED`, so the feed is correct today by consequence rather than by construction. Every other read path that had to respect a suspension got an explicit `ACTIVE` check for exactly the reason this one did not: the enquiry is gone, not merely hidden. If a future path ever suspends an account without taking its enquiries down, this is what breaks, and it breaks by charging tutors. |
| T17 | **The admin user list has no pagination controls** | 2026-09-04 (M5) | When a filtered list routinely exceeds one page | The API paginates and the screen reports the true total, but there are no next/previous buttons — a search that matches 300 accounts shows the first 20 and says so. Correct for the way the screen is used (a phone number narrows to one row) and wrong the first time someone browses all tutors. Same shape as `T14`. |
| T18 | **`ModerationView` is the one requirement projection carrying the student's phone** | 2026-09-04 (M5) | Reviewed at `M5-08` | Deliberate and load-bearing: spam is recognised by seeing one number post eleven enquiries. It is also the single place where the contact detail the whole business model sells is returned outside a paid unlock. Guarded by a class-level `hasRole('ADMIN')`, and since 2026-09-04 every read of it is audited (M5-08) — which is what turns "an admin could have read every parent's number" into "an admin did, at 14:32". Left open rather than struck out: attribution is not the same as restriction, and nothing yet limits how much of it one admin can page through. |
| T19 | ~~Anonymous probing of `/admin` is not audited~~ — **addressed** | 2026-09-04 (M5) | Repaid 2026-09-04 (`M5-07`) | A request with no credentials is rejected by `anyRequest().authenticated()` in the security filter chain, before any interceptor runs, so `audit_log` never sees it. An authenticated non-admin trying admin routes **is** recorded, which is the case that matters — somebody holding a real token. Fixing this properly means auditing from a filter ordered ahead of Spring Security, and the actor would then be unreadable because the `SecurityContext` is cleared before an outer filter regains control; the two-component design that solves both is more machinery than the gap justifies. Anonymous probing is the access log's job and `M5-07`'s. Asserted by `anonymousRequestsAreNotRecorded`, so nobody re-adds the comment claiming otherwise. **Resolved differently than expected:** the audit log still does not see these, and now does not need to — `RateLimitFilter` sits ahead of Spring Security and turns the flood away before it reaches authentication at all, which is both cheaper and the right layer. The audit log records what admins do; stopping strangers is the limiter's job. |
| T20 | **Rate limit buckets live in one JVM's memory** | 2026-09-04 (M5) | `M6-07`, before scaling out | `RateLimiter` holds `ConcurrentHashMap` state per instance, so two instances behind a load balancer would allow twice every configured rate. This is the same single-instance assumption as **T13** on the credit expiry job, and the two should be fixed in one go. The shared alternative is Redis — a service to run, monitor and pay for, which is not worth it before there is a second instance to justify it. The map is swept past 50,000 buckets so it cannot itself become the denial of service it prevents. |
| T21 | **Server-side rendering makes every public request look like one visitor** | 2026-09-04 (M5) | `M6-07`, when a proxy exists | The Next.js frontend renders public pages on the server, so `/api/v1/public/**` is reached from the frontend server's single address rather than the visitor's — unless the deployment forwards `X-Forwarded-For`. The per-IP limit there is set to 3,000/min to keep that from throttling the whole site, which means it is not really protecting anything until the visitor address arrives. Two things fix it, and both belong to deployment: forward the real client IP, and then lower the number. Note that trusting `X-Forwarded-For` at all is a deployment assumption — correct behind a proxy we control, spoofable if the application is ever exposed directly, and the same assumption the audit log's IP column already carries. |
| T22 | **The S3 storage adapter has never touched a live bucket** | 2026-09-04 (M6) | `M6-07.1`, before any real upload | `S3FileStorage` compiles, wires up conditionally and shares its validation with the local implementation, but nothing has been stored or read against a real endpoint. The credentials chain, the endpoint override for non-AWS providers, and whether the bucket is genuinely private are all unverified. First deploy must upload a document, read it back as an administrator, and delete it — in that order — before any tutor is asked to. |
| T23 | **`Alerts` logs; nothing pages** | 2026-09-04 (M6) | `M6-08.1`, on choosing a provider (D8) | The seam is in place with stable `kind` values an alerting rule can match, and four real call sites use it. What is missing is anything that wakes a person: today a failed payment webhook is an ERROR line in a log nobody is watching at 2am, which is the exact failure mode the class was written to name. Adding a provider is a one-file change. |
| T24 | **No contrast audit, Lighthouse run or bundle review** | 2026-09-04 (M6) | `M6-06`, `M6-03.3` | All three need a tool run against a built site rather than a code change, and two of them want a deployed URL to be meaningful. Grouped here because they will be one afternoon's work once there is somewhere to point the tools at, and because ticking them off individually without running anything would be the dishonest option. |

---

## 4. Risks

Things that could cost significant time, with the cheapest early mitigation.

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | **Unmasked contact details leak into a public response** | Destroys the entire business model — the product *is* the paywall on contact details | `M2-03.3` tests this explicitly. Mask at the DTO boundary so an entity physically cannot leak. Treat any leak as a P0. |
| R2 | **Double-charging a tutor for one lead** | Direct loss of tutor trust; refund load | Unique constraint `M3-01.3`, idempotency `M3-07.7`, concurrency test `M3-08.3` |
| R3 | **Webhook grants credits more than once** | Direct revenue loss | `M4-03.2` idempotency + `M4-03.6` replay tests |
| R4 | **Cold-start: no tutors means no leads means no tutors** | The classic marketplace failure; no amount of code fixes it | Seed one city densely and manually recruit tutors before opening to parents. Product problem, not an engineering one — but it decides whether any of this matters. |
| R5 | **SEO pages rank slowly or not at all** | The primary acquisition channel underperforms | `M2-07.5` verifies real SSR; ship SEO pages early so indexing has time to mature |
| R6 | **Lead quality complaints from parents** | Churn on the demand side, which is the scarcer side | Unlock cap of 5 (SoT §3.2) is the main defence; watch complaint volume and tune |
| R7 | **Ledger and cached balance drift** | Financial correctness, hard to unpick after the fact | `M3-05.5` reconciliation; ledger is authoritative by design |

---

## 5. Open questions for later milestones

Not urgent, but recording them now so they are not rediscovered under pressure.

- Should tutors see *who* else unlocked a lead, or only the remaining slot count?
- Do we notify a student when their requirement caps out at 5 tutors?
- What happens to a tutor's unlocked leads if their account is later suspended?
- Should an expired requirement be re-postable in one click?
- Do we need Hindi (or a regional language) UI at launch, or is English-first acceptable for the initial city?
- How do we handle a tutor who serves multiple cities — does the travel radius model cover it?

---

## 6. Ready to start now

M0–M5 are complete. What remains in M6 splits cleanly into *blocked on you* and *needs a tool run*.

**Blocked on a decision or an account — nothing engineering can do first:**

1. `M6-07.1` **Choose a host.** Unblocks `M6-07.2`, `M6-07.6`, and makes `T20`, `T21` and `T13` fixable.
2. `M6-07.2` **Managed PostgreSQL with a _tested_ restore.** The highest-risk item on the board: an untested backup is a belief, and the day it matters is the worst day to find out.
3. `D5` **SMS provider**, then `M6-10.1`. Researched, undecided, and now the thing standing between the product and real users signing in.
4. `D2` **Is `apnatutor.in` available?** Still unchecked, and it gates `M6-07.5`.
5. `M6-10.4` **Razorpay live keys** — needs a verified merchant account.
6. `M6-09.1` / `M6-09.2` — **a lawyer** over the terms and privacy drafts.

**Needs a tool run rather than a code change:**

7. `M6-03.3`, `M6-06.1`, `M6-06.3`, `M6-06.4` — contrast audit, image optimisation, Lighthouse, bundle review. Grouped as `T24`; roughly one afternoon once there is a deployed URL to point at.
8. `M6-01.3` — test on a real Android phone. Needs a phone.

> **Correction, 2026-09-04.** This list previously said of `M5-05.3`: *"Suspension is nearly free: `UserStatus.SUSPENDED` exists, the DB constraint allows it, and `AuthService` already refuses a suspended user at login and refresh. Nothing anywhere sets it — only the write path is missing."* That was wrong, and worth leaving on the record. The write path was indeed missing, but so was every read path: search filtered on `is_published` alone and the public profile lookup did the same, so setting the flag would have blocked login while leaving the tutor in every search result. An estimate built from "which column exists" rather than "who reads it" will keep making this mistake — the question to ask of any status field is not whether it can be set, but what changes when it is.

---

## Review cadence

Re-read this file at the start of each milestone. Update the "Last reviewed" date when you do. Its value decays fast if it becomes a write-only file — a stale risk register is worse than none, because it looks like the risks were considered.
