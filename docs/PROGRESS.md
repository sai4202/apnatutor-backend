# ApnaTutor — Progress Journal

> Update this **in the same session as the code change**, never later. If a session ends without a changelog entry, the next session starts blind.

**Current milestone:** M6 — Polish & launch. Everything that does not need an account, a credit card or a decision is done.
**Overall:** ████████████ **M0–M5 complete, M6 substantially done** · **252 backend + 18 end-to-end tests** · the marketplace works, can be moderated, records what it does, bounds its front door, lets people leave with their data — and now has demo data, browser tests, legal pages, CI and a production profile

---

## Next 3 actions

Read this first when resuming. Keep it to exactly three, always current.

1. **Decide the three things only you can decide**, because everything left is blocked on one of them: a host (`M6-07.1`), an SMS provider (`D5`), and whether `apnatutor.in` is available (`D2`). None is an engineering question, and each unblocks several tasks at once.
2. **`M6-07.2` — managed PostgreSQL with a _tested restore_.** Not just backups. An untested backup is a belief, and the day it matters is the worst possible day to find out. This is the single highest-risk unticked item on the whole board.
3. **Get a lawyer over `/terms` and `/privacy`.** Both are written and both say on the page that they are drafts. The refund policy needs no review — it matches SoT §3.5 exactly — but the other two carry company details that do not exist yet.

Full breakdown in [TASKS.md](./TASKS.md); open decisions and debt in [PENDING.md](./PENDING.md).

---

## Milestone status

| Milestone | Status |
|---|---|
| M0 — Foundation | ✅ Done (2026-08-31) |
| M1 — Accounts, profiles & trust | ✅ Done |
| M2 — Catalog & discovery | ✅ Done (2 subtasks deliberately deferred) |
| M3 — Requirements & lead loop | ✅ Done (2026-09-01) |
| M4 — Credits & payments | ✅ Done (2026-09-01) |
| M5 — Reviews, admin & trust | ✅ Done (2026-09-04) |
| M6 — Polish & launch | 🟡 Substantially done — what remains needs a host, provider credentials, a domain, or a lawyer |

---

## Changelog

### 2026-09-04 — M6: everything that does not need an account or a credit card

**252 backend tests and 18 end-to-end tests pass.** No migration.

**The end-to-end tests found a real 500 within an hour of existing, on the pages that matter most.** `/tutors/hyderabad/mathematics` — a programmatic SEO page, the primary acquisition channel — crashed on `avgRating.toFixed(1)` for any unrated tutor. The cause is worth writing down because it is a trap the whole frontend sits in: the backend sets `default-property-inclusion: non_null`, so Jackson **omits** nulls rather than serialising them. A nullable field is therefore *absent*, not null, and `field !== null` passes on `undefined` and then reads a property of it. TypeScript could not help: every type says `| null`, so the strict check looks sound. Eleven guards across six files were wrong the same way; the note explaining it now sits at the top of `lib/api.ts`.

**The demo data is built through the real services, not SQL.** A tutor is published by the same `publish` that enforces the 60% completeness bar, an unlock spends real credits through the ledger, and a review goes through moderation before it appears. That is slower than inserting rows and buys the thing demo data is for: **if the seed runs, the flows work.** A dataset inserted directly can describe a state the application cannot reach, which is worse than no dataset.

**Eight tutors, hand-written.** A generator produces "Tutor 47 teaches Subject 3 in Locality 9", which exercises a query and tells you nothing about whether the marketplace looks trustworthy. Fees sit in the range Hyderabad tuition actually occupies, because a demo where everything costs ₹500/month teaches the wrong thing about the pricing bands.

**`lib/exampleTutors.ts` is gone — debt T11 repaid.** Three fabricated profiles powered the homepage marquee and a `/tutors/[slug]` branch. They were labelled and noindexed, and they were still fake listings on a marketplace. The homepage now shows real published tutors, best-rated first, and renders nothing at all when there are none — an empty strip beats a padded one.

**The SEO tests run with JavaScript disabled.** That is the only way to prove content is server-rendered rather than hydrated: both look identical in a browser, and the difference is whether Google sees anything. The city page, the city × subject page and the homepage's subject links are all asserted this way.

**The money-path test fails rather than skips when the tutor has no credits.** It first skipped, which reports green while testing nothing. The demo seeder now funds the dev tutor, outside its own idempotency check so a re-run tops them up.

**`DevModeGuard` now refuses a production profile with any console stub left wired up** (M6-10.5). Each of the three runs *without error*: console SMS logs the OTP instead of sending it, so nobody can sign in and every code is in a log file; console mail does the same to receipts; local disk storage accepts uploads and loses them on the next redeploy, taking tutors' ID documents with them. None produces an exception. All three produce a deployment that looks healthy and is quietly broken — which is exactly what a startup check is for.

**S3 storage exists but has never touched a live bucket.** It works against S3, R2, B2, Spaces or MinIO through an endpoint override, because egress pricing differs by an order of magnitude between them and file serving is the one cost here that scales with traffic rather than users. Writing it surfaced a smaller problem: `LocalFileStorage` and the new class had **different key-validation patterns**, which is the drift the shared `StoredUpload` was extracted to prevent — a key one backend accepts and the other refuses is a file that uploads in development and 404s in production.

**`Alerts` is a seam, not a pager.** Everything already logs its failures, which is the problem: a log full of errors has no signal left. Four events now raise a distinct, greppable `ALERT kind=...` — a rejected webhook signature, a failed webhook, a ledger mismatch, an uncredited payment. It logs today; adding a provider is a one-file change, and scattering some SDK through the payment code before that provider is chosen would mean undoing it in a dozen files if the choice turned out wrong.

**The refund policy is the one legal page that had to be exact.** It describes behaviour the code enforces line for line: the seven-day window, the eight reason codes, one dispute per lead, the freed cap slot, refunded credits carrying no expiry. A policy promising something the software does not do is worse than no policy — it is a commitment made to every tutor who reads it. If SoT §3.5 changes, that page changes in the same commit.

**The terms and privacy pages are drafts that need a lawyer.** What an engineer can get right is that every clause describes what the software actually does; what they cannot is enforceability, or the company details in the last section. Both say so on the page rather than only in a commit message.

**A skip link was the real accessibility gap.** `<main id="main">` already existed with nothing pointing at it, and global `:focus-visible` was already in place — so keyboard users were tabbing through the entire header on every page for want of eight lines.

**What is not done, and why.** Deployment, a domain, real SMS, real email and live Razorpay keys all need an account, a payment method or a decision that is not an engineering one. The contrast audit, Lighthouse and the bundle review need a run rather than a build, and the responsive pass has had no screen-by-screen audit — the mobile end-to-end project catches a broken layout, not an ugly one. All of it is marked in TASKS.md rather than quietly ticked.

### 2026-09-04 — M5-09 and M5-10: reporting, and the right to leave. **M5 is closed.**

**250 tests pass**, up from 234. Migrations V19 and V20.

**A report is a signal, not an instruction** (ADR #18). Upholding one records that a moderator agreed and changes nothing about the account it names — suspending, removing or unpublishing is done from the screen that owns that action, with its own rules and its own audit entry. The alternative makes the report button a weapon: a handful of coordinated reports removes a competitor, and it skips the one step where a person looks. `upholdingDoesNotActOnTheSubject` is the test.

**One open report per person per subject**, as a partial unique index. The number a moderator reads is how many *different* people reported the same thing, so one person filing fifty times must not look like fifty people. Partial, so the same person may report again once the first was decided — a second incident is real information, not a duplicate.

**`SYSTEM` reports are counted separately from people.** "Four people reported this tutor" and "four reports, three of them ours" are different facts, and `countDistinctReportersFor` ignores the platform's own.

**The dispute-rate signal finally has somewhere to go.** `RefundService.warnIfDisputeRateHigh` has warned into a log since M4 with a comment promising it would become an alert; that comment was corrected during M5-08 to point here, and now it raises a `SYSTEM` report instead. It is idempotent while one is open — the signal fires on every new dispute, and a queue with forty identical entries for one tutor is a queue nobody reads.

**Deletion anonymises and never removes** (M5-10.3, ADR #19). Two append-only tables reference `users.id`: the credit ledger by Invariant 1 and the audit log by M5-08.4. Removing the row would either cascade rows out of an immutable ledger or leave dangling references in one.

**The anonymised phone was the interesting part.** The column is `NOT NULL`, uniquely indexed and CHECKed against E.164 — three constraints that between them rule out null, a constant and free text. The value is `+99` plus the zero-padded user id: unique by construction, valid E.164 by shape, and `+99` is not an assigned country code so it can never collide with a real number. The alternative was to make the column nullable with a partial unique index, which would have meant relaxing a constraint that has stopped one number becoming two accounts since V2 — a live guarantee traded away for a rare case.

**Identity documents are the one thing genuinely deleted.** Aadhaar and PAN scans; nothing references them and no ledger depends on them. A storage failure does not abort the deletion — a user asking to be forgotten being told "no" because one file call failed is the worse outcome — so the reference is cleared regardless, leaving an orphaned file, which is a cleanup problem rather than a privacy one.

**The deletion copy is the feature.** Deleting forfeits a tutor's unspent credits, keeps the credit ledger, and leaves reviews they wrote published. Every one of those is a nasty surprise discovered afterwards, so all three are on the screen above the button. A dialog that asks only "are you sure?" is not consent to any of them.

**The export is offered first, and says what it leaves out.** It includes the credit ledger, which is the section a tutor actually wants. It excludes other people's contact details even where the tutor paid to see them — that is their data, not the tutor's, and an export must not become a second way to buy a contact list. `exportDoesNotLeakOtherPeople` asserts it.

**"Your data" is in the footer**, not behind the account menu. A right to a copy and a right to erasure are worth nothing if they are hard to find, and the footer is the one thing on every page.

**M5 is complete.** M0 through M5 done: accounts and trust, discovery and SEO, the lead loop, credits and payments, reviews, the admin console, the audit log, rate limiting, reporting and data rights.

### 2026-09-04 — M5-07: rate limiting, and the two places a per-IP limit is wrong

**234 tests pass**, up from 224. No migration.

**Token buckets, not fixed windows.** A fixed window lets a caller spend a full window's permits at 11:59:59 and another full window's at 12:00:00 — twice the intended rate, at the moment somebody looking for a gap would find it. A bucket refills continuously, so the burst it permits is exactly its capacity. `refillIsCappedAtCapacity` is the test: waiting longer never buys more than capacity.

**The filter runs ahead of Spring Security**, at order `-101`. A limiter behind security never sees an anonymous request to a protected route, because security rejects it first — which was debt **T19** exactly: anonymous probing of `/admin` produced a 401 and no record anywhere. In front, the flood is turned away before authentication, which is also the cheapest place to turn it away. It is registered through a `FilterRegistrationBean` rather than `@Component` plus `@Order`, because Boot auto-registers filter beans at `LOWEST_PRECEDENCE` and ignores `@Order` while doing it — a filter meant to run first would have run last, silently, still working for authenticated traffic and quietly missing the requests it was added for.

**The cost of that position is that everything in the filter is keyed on IP.** Keying on a user id there would mean reading it from a token nothing has verified yet, and an unverified `sub` claim is attacker-chosen — a forged one lets an attacker spend a victim's allowance rather than their own. Per-user limits therefore live in the service layer, where the identity has been proven.

**Two places a per-IP limit is actively wrong, and both are handled.**

- **Carrier-grade NAT.** Indian mobile networks share addresses heavily, so one address can be a neighbourhood. Every limit here is sized for "one address behaving badly", not "one person behaving normally"; a limit tuned to a single human would lock out a city block. What protects an individual number is still the per-phone OTP cap from M1-03.
- **Server-side rendering.** The Next.js frontend renders public pages on the server, so every SEO page view reaches `/api/v1/public/**` from the frontend server's one address rather than the visitor's. A sensible-looking per-IP limit there would have throttled the entire site the first time traffic arrived. The public allowance is 3,000/min for that reason, and the real fix is forwarding the visitor address. Debt **T21**.

**The unlock limit sits after the replay check, and that ordering is the whole point.** `LeadUnlockService.unlock` deliberately returns the existing unlock when a tutor already owns it, because the failure it prevents is a tutor on a patchy connection whose response was lost retrying and being charged twice. Rate-limiting before that check would refuse exactly those retries — turning a flaky network into a lost credit, which is the worst outcome the money path can produce. Only a genuinely new unlock counts against the allowance, and `replayedUnlocksDoNotCountAgainstTheAllowance` asserts it.

**The bucket map cannot grow without bound.** Keys come from the network, so an unbounded map keyed on them is itself the denial of service the class exists to prevent. Buckets are swept past 50,000, and a **full bucket is dropped** — a caller holding all their permits is indistinguishable from one never seen, so eviction is free rather than a decision about whom to forget. If a sweep frees nothing, that is a distributed flood and it says so at WARN.

**Rate limiting is skipped entirely in dev mode**, for the same reason the OTP cap exempts seeded accounts: they exist to be signed into repeatedly. `DevModeGuard` already refuses to start with dev mode on alongside a real SMS provider or a `prod` profile, so this cannot be why a production deployment is unprotected.

**The suite runs with the limiter on but effectively unlimited.** Every test drives MockMvc from one loopback address, and a realistic limit would have the ~230 requests of a full run throttle each other — failures with nothing to do with what the tests assert. The filter still runs, so a change that broke it outright would still show up; `RateLimitIntegrationTest` sets its own low limits and gets its own context, and gives each test a distinct `X-Forwarded-For` so the two auth tests cannot spend each other's permits.

Also shipped: `RATE_LIMITED` handled by code on the login screen and the lead feed. The two auth limits are worded differently on purpose — "too many codes for this number" and "too many attempts from your connection" are different facts, and on a shared mobile network the second is usually not the user's doing. The unlock message says "you have not been charged" explicitly, because a tutor one tap from their credit balance will assume the worst.

### 2026-09-04 — M5-08: the audit log, and one honest gap

**224 tests pass**, up from 217. Migration V18.

**The rule is the route, not the call site.** `AuditInterceptor` writes exactly one entry for every mutating request under `/admin`, so an endpoint added next year is audited because of where it lives rather than because its author remembered. What an interceptor cannot know is what changed, so services describe themselves through `AuditContext` on the way past and the two are combined at `afterCompletion`. An action nobody described is still recorded, as its route and its outcome. Same argument as ADR #14: a thing every future code path must remember to do is a thing that will eventually not be done, and here the failure is silent — an action nobody logged looks exactly like an action nobody took.

**The write runs in its own transaction**, `REQUIRES_NEW`, in a separate bean. A refused or failed action has rolled its own transaction back by then, and the record of a failed attempt is the entry most worth keeping; joining the caller's transaction would roll the evidence back with the thing it was evidence of. Third time this trap has appeared on this project, after `WebhookEventRecorder`. The write also never throws — a failed audit write must not turn a completed suspension into a 500, because the admin would simply do it again.

**Immutable at the database, not by convention.** `UPDATE` and `DELETE` on `audit_log` raise, exactly as `credit_transactions` does. "We only ever insert" is a claim about code; the first thing anyone covering their tracks reaches for is an `UPDATE`. There is deliberately no retention job — when one is needed it should be a documented policy with its own migration, not a `DELETE` somebody adds quietly.

**An honest gap, asserted rather than papered over.** The first version of this claimed in three places — javadoc, migration comment and a test — that an unauthenticated attempt on an admin route is recorded with a null actor. It is not. `anyRequest().authenticated()` rejects it in the security filter chain, before any interceptor runs. The test failed, which is what a test is for; the comments would have gone on being wrong indefinitely. All three now say what actually happens, and there is a test named `anonymousRequestsAreNotRecorded` that asserts the absence so nobody re-adds the claim. The case that does matter is covered: an authenticated non-admin probing `/admin` is recorded, because `@PreAuthorize` is evaluated during dispatch. Anonymous probing belongs to the access log and to `M5-07`. Debt **T19**.

**Two kinds of read are audited; the rest are not.** `/admin/files/**`, the only route to an ID or education document, and `/admin/requirements/**`, the only projection of an enquiry carrying the student's own phone number — that one is debt **T18**, and this is what closes it. Reading those *is* the sensitive act. Every other admin `GET` is left alone: working a queue means loading it repeatedly, and an entry per poll buries the entries that matter, which is a way of losing an audit log without deleting anything.

**`warnIfDisputeRateHigh` was not moved here, and the comment saying it would be has been corrected.** It fires when a tutor's dispute rate looks like abuse — the platform noticing something about a tutor, not an admin doing something. Putting it in `audit_log` would blur a table whose whole value is that every row has a person behind it. It belongs in the abuse triage queue at `M5-09.2`, and the javadoc now says so.

**The console reports what a suspension does not do.** Suspending an account now says, on the screen, that search and the profile page close immediately but the user's own signed-in session can last up to fifteen more minutes. That is debt **T15** — stateless tokens, ADR-adjacent, not worth a revocation list yet — and an admin who is not told will report it as a bug.

Also shipped: `GET /admin/audit` with filters on actor, target, action and outcome, and the `/admin/audit` screen, where before/after sits behind a disclosure because the log is scanned far more often than it is read. Reading that screen is itself audited.

### 2026-09-04 — M5-05 and M5-06: the admin console, and a suspension that suspends

**217 tests pass**, up from 205. Migration V17.

**The finding that shaped this chunk: `SUSPENDED` was doing almost nothing.** `users.status` has carried it since V2, and `User.canAuthenticate` reads it, so a suspended account cannot log in. Nothing else looked at it. `TutorSearchRepository.buildFilter` narrowed on `tp.is_published = TRUE` alone, and `getPublicProfile` filtered on the same flag — so a tutor suspended for abusing a parent stayed in every search result and kept their profile page, and tutors kept paying to reach a student we had judged fraudulent. Building the suspend button first and discovering this afterwards would have shipped a feature whose only real effect was on a database column.

**Suspension is enforced by the read queries, not by a flag** (ADR #14). The obvious fix — unpublish the profile on suspension, republish on reinstatement — is cheaper per read and wrong in the way that matters: it is state, and state has to be set correctly by every path that ever suspends anyone, forever. `findPublishedActiveById` and one extra `EXISTS` in the search filter cannot be forgotten by a future code path. The cost is a primary-key lookup per candidate row on the hottest query in the application.

**Suspending a student takes their live enquiries down, and that refunds tutors.** Otherwise we would keep selling introductions to an account we have just called fraudulent, and every one of those charges becomes a dispute we would uphold anyway — the same money, paid out one support conversation at a time, plus the tutor's opinion of us. `HIRED` and `EXPIRED` enquiries are untouched: those introductions happened. A suspended tutor's unlocks are likewise left alone, and the asymmetry is the point — they paid for introductions that were real.

**A takedown costs money, deliberately.** `RequirementModerationService.remove` refunds every active unlock in the same transaction as the status change, with the requirement row locked so an unlock cannot land between the sweep and the change. It writes **no `refund_requests` row** (ADR #15). A dispute is a tutor's claim; this is the platform conceding unasked, and recording it as a dispute would inflate `disputeRateFor` — the one number used to judge whether a tutor games refunds — with refunds they never asked for. There is a test named for it.

**Restoring does not claw the credits back.** Reversing a refund days later, possibly into a negative balance because the tutor has since spent it, makes our mistake theirs. The cost of a wrong takedown stays here, which is also what keeps the decision careful. The restored status is recomputed from expiry and the unlock count rather than remembered: an enquiry that expired while it was down comes back expired, and there is a test for that too.

**`RefundService` had been logging the moderation queue for a milestone.** `noteRepeatedlyDisputedRequirement` warned "consider closing it" at three disputes, into a log nobody greps. That is now `findDisputedAtLeast`, and it counts `COUNT(DISTINCT tutor_id)` rather than `COUNT(*)` — the unique index on `unlock_id` stops a tutor disputing one unlock twice but says nothing about one tutor disputing several unlocks of the same requirement, which cannot happen today and would quietly become a way to frame a student if it ever did.

**Admin accounts cannot be suspended.** Not because admins are above it, but because suspending the last one locks everybody out of the console with no way back in through the product, and the state that gets you there is one misclick in a user list.

**Metrics return a numerator and a denominator, never a percentage.** On a platform this young the honest reading of "8%" is "two out of twenty-five", and a dashboard that hides the denominator teaches whoever reads it to trust a number that moves four points when one tutor signs up. The whole dashboard is one snapshot in one transaction — assembling it from eight calls lets a signup land between a numerator and its denominator and produce a conversion rate above 100%.

**The daily series is built from `generate_series`, bucketed in Asia/Kolkata.** Days with no activity are zeros rather than gaps: a chart that omits empty days draws a straight line through a dead week and makes an outage look like steady trade. The timezone matters because everything is stored in UTC, and a boundary five and a half hours out puts an evening signup on the wrong day for every person reading the screen.

**The document viewer fetches, it does not link.** `/admin/files/**` is `@PreAuthorize`d and the access token lives in memory by design, so a browser has nothing to attach to an `<img src>`. The document is fetched with `authFetch`, handed to the DOM as a blob URL, and the URL is revoked on unmount — an object URL pins its blob for the life of the document otherwise, and leaking an Aadhaar scan per card into the tab's memory is the wrong thing to do with those particular bytes. Nothing loads until the reviewer asks, so opening a queue of twenty does not pull twenty identity documents into a browser.

**Numbering correction.** `TASKS.md` reserved `V17` for the audit log. This chunk needed a migration first and the audit log will want to record against it, so admin moderation is **V17** and the audit log is now **V18**. Second numbering clash on this project, after V10/V11 — the directory is the authority, not the task file.

Also shipped: the console itself — layout, sidebar, admin route guard, `robots: noindex`, and seven screens (verifications, reviews, disputes, enquiries, users, credits, pricing) plus the dashboard. `AccountMenu.tsx:24` has linked `ADMIN → /admin` since M1; it no longer 404s.

### 2026-09-01 — M5-01…M5-04: reviews, and a rating that finally means something

**205 tests pass**, up from 187. Migration V16.

**This chunk was chosen because M2 had already built the consumer.** `tutor_profiles.avg_rating` has existed since V6, indexed for sorting in V8, read by `TutorSearchRepository`, backing the `minRating` filter, and ordering the lead-notification fan-out. Nothing wrote it. Four pieces of shipped code were ranking on a column where every tutor was tied at NULL. `ReviewApiTest.searchSortsAndFiltersOnRealRatings` is the test that would have failed yesterday for want of data rather than logic.

**Aggregates are recomputed, never incremented** (ADR #12). `review_count + 1` drifts the moment two moderators approve together — both read the old value, both write the same new one, and a review is gone from the count for good. Worse, it cannot be undone: withdrawing a published review would need to know the old rating to subtract it. One SQL statement derived from the table serves approval, rejection and withdrawal, cannot get a sign backwards, and is idempotent enough that `recompute-ratings` is the repair tool as well as the backfill. The same reasoning that makes the ledger authoritative and the wallet balance a cache.

**Withdrawing the last review returns a tutor to NULL, not 0.0.** Search sorts `NULLS LAST`, so a zero would rank an unrated tutor below every one-star tutor on the platform. There is a test named for it.

**Two gaps in the specs, found and closed.**

- `TASKS.md` called for `V10__reviews.sql` and `V11__audit.sql`. Both numbers were taken — V10 is `billing`, V11 is `notifications`. Reviews are **V16**; the audit line now says V17.
- `M5-03.2` required replies to be moderated, but `SOURCE_OF_TRUTH.md` §5 defined `tutor_reply` as a bare text column with no status. There was nowhere for a reply to wait. It now carries `tutor_reply_status`, `tutor_reply_at` and `tutor_reply_moderated_by`, decided independently of the review — refusing a tutor's answer is no reason to unpublish the student's words.

**A refunded unlock does not confer the right to review.** The eligibility query requires `status = ACTIVE`. A refund means the tutor successfully argued the lead was worthless and we agreed; letting that student then rate them makes every dispute an invitation to retaliate, and tutors would learn to stop disputing. That is a rule about incentives, not about data, so it is written into SoT §3.6 rather than left in a query.

**M5-04.2 is structural, and asserted as a property.** No request record has a field a client could bind an aggregate to. `ReviewDtoContractTest` walks the record components of every request shape rather than posting one payload at one endpoint — it fails the day somebody adds `avgRating` to a DTO, which is the failure worth catching.

**Two ids for a tutor, deliberately** (ADR #13). The database keys reviews by user id, because eligibility joins `lead_unlocks` and every money table keys the tutor that way. The API takes `tutor_profiles.id`, because that is the only tutor identifier the frontend has anywhere else. The translation happens once, in the service.

**Screens nobody had written a task for.** `M5-06` covers only the admin UI, so the student and tutor review screens existed in no task at all — a review nobody can write is dead code. Added as `M5-11`. The student writes one inline on the enquiry the tutor answered, which is where they already are when they have an opinion; a flow that starts with finding the tutor again collects far fewer reviews, and reviews are the scarce input. The tutor sees pending reviews too — hiding them until publication means the first they hear of a complaint is a parent quoting it back. Reviewer names are never shown to the tutor, and are masked to "Priya S." publicly.

**Moderation is two queues, not one.** A pending review is a student who thinks they were ignored; a pending reply is a tutor who cannot answer criticism already published about them. Merging them buries whichever is rarer.

Also shipped: `unpublish`, for a review reported after it went live; notification on publication, on rejection with the reason, and to the student when a reply goes public; and one-query name lookups on both list endpoints, so neither is an N+1.

### 2026-09-01 — M4 closed: money in, exactly once, and provably

**187 tests pass**, up from 154. Migrations V13, V14, V15.

Everything in M4 serves one claim: credits are granted exactly once, only for money a provider confirmed, and it stays provable months later.

**Packages are admin-editable rows, per your instruction.** The prices are still a hypothesis (PENDING D3), but being wrong now costs a settings edit rather than a release. `payments` copies the credits and amount at order time, so a repricing next month cannot rewrite what someone paid today. Retiring the last active package is refused — an empty storefront is an outage, not a pricing decision.

**The browser is never believed.** Razorpay's widget calls back into the page on success, and crediting there would be crediting on a request the user's own browser makes. Amounts and packages are read server-side; a payload claiming ₹999,999 grants exactly the package's credits, and there is a test for it. The checkout signature is verified only to move the UI along.

**Once-only crediting, in three layers**: the payment row loaded `FOR UPDATE`, `credited_at` on the row, and a unique index on `(provider, provider_payment_id)`. Three deliveries of one event grant once; so do `payment.captured` and `order.paid` for the same purchase, which event-id deduplication alone would miss.

**The webhook endpoint is unauthenticated by necessity** — Razorpay holds no credential of ours — so the HMAC is the only thing between it and free credits. It fails closed on a missing secret, a missing header or a mismatch, compares in constant time, and records every attempt with its raw body. Rejected deliveries are kept: a run of them is how anyone learns the endpoint is being probed.

**`WebhookEventRecorder` is a separate bean.** Third time this trap has come up here: `@Transactional` is proxy-applied, so `REQUIRES_NEW` on a self-invoked method does nothing at all, and the audit row would roll back with the failure it was recording.

**A stub gateway** meant the whole flow was built and tested before any Razorpay account exists. It verifies signatures against a fixed secret rather than accepting everything — a stub that trusted every request would let a signature bug reach production behind a green suite. `DevModeGuard` now refuses to start under a `prod` profile with no Razorpay keys: that combination looks like a perfectly healthy deployment while the stub quietly issues credits for money that never moved.

**A design the database rejected, correctly.** The expiry job first wrote a zero-amount `EXPIRY` entry against grants spent before they lapsed, purely to avoid reprocessing them. `credit_transactions_amount_nonzero` refused it. The constraint was right and the design was wrong — "which grants have been through expiry" is bookkeeping about the ledger, not an entry in it, so it moved to its own table in V15.

**Expiry never takes a balance below zero.** Credits are fungible, so a 10-credit grant is still on record after 8 are spent; writing off the full 10 would bill the tutor for credits they already used. Spent credits are never clawed back — given the choice, the platform takes the loss.

**Refunds free the cap slot.** The property easiest to forget, and the one with a test named for it. A parent promised five responses who got one unusable one should end up with five usable ones. Approval also reopens a requirement that had capped out. Refunded credits carry no fresh expiry: they were paid for once already.

Also shipped: signup bonus (guaranteed once by a partial unique index, not by the check preceding it), stale-order cleanup, dispute reasons as countable codes, two abuse signals that flag rather than block, a printable receipt with no PDF dependency, and the tutor wallet UI in which "not enough credits" is a link to top up rather than a dead button.

### 2026-09-01 — M3 closed: the lead loop works in a browser

**Shipped**

- **Student screens** (`M3-10`): post-requirement form, enquiry dashboard, responding-tutors list with contacts revealed, hire/withdraw. The form is fillable before signing in and shows a live credit quote — demanding an account before someone has expressed what they want loses most of them.
- **Tutor screens** (`M3-11`): lead feed with masked previews, unlock confirmation showing cost *and* resulting balance, post-unlock contact reveal, my-leads, wallet balance visible throughout and in the header.
- **`AccountMenu`** — the header showed "Sign in" even when signed in, so a signed-in user had no route to their own pages. Extracted as the only client component in the header, so public SEO pages keep their server rendering.
- **Student profile screen** (`M1-12.6`) and the **OTP resend cooldown** (`M1-11.6`), closing the last two open M1 subtasks.

**Two behaviour changes, both found by walking the money path**

- **Only published tutors see leads.** The feed matched on subjects and locations alone, so a tutor who had never published could unlock a lead — putting a stranger on a parent's phone with no profile to check them against. `findLeadFeedFor` and the new `findTutorsToNotify` both require `is_published` and must stay in step.
- **Unlocking is now replay-safe.** A repeat returns the unlock the tutor already holds instead of `LEAD_ALREADY_UNLOCKED`. The case that matters is a tutor on a patchy mobile network whose request succeeded but whose response never arrived: answering the retry with an error left them charged and holding nothing. This is `M3-07.1` satisfied structurally rather than with the `Idempotency-Key` header the task named.

**`M3-09.3` was never wired.** `NEW_MATCHING_LEAD` existed in the enum and was sent by nothing, so tutors only found leads by remembering to check the feed. Now fanned out at posting time to 4× the enquiry's unlock cap, ordered by approved verifications then rating — messaging everyone who matches would mean most recipients arrive to find the lead taken.

**Fixed**

- `npm run lint` was red before this session and is now clean. React 19's `set-state-in-effect` rule rejects a bare `void load()` in an effect; awaiting inside the effect satisfies it (verified by probe — the rule accepts a `useCallback` that sets state, it just cannot see through the un-awaited call). Also renamed `useTestAccount`, which ESLint treated as a hook because of the `use` prefix.
- `docs/TASKS.md` had **the entire M3 section unticked** while the summary table claimed 9 done. Ticked honestly this session, with the two behaviour changes written into the file.

**154 tests pass**, up from 152. New: an unpublished tutor sees no leads; posting notifies matching tutors and only matching ones. The duplicate-unlock concurrency test now asserts what actually matters — both calls may succeed, but only one row and one charge.

### 2026-08-31 — Project born

**Decided**
- Name **ApnaTutor**, India-first, lead-credit business model (not booking-commission).
- Spring Boot REST backend + Next.js frontend + native PostgreSQL, no Docker.
- Full rationale for all ten founding decisions is in the SoT decision log.

**Environment audit (this machine)**
- Found: JDK 26, Maven/Gradle caches, IntelliJ.
- Missing: Node, Git, PostgreSQL, Maven CLI, any LTS JDK.
- Resolution: installed Node 24.19.0 LTS + Git 2.55.0 via winget; PostgreSQL 18 installing. Maven CLI not needed — the Maven wrapper (`mvnw.cmd`) is bundled. Stayed on JDK 26 rather than installing an LTS, because Spring Initializr lists Java 26 as supported for Boot 4.1.1 (ADR #4, with Temurin 21 as the documented fallback).

**Built**
- Repo at `C:\Users\HP\IdeaProjects\apnatutor`.
- Backend scaffolded from Spring Initializr: Boot 4.1.1, Java 26, Maven, with Web MVC, Security, Data JPA, Validation, Actuator, Flyway, PostgreSQL driver, Lombok, config processor.
- `docs/SOURCE_OF_TRUTH.md`, `docs/PLAN.md`, `docs/PROGRESS.md`.

**M0 completed — verified, not assumed**
- PostgreSQL 18.6 installed as service `postgresql-x64-18`; `psql` added to the user PATH; `apnatutor` role and both databases created with `pg_trgm`.
- Next.js 16.3.3 / React 19.2.8 / Tailwind 4 scaffolded.
- Single repo-root `.env` now feeds both apps — backend via `spring.config.import` (no dotenv dependency; Spring parses it natively with the `[.properties]` hint), frontend via `loadEnvConfig("..")` in `next.config.ts`. JWT secret generated with `crypto.randomBytes(48)`.
- `SecurityConfig` added: default-deny with an explicit public allowlist, CORS bound to the configured frontend origin, BCrypt encoder.
- **Verification actually run:** `mvnw verify` → BUILD SUCCESS, 1 test, 0 failures. `npm run build` → clean, TypeScript passed. `/actuator/health` → `status: UP`, `db: UP`. Page fetched from `localhost:3000` contained three server-rendered `UP` pills — so Next→Boot→Postgres is proven end to end, and proven *in the HTML source*, which is what the SEO strategy depends on.

**Notes for next time**
- Spring Boot 4.x renamed things: the starter is `spring-boot-starter-webmvc`, not `-web`, and test support is split into per-starter `*-test` artifacts. Boot 3 tutorials will not match this `pom.xml`.
- Local Postgres superuser password is `postgres`; app role is `apnatutor`/`apnatutor`. Local only.
- Deliberately deferred from M0 to M1: global error handler, `ApiError` shape, pagination wrapper, springdoc. There were no endpoints to apply them to yet, and building them against imagined endpoints tends to produce the wrong abstraction.

### 2026-08-31 — V1 task tree

**Added**
- `docs/TASKS.md` — 71 tasks with subtasks across M0–M6, permanent `M1-03.2` style IDs meant for commit messages.
- `docs/PENDING.md` — open decisions, blockers, deliberate debt, risk register. Kept deliberately distinct from the task list: it holds what a checklist cannot, and is not a copy of the unticked boxes.

**Restructured to stop status drift**
- PLAN.md lost its M1–M6 checkboxes; it now owns milestone *shape and rationale* only. TASKS.md is the sole place a box gets ticked. Its open-questions table moved into PENDING.md §1.

**Two sequencing bugs found and fixed while decomposing**
- **Catalog moved M2 → M1** (`M1-06`). Tutor profiles need subjects, boards, grades and locations to attach to; the onboarding wizard is unbuildable without them. Would have stalled mid-M1.
- **Credit ledger moved M4 → M3** (`M3-05`). The unlock endpoint spends credits, so M3 could not have been finished or tested with the ledger a milestone away. Admin credit grants now let the whole loop be exercised before any payment code exists.

**Also captured**
- The CSRF gap on the refresh endpoint is now tracked debt (PENDING.md T1) with its repayment pinned to `M1-04.5`, rather than living only as a code comment.

### 2026-08-31 — Backend and frontend split into independent projects

**Restructured** (ADR #11). `backend/` and `frontend/` are now self-contained: each has its own `.env`, `.env.example`, `.gitignore`, `README.md` and `CLAUDE.md`, and neither reads a file outside its own directory. One git repo is retained so an API change and its client update can land in a single commit.

- `.env` → `backend/.env`; new `frontend/.env.local`. **This reverses the earlier shared repo-root `.env`**, which coupled the two at the filesystem level.
- `scripts/` → `backend/scripts/` (they are database scripts, backend-owned).
- `application.yml` now imports `./.env` only, never `../.env`.
- `next.config.ts` returned to a plain config — the custom `loadEnvConfig("..")` existed *only* because the file was in the parent. Next loads `.env.local` from its own directory by default.
- Root `.gitignore` trimmed to genuinely cross-cutting rules; each project owns its build-specific ones.
- Shared product docs stay in `docs/` — the business rules bind both sides, and splitting them would invite two diverging copies.

**Caught during the split:** `frontend/.gitignore` ships with a blanket `.env*` rule from create-next-app, which would have silently swallowed the new `.env.example`. Added a `!.env.example` negation.

**Verified after restructuring:** `mvnw verify` green, `npm run build` clean, and the full chain re-checked live — `/actuator/health` `UP`/`db: UP` and three server-rendered `UP` pills at `localhost:3000`.

### 2026-08-31 — M1-01 shared web plumbing (mostly)

- `ErrorCode` — stable machine codes with HTTP status attached to each, so the two cannot drift across handlers. Includes the M3/M4 codes SoT already names as contract.
- `ApiError` — the single error shape; `fieldErrors` omitted from JSON unless present.
- `ApiException` + factories, with stack-trace capture disabled: these are expected outcomes on hot paths, not faults.
- `GlobalExceptionHandler` — body/parameter validation, unreadable body, authentication, access denied, unmapped URL, and a catch-all. **Internals are logged, never returned.**
- `PageResponse<T>` with an entity→DTO mapping overload. Spring's `Page` is deliberately not serialised directly — its JSON shape is a version-dependent implementation detail.
- `CorrelationIdFilter` — honours an inbound `X-Correlation-Id`, **sanitised and length-capped** before it reaches a log line, clears MDC in a `finally` so pooled threads cannot inherit a stale ID. Log pattern updated to print it.

**`M1-01.4` (springdoc) — first recorded as blocked, then unblocked the same day. See the correction below.**

### 2026-08-31 — M1-01.4 springdoc: correction and unblock

**The blocker was not real.** I called springdoc unavailable for Spring Boot 4 on the strength of Maven Central's `solrsearch` API reporting 2.8.6 as the latest version. That field is cached and lags actual releases. The repository's own `maven-metadata.xml` lists 3.0.0-M1 through **3.1.0**, and the 3.x line is the Boot 4 line.

**Now working:**
- springdoc-openapi 3.1.0 pinned via a `springdoc.version` property (not managed by the Boot parent).
- `OpenApiConfig` — API metadata plus a declared `bearerAuth` scheme, so Swagger UI's Authorize button works the moment M1-04 issues tokens. Without it every protected endpoint would be untestable from the browser.
- Docs gated behind `APNATUTOR_API_DOCS_ENABLED`, **to be turned off in production** — an always-on schema dump is free reconnaissance.
- SecurityConfig permits the exact doc paths, including bare `/v3/api-docs` and `/v3/api-docs.yaml`.

**Verified live, not just compiled:** `/v3/api-docs` returns an OpenAPI 3.1.0 document titled "ApnaTutor API v1" with the `bearerAuth` scheme present, and `/swagger-ui.html` returns 200.

**Lesson recorded in `backend/CLAUDE.md` and PENDING.md:** to check whether a dependency version exists, read `repo1.maven.org/.../maven-metadata.xml`, not the search API. M1-01 is now complete except `M1-01.6` (idempotency).

### 2026-08-31 — M1-02, M1-03, M1-04: identity, OTP and JWT sessions

Phone + OTP authentication works end to end. **42 tests pass**, and the flow was also exercised live against the dev database.

**Schema** (`V2__identity.sql`, `V3__idempotency.sql`)
- `users`, `otp_codes`, `refresh_tokens`, `idempotency_keys`, with enum values constrained in the database as well as in Java, and `set_updated_at` triggers throughout.
- Email uniqueness is a *case-insensitive partial* index — two accounts must not differ only by capitalisation, but any number may have no email.

**`PhoneNumbers`** — E.164 normalisation, which turned out to matter more than expected. Without it the same person typing `98765 43210` and `+919876543210` gets two accounts, splits their reviews and credits across both, and sidesteps the OTP send-rate limit by varying the formatting. 22 unit tests cover it.

**OTP** — `SecureRandom` codes, BCrypt-hashed before storage, constant-time comparison, single-use, superseded by any newer code, 5 attempts per code, 5 sends per hour per phone. Registered and unregistered numbers return byte-identical responses, asserted by test — otherwise the endpoint is a free oracle for discovering which numbers hold accounts.

**Sessions** — 15-minute HS256 access tokens via Spring Security's Nimbus support (chosen over JJWT, which would have dragged in Jackson 2 against Boot 4's Jackson 3). 30-day refresh tokens stored as SHA-256, never in the clear, rotated on every use, with family-wide revocation on reuse. Bearer validation uses Spring's `oauth2ResourceServer` rather than a hand-rolled filter.

**Debt T1 repaid** (`M1-04.5`): the refresh route is cookie-authenticated, so the global CSRF disable does not protect it. It now requires `SameSite=Strict` plus an `X-Refresh-Request` header that HTML forms cannot set.

#### Three bugs caught before they shipped

1. **`ddl-auto: validate` caught a schema drift** — `token_hash` declared `CHAR(64)` in SQL against a `String` field expecting `VARCHAR`. `VARCHAR` is the better choice regardless: `CHAR` space-pads, which is a quiet hazard for a value compared for exact equality.

2. **The OTP attempt counter was being rolled back.** Recording a failed attempt and then rejecting the request are contradictory demands on one transaction: the rejection throws, the transaction is marked rollback-only, and the increment is discarded. The cap would never fire and a six-digit code — one million possibilities — would be brute-forceable. Fixed with `OtpAttemptRecorder` (`REQUIRES_NEW`).

3. **The same bug in refresh-token reuse detection**, found by the test written for it. `rotate()` revoked the compromised family and then threw, rolling the revocation back — detection that detects and then forgets, leaving a stolen token working until expiry. Fixed with `TokenFamilyRevoker` (`REQUIRES_NEW`).

> **Pattern worth remembering:** any security decision that must survive the exception reporting it needs its own transaction. Two instances in one milestone suggests there will be more — the M3 unlock path is the next place to watch.

**Also found:** Flyway's `cleanOnValidationError` was **removed in Flyway 9/10**, so the setting in `application-test.yml` was silently doing nothing. Replaced with an explicit `FlywayMigrationStrategy` in `TestFlywayConfig` that cleans and re-migrates, which also gives every test run the from-zero rebuild `M6-07` depends on.

### 2026-09-01 — M1-06 catalog, and the frontend design system

**Catalog** (`V4__catalog.sql`, `V5__catalog_seed.sql`) — 70 subjects across 7 categories, 10 boards, 19 grade levels, 10 cities, and localities (Hyderabad 20, Bengaluru 14, others shallow). Public endpoints under `/api/v1/public/catalog`, cached 6 hours: this is read on nearly every page load, changes maybe monthly, and is identical for every visitor.

**Launch-city assumption made rather than blocking.** Hyderabad is seeded deepest. Slugs only become permanent once a search engine indexes them, which is M6, so this stays cheap to change until then. Other cities are deliberately shallow — seeding hundreds of localities where we have no tutors creates empty pages, which `M2-08.4` has to mark `noindex` anyway. Decision D1 remains open in PENDING.md.

**Design system** — white ground, a single blue accent. Blue is doing real work here rather than being a preference: this is a marketplace where a parent hands a stranger their phone number and lets them into their home, so the palette has to read institutional and safe. One accent hue used sparingly also means anything rendered in blue is unambiguously *the* action on the page.

- Tokens in `globals.css` via Tailwind 4 `@theme`: a blue ramp, slate neutrals (a trace of blue so they sit with the accent rather than fight it), a constrained ~1.25 type scale, and brand-tinted shadows.
- Primitives in `components/ui.tsx` — hand-rolled rather than a component library, which for this many elements would cost more in bundle size and override-fighting than it saves.
- One `Container` sets page width everywhere, so section edges line up.
- 44px minimum touch targets; most traffic will be a thumb on a mid-range Android phone.
- One consistent `:focus-visible` ring, `prefers-reduced-motion` respected, a skip-to-content link, and `sr-only` labels where a visible one would clutter.
- **No dark mode, deliberately.** Every screen is designed against white, and a half-considered dark variant is worse than none.

**Pages** — landing, `/tutors`, `/login`, `/for-tutors`, `/post-requirement`. All Server Components except login. The homepage prerenders statically with hourly revalidation and ships **zero client JavaScript**, which is what the SEO strategy actually depends on.

**Login is real** and drives the M1 auth API end to end. The access token is held in React state and nowhere else — not `localStorage`, not `sessionStorage`, since anything readable by JavaScript is readable by a successful XSS. Refresh-on-load belongs in a shared auth provider (`M1-11.3`).

**Empty states are honest.** `/tutors` says there are no tutors rather than rendering fake cards, and `/post-requirement` disables its fields rather than silently discarding input. A demo that looks populated but is not makes real progress impossible to distinguish from a mockup.

**Caught while building:** Spring Data does not scan repository interfaces nested inside a class — the failure is an unhelpful "no qualifying bean" at startup. Also renamed `Location.city_` to `cityLevel`, because Spring Data treats `_` in a derived query name as a property-path separator, so `findByCity_True` would parse as `city.true`.

### 2026-09-01 — Dev mode with seeded test accounts, and a real background

**Signing in with no SMS provider.** A single `apnatutor.dev.enabled` switch, not three independent flags — one thing to turn off is one thing to forget to turn off. It seeds one account per role (`9999900001` student, `9999900002` tutor, `9999900003` admin, OTP `123456`), bypasses SMS for those numbers, skips their hourly send cap, and returns generated codes in the `/auth/otp/request` response so the login screen can fill them in.

**`DevModeGuard` is the control that makes this safe.** It refuses to start the application if dev mode is on alongside a real SMS provider or a `prod` profile. Left enabled in production, seeded accounts with a published OTP are an unauthenticated login for anyone who reads the README — so this is enforced by a startup failure, not a comment. Four tests cover it.

Seeded through an `ApplicationRunner` rather than a Flyway migration, deliberately: a migration runs in every environment it reaches, so published-credential accounts would land in production on the first deploy.

Test accounts are also **off in the test profile** — the auth tests assert real rate limits and random codes, and a fixed OTP would quietly bypass exactly what they exist to verify.

**Background redesign.** The flat white was reading as unfinished. Now layered: aurora colour fields, a masked dot field, hand-placed blurred orbs, and an feTurbulence film grain — the grain being the layer that actually matters, since it breaks up the smooth gradient ramps that band visibly on cheap panels, which is most of this audience's hardware. Section boundaries use a fading hairline rather than a full-width border. All CSS, no images, no JavaScript.

**Deliberately against the current trend.** The 2026 SaaS design writing converges on dark mode plus glassmorphism as a default. That language is aimed at developers evaluating B2B tools; here the visitor is a parent deciding who to let into their home, and dark glass reads as a crypto product rather than a trusted service. White and blue stays.

Hero stat tiles show real catalog counts (70+ subjects, 10 cities) and the unlock cap — capability claims, not invented user numbers, which the product could not back up on day one.

### 2026-09-01 — Landing page design iteration (three commits, one of them a revert)

**A design was built and rejected. Recording it so nobody rebuilds it.** The first attempt layered aurora gradients, blurred orbs and film grain over the white ground. The user's verdict was blunt and correct: it added decoration where the page needed hierarchy. Reverted in `62dcda9`. If richer backgrounds come up again, the lesson is that texture is not the lever — layout and type are.

**What replaced it, after asking rather than guessing again.** Given four directions, the user chose *show the product, don't describe it*:

- Hero is now asymmetric: pitch and search on a hard left axis, tutor result cards on the right. A parent understands "verified tutors near you" in about a second from seeing a badge, rating, fee and locality — body copy cannot do that.
- `TutorCard` is the **real** component, not hero art. It renders live search results in M2, so its interface is the shape the search endpoint must return.
- Category icons with per-category colour, and city **landmark glyphs** (Charminar, Gateway of India, Gopuram, Howrah Bridge…) instead of ten identical map pins. Blue stays the only action colour; these hues live in icon tiles and never on a control.
- Background: three overlapping gradient stops rather than one flat tint, plus a blue-tinted section band replacing flat grey.

**A real layout bug, spotted from a screenshot.** The card column rendered ~800px against a ~480px left column, and `items-center` vertically centred the short one — producing a large dead space above the headline. Constraining the card column to a fixed viewport fixed the alignment and enabled the marquee in the same change.

**Looping card marquee.** List rendered twice, strip translated exactly `-50%`, so the loop has no seam. Pauses on hover and focus-within. Needed an explicit reduced-motion override: the blanket rule collapsing all animations to 0.01ms would have frozen the strip halfway scrolled off, so it is cancelled outright instead.

**`/tutors/[slug]` profile page** — the real `M2-06` page built early against example data, prerendered static. **No phone number appears on it and none will when the data is real**: contact details are what tutors pay to unlock, so a public profile leaking one removes the business model rather than degrading it. The page explains where the number is instead of leaving the visitor hunting, and the reviews section says reviews will appear rather than inventing any.

**Process failure worth recording:** commits `62dcda9`, `b8fd365` and `cb2fd10` shipped without touching these docs, despite CLAUDE.md requiring it in the same session. Caught only because the user asked. Backfilled here.

### 2026-09-01 — M1-08 tutor profiles, the core inventory

**63 tests pass** (up from 46). Everything downstream — search, the lead feed, unlocks — needs these to exist.

**Schema** (`V6__profiles.sql`): `tutor_profiles`, `tutor_subjects`, `tutor_locations`, `tutor_qualifications`, plus a deliberately thin `student_profiles`. Fees in paise as `BIGINT`. Subjects are per-tutor-per-subject with their own fee, grades and boards, because a tutor may reasonably charge more for Class 12 Physics than Class 8 Maths.

**Completeness scoring is weighted, not an even split.** Subjects and location outweigh a bio because a profile without them cannot be matched to a requirement at all — it is invisible however well written. Gates publishing at 60%, and an edit that drops a live profile below the bar **unpublishes it automatically**, since a half-empty listing in front of parents reflects on every other tutor. It also returns a plain-language list of what is still missing: "60% complete" tells a tutor they are stuck without telling them what to do.

**Ownership is structural rather than a check** (`M1-05.3`). There is no "update tutor {id}" endpoint at all — every route acts on the token's own user. No parameter tampering can reach another tutor's profile, because there is no parameter to tamper with. Covered by a tutor-vs-tutor isolation test.

**Two response types, not one with conditionals.** `OwnerView` has everything; `PublicView` has no phone, no email, no date of birth, no document URLs. Separate types because a conditional is something a future edit can silently get wrong — if a field is absent from the type, no code path can leak it. Asserted by a test that greps the public response for each.

#### Three bugs found by the tests

1. **`MultipleBagFetchException`** — my `@EntityGraph` tried to join-fetch three `List` collections at once, which Hibernate cannot do. I had been optimising a non-hot path: a profile read is one row for one user, while search is the hot path and never touches these collections. Removed the graph and let them load lazily inside the transaction.
2. **`@Transactional(readOnly = true)` on a method that writes.** `getOwnProfile` creates the profile on first access, so the very first fetch failed with a 500.
3. **Jackson 3 flipped `FAIL_ON_NULL_FOR_PRIMITIVES` to true.** Boot 4 ships Jackson 3, so an omitted `boolean` in a request body is now a hard parse error instead of defaulting to `false`. Request DTOs now use boxed types with explicit defaults, rather than disabling the check globally for every endpoint. Recorded in `backend/CLAUDE.md` — this will recur on every DTO from here.

### 2026-09-01 — Backgrounds, section panels, and a visual browse grid

**A cascade bug, caught from a screenshot.** "Are you a tutor?" was invisible on its dark panel and the blue panel's heading rendered near-black — both marked `text-white`. The base block in `globals.css` was **unlayered**, and unlayered CSS beats layered CSS regardless of specificity, so `h1,h2,h3,h4 { color: ink-900 }` silently overrode every white heading in the application. Wrapping it in `@layer base` fixed it everywhere.

> Worth noting how this was found: my verification greps rendered HTML, and the HTML was always correct. The failure existed only in computed styles. Screenshots catch a class of bug that markup assertions structurally cannot.

**Section panels.** Every section is now a rounded panel on a page canvas rather than a full-bleed band. Panels give each section a real edge, and because they all sit in the same `Container` their edges line up down the page — most of what makes a layout read as deliberate. One radius everywhere; mixing radii is what makes a page look assembled from parts.

**The canvas needed real weight.** First attempt used `ink-50` (#f8fafc), barely a shade off white, so panels had nothing to sit against and the page still read as flat white. Replaced with a dedicated `--color-canvas` blue-grey (#e6edf7): luminance ratio against white goes from ~1.04 to ~1.18. Same root cause fixed in several inner surfaces that were near-white on near-white.

**Visual browse grid**, after UrbanPro was given as a reference. Categories are now grouped with a five-column tile grid each, rather than text chips.

**Deliberately not stock photography**, unlike the reference. Seventy photos is several megabytes on a mid-range Android on a patchy connection; every photo needs a licence traceable to launch; and a stock photo of a smiling student implies a classroom that does not exist yet. Generated gradient-and-glyph tiles cost nothing and claim nothing. The trade is real — photographs carry more warmth — and if photography is commissioned later only `SubjectTile` changes.

Hero stats moved inline under the search, pipe-separated. They remain capability claims (subjects, cities, free) rather than user counts: an incumbent can legitimately print "55 lakh students", and we cannot.

**Two follow-up corrections, both caught from screenshots.**

The first tile grid drew the *category* icon on every tile, so Mathematics, Physics, Chemistry, Biology and Science were five identical open books on five identical blue rectangles. The picture carried no information and the label did all the work — which defeats the point of a visual grid. Now one glyph per subject: a flask for Chemistry, an atom for Physics, a stethoscope for NEET, a shuttlecock for Badminton. Roughly 70 distinct glyphs, falling back to the category icon where a subject has none.

Languages get their **native script** — అ for Telugu, அ for Tamil, ॐ for Sanskrit — rather than twelve identical speech bubbles, which was the same failure in miniature. Set as text, so they stay correct at any size and need no path tracing. Tiles also shift tone by position, since five tiles in an identical shade still read as one block of colour.

Second: the page rendered only the first three categories, which silently hid Music & Dance, Study Abroad Tests and Hobbies & Sports — half of what the platform offers, invisible on the page whose job is to show what the platform offers. Now renders every category.

### 2026-09-01 — M1-09 file storage

**79 tests pass**, up from 63. Sixteen of the new ones are this module, and almost all are attack cases — which is what the module is for.

**The type comes from the bytes, never the upload.** Both the filename and the browser-supplied `Content-Type` are attacker-controlled. Anyone can name a file `photo.jpg`, declare it `image/jpeg`, and upload HTML — and if that is served back from our own domain it executes with our origin's privileges. That is stored XSS with an upload form as the delivery mechanism. `ContentTypeDetector` reads magic bytes and rejects anything unrecognised rather than guessing.

**SVG is deliberately unsupported.** It is XML, it can contain `<script>`, and it has no fixed magic number — there is no safe way to accept one without a sanitising parser, and a profile photo does not need vector graphics.

**Uploaded filenames are discarded entirely.** Stored as `kind/uuid.ext`, with the extension derived from the detected type. Nothing the uploader chose reaches the filesystem: no null byte, no unicode direction override, no second extension, no deliberate collision.

**Path traversal has two independent defences** — a strict key pattern, and a check that the resolved path is still inside the storage root. Not redundant: the pattern is the intent, the containment check is the guarantee that still holds if someone later loosens the pattern without understanding why it was tight. Five traversal shapes are tested against a real file planted outside the root.

**Public and private files can never share a serving path.** The kind is encoded in the storage key's own directory, so `/public/files/**` refuses anything not marked public without a database lookup — access control that needs a lookup is access control that can be skipped. It returns **404 rather than 403**, because a 403 would confirm that a given ID document exists.

Private files are served `no-store`; public photos cache for 30 days and are immutable, since the key changes when the photo does. Both carry `X-Content-Type-Options: nosniff` and a `sandbox` CSP.

`M1-09.5` (image resize) is deferred — the 5 MB cap is holding, and it is a bandwidth optimisation rather than a correctness one.

### 2026-09-01 — M1-10 verification, M1-07 student profiles. **M1 backend complete.**

**94 tests pass**, up from 79.

**Verification ladder.** Every approval is a deliberate admin act recorded with who and when; nothing approves automatically. A badge granted carelessly is worse than no badge, because it converts our carelessness into a parent's misplaced confidence.

**A deliberate deviation from the task text.** The breakdown described the ladder as PHONE → EMAIL → ID → EDUCATION, each rung requiring the one below. Implemented literally that is wrong here: email is optional because phone is the identity, so a tutor who never added an email could never reach `ID_VERIFIED` — blocking the one badge parents care about, and with it the M4 signup bonus. The ladder is phone → ID → education, with email as its own badge.

**PHONE is not a row in `verifications`** — it lives on `users.phone_verified_at`, written by the OTP flow. Duplicating it would create two sources of truth for the same fact.

**Guarantees at the database level rather than in application code:** a rejection must carry a reason; an approval or rejection must record who and when; one live request per user per type via a partial unique index, so resubmission after rejection stays possible but the queue cannot be flooded. Review is one-shot — re-deciding would overwrite the audit trail that makes a decision defensible.

Tested that an ID document is unreachable publicly (404, not 403 — a 403 confirms the document exists), that the admin route is 401 unauthenticated, and that **a tutor cannot approve their own verification**. The trust model collapses if that ever succeeds.

**Student profiles** are deliberately thin — name and location, both optional. Nothing is required before posting a requirement, because the requirement itself carries subject, budget and area. Every field asked for earlier is a chance to abandon the funnel.

> **Caught while writing tests:** I had defined a no-op `ResultMatcher` named `content()` at the bottom of the verification test, which would have made those assertions silently pass. A test that cannot fail is worse than no test — it reports safety that was never checked.

### 2026-09-01 — M1-11 / M1-12: the frontend that makes M1 usable

Everything built so far was reachable only through Swagger. This is the part a tutor actually touches.

**`AuthProvider`.** The access token lives in React state and nowhere else — not `localStorage`, not `sessionStorage`, because anything readable by JavaScript is readable by a successful XSS, and a bearer token *is* the user. The refresh token is already an HttpOnly cookie, so a reload calls `/auth/refresh` rather than reading a stored credential. That is the whole reason the backend was built this way. The cost is a brief loader on full page loads; the alternative is persisting a credential where script can reach it.

`authFetch` retries **once** after refreshing on a 401. Access tokens last 15 minutes, so a mid-session 401 is expected rather than exceptional.

**`RequireRole` is a UX affordance, not a security control** — anyone can edit client state. Enforcement is the backend's `@PreAuthorize` on every endpoint. Its loading branch matters more than it looks: redirecting before the initial refresh settles would bounce a signed-in user to the login screen on every reload.

**The onboarding wizard is server-driven.** It renders the `profileCompleteness` score and the plain-language `missingForPublish` list the API already returns, rather than keeping its own idea of "complete" — two definitions would drift, and the one the publish endpoint enforces is the one that counts.

**Each step saves on its own**, rather than one long form submitted at the end. Filling in a tutor profile is genuinely long, will be done on a phone, and will be interrupted; losing twenty minutes of typing to a dropped connection is how a half-finished profile becomes an abandoned one. The profile therefore exists in a partial state throughout — safe, because nothing is visible until `publish`, which the backend refuses below 60%.

Steps are **tabs, not a forced sequence**: a tutor who only wants to change their fees should not walk through four other screens. The wizard is also the editor, so a profile is changed in one place rather than two that can diverge.

**The dashboard shows zeroes nowhere.** A "0 enquiries" counter reads as *nobody wants you* rather than *not built yet*, and that difference matters to someone deciding whether to finish their profile. The lead feed placeholder says what it is and when it arrives.

**Caught during verification:** the running backend predated M1-07 through M1-10, so the first live check 404'd on every profile route. Restarted, migrations applied to v7, and the path re-verified — profile created at 0%, basics raising it to 25%, `missingForPublish` populated.

### 2026-09-01 — M2: search, masking, and the SEO landing pages

**111 tests pass.** Six of eight M2 tasks complete.

**Search is native SQL**, for two concrete reasons: `teaching_modes` is a Postgres `varchar[]` and JPQL cannot express `= ANY(...)`, and this is the hottest query in the application, so the SQL reaching the planner needs to be the SQL written rather than whatever Hibernate composes. Every value is bound; only the *shape* of the WHERE clause is dynamic. Sort comes from an enum, tested by asking for a sort of `id; DROP TABLE users`.

**Two product decisions worth stating.** An online tutor matches every location filter — they can teach a student in any city, so excluding them would hide exactly the tutors most able to help. And fee bounds compare against a tutor's *starting* fee, not their range, because comparing against the range excludes someone whose lowest rate is inside a parent's budget merely because their highest is not.

**Contact protection is structural, not masking.** Public DTOs carry no contact fields at all — a field that does not exist cannot be leaked by a future edit, whereas a masked field is one careless change from being unmasked. `ContactMasking` exists for the M3 lead feed, where a masked form is shown deliberately.

**SEO.** 714 sitemap URLs from the live catalog. Landing pages are server-rendered with zero client JavaScript, carry JSON-LD, and cross-link to related subjects and other cities — a page nothing links to is a page nothing indexes, and a sitemap alone is a weak signal.

**A combination with no tutors is `noindex, follow`.** Indexing seven hundred empty pages earns a site-wide quality penalty. They stay in the sitemap so they are crawled and ready the moment their first tutor publishes.

#### Bugs found

- `verifiedTutorIds()` had a `.filter(id -> true)` and never checked verification at all. Replaced with a single bulk query mapping user ids to profile ids.
- **A malformed query parameter returned 500 rather than 400** anywhere in the API. `MethodArgumentTypeMismatchException` fell through to the catch-all handler, so a bad enum or number looked like a server fault. Now a 400 naming the accepted values — without echoing the rejected value back, since that is caller-controlled text.

#### Two deliberate deferrals

`M2-02.4` (index verification under load): with a handful of rows Postgres correctly prefers a sequential scan, so asserting index use today would prove nothing. Needs the M6-04 demo dataset.

`M2-07.2` (locality-level pages): 10 cities × 70 subjects is already 700 pages with no tutors on them. Adding locality depth multiplies thin pages before there is supply to fill them.

> **Process note:** I corrupted `docs/TASKS.md` by round-tripping it through PowerShell's `Set-Content -Encoding utf8`, which double-encoded every non-ASCII character. Restored from git and redone with the editing tool. PowerShell rewrites are not safe for files containing anything outside ASCII.

### 2026-09-01 — M3: the lead loop. **The transaction the product rests on.**

**144 tests pass**, up from 111. Nine of eleven M3 tasks; only the two frontend ones remain.

Four properties had to hold, each now enforced by something stronger than a comment:

1. A tutor is never charged for a lead they do not receive.
2. A tutor is never charged twice for the same lead.
3. No more than five tutors unlock one requirement, however many try at once.
4. A rejected attempt leaves no trace — no ledger entry, no partial record.

**The unlock is one transaction with a row lock taken on the requirement first.** Without it, five tutors hitting the last slot together each read `unlockCount = 4`, each conclude there is room, and five get charged for four slots. Locks are always requirement-then-wallet — two orderings across two code paths is a deadlock waiting for traffic.

**The unique index on `(requirement_id, tutor_id)` is the last line of defence.** The application check is the friendly path; the constraint is the guarantee, because a check can race and a constraint cannot.

**`LeadUnlockConcurrencyTest` is why M3-08 exists.** Ten real threads released by one latch against a real database — a sequential test would pass with no locking at all. It asserts exactly five unlocks, the counter matching the records, every loser's balance untouched, and the ledger reconciling for all ten tutors under contention.

**The ledger is append-only, enforced by a database trigger** that raises on UPDATE and DELETE. A comment saying "append-only" is not a control: the first person under deadline pressure who needs to "just fix" a balance writes an UPDATE, and financial history that can be edited is not history. Corrections are compensating `ADMIN_ADJUSTMENT` entries, so the error and its fix both stay visible. `reconcile()` replays the ledger against the cached balance — every cache is a chance to be wrong, and this is how we find out.

**Lead prices are locked onto the requirement at creation** and never recomputed. A tutor shown a lead at 5 credits must be charged 5, whatever the bands say by the time they tap. 22 tests cover every band boundary; an off-by-one here is a tutor overpaying repeatedly until someone notices.

**Notifications are delivered after commit, never inside the transaction.** Sending an SMS inside the unlock would let a provider timeout roll back a *paid unlock*, and holding a database transaction open across a third-party network call is how a slow provider becomes a database outage. The record is written transactionally; delivery follows and its failures are swallowed.

**Three DTOs rather than one with conditionals** — `StudentView`, `LeadPreview` (no name, no phone: this is what makes an unlock worth paying for), `UnlockedLead`. A field absent from `LeadPreview` cannot be leaked into it, and the end-to-end test greps the feed response for the student's number.

**Two mistakes of mine, both caught by the build:**

- PowerShell's `Set-Content -Encoding utf8` wrote a BOM into two enum files and broke compilation. Second encoding failure from PowerShell writes today — source files now go through the editing tool only.
- The end-to-end test asserted an exact feed total, which is really an assertion about the order tests happen to run in. Rewritten to assert on the specific requirement.

---

## Known issues

- **`CorsConfigurationSource` cannot be injected by type** in Spring Boot 4 — `mvcHandlerMappingIntrospector` also implements it, so `@Bean SecurityFilterChain(HttpSecurity, CorsConfigurationSource)` fails startup with `NoUniqueBeanDefinitionException`. Fixed by calling the `corsConfigurationSource()` bean method directly. Watch for the same trap with any other type Spring MVC implements incidentally.
- Mockito warns it is self-attaching as a JVM agent, which future JDKs will disallow. Harmless today; when it becomes an error, add Mockito as an explicit `-javaagent` in the Surefire config. Plausibly arrives sooner on Java 26 than on an LTS (ADR #4).

---

## Blockers

*None.*
