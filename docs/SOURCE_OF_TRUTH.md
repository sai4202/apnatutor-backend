# ApnaTutor — Source of Truth

> **This file is canonical.** If code, comments, or a chat conversation contradict this document, this document wins — or this document gets updated in the same commit. Never let them silently diverge.

**Project:** ApnaTutor — *"Apna tutor, apne ghar ke paas."*
**Started:** 2026-08-31
**Model:** India-first tutor marketplace, lead-credit monetisation.

---

## 1. Domain glossary

Precise meanings. Use these words in code, tables, APIs and UI copy — nowhere else invent a synonym.

| Term | Definition |
|---|---|
| **Student** | The account that seeks tuition. In practice often a parent. Role name is `STUDENT`; UI says "Student / Parent". |
| **Tutor** | The account that supplies tuition. Role `TUTOR`. |
| **Requirement** | A student's posted tuition need (subject, grade, locality, budget…). Free to post. Owned by the student. |
| **Lead** | A Requirement *as seen from a tutor's side*. Not a separate table — same row, different projection. A lead is **masked** until unlocked. |
| **Masked** | Student's name, phone and exact address are hidden. Tutor sees subject, grade, board, locality, budget band, timing and notes. |
| **Unlock** | The act of a tutor spending credits to reveal a lead's contact details. Recorded in `lead_unlocks`. Irreversible except via refund. |
| **Credit** | Prepaid unit tutors spend to unlock. Internal accounting value **₹10 per credit**. Never a currency — always an integer count. |
| **Wallet** | A tutor's *derived* credit balance. Never authoritative on its own — see §4. |
| **Ledger** | `credit_transactions`, append-only. The single authority on how many credits anyone has. |
| **Unlock cap** | Max tutors allowed to unlock one requirement. Protects the parent from being spammed. |
| **Verification** | An admin- or system-confirmed claim about a user (phone, email, ID, education). Drives trust badges. |
| **Engagement** | Any recorded tutor↔student connection. Today the only type is `UNLOCK`; v2 adds `BOOKING`. See §7. |

**Banned words in code:** "booking" (reserved for v2), "order" (reserved for Razorpay payment orders), "match" (ambiguous — say "lead feed" or "search result").

---

## 2. Roles & permissions

| Role | Can |
|---|---|
| `STUDENT` | Post/edit/close own requirements, view tutors (contacts masked), see tutors who unlocked them, leave reviews for tutors they unlocked |
| `TUTOR` | Manage own profile, browse lead feed (masked), unlock leads, buy credits, reply to reviews, raise refund disputes |
| `ADMIN` | Everything above plus verification queue, review moderation, credit adjustments, refunds, package pricing, user suspension |

A user has exactly **one** role. A person wanting both creates two accounts (v1 simplification — revisit only if real users complain).

---

## 3. Business rules (authoritative numbers)

### 3.1 Lead pricing
Cost in credits is a function of the requirement's monthly budget:

| Monthly budget (₹) | Credits |
|---|---|
| < 2,000 | 3 |
| 2,000 – 4,999 | 5 |
| 5,000 – 9,999 | 8 |
| ≥ 10,000 | 12 |

- **Online-only requirements: ×0.8**, rounded up (less commitment, wider supply).
- Price is **locked onto the requirement at creation time** (`requirements.unlock_cost_credits`). Later pricing changes never alter existing leads — tutors must never see a price move under them.

### 3.2 Unlock cap
- **5 tutors maximum** per requirement — the default of the `lead.unlock_cap` setting, editable by an admin within bounds of 1–20.
- Like the price, the cap is **locked onto the requirement at creation** (`requirements.unlock_cap`). Raising the setting must never reopen an enquiry whose owner was told to expect at most five calls.
- An unlock attempt past the cap is rejected with `LEAD_UNLOCK_CAP_REACHED`; no credits are debited.
- Once capped, the requirement disappears from all other tutors' feeds.
- A refunded unlock **frees its slot** back up.

### 3.2a Who is shown a lead

A requirement appears in a tutor's feed only if **all** of these hold. `findLeadFeedFor` and `findTutorsToNotify` both encode this rule and must stay in step.

- The tutor teaches the subject.
- The requirement is `ONLINE`, has no location, or the tutor lists that locality or its city.
- The tutor has not already unlocked it.
- The requirement is `OPEN` and unexpired.
- **The tutor's profile is published.** An unpublished tutor unlocking a lead would put a stranger on a parent's phone with no profile for the parent to check them against — which is what the verification ladder exists to prevent.

### 3.2b Unlocking is replay-safe

A repeated unlock returns the unlock the tutor already holds. It does not charge again and does not error.

This is not politeness. The case it covers is a tutor on a patchy mobile connection whose request succeeded but whose response never arrived, and whose client then retried: answering that retry with `LEAD_ALREADY_UNLOCKED` would leave them charged and holding nothing, which is the worst outcome the money path can produce. It also makes the endpoint idempotent without an `Idempotency-Key` header — the same guarantee, enforced by the data rather than by a header a client can forget.

The charge is still exactly once, guarded by a `SELECT … FOR UPDATE` on the requirement and a unique index on `(requirement_id, tutor_id)` underneath. `LEAD_ALREADY_UNLOCKED` remains a valid `ErrorCode` and is still returned when two of a tutor's own requests race.

### 3.2c New leads are pushed, not waited for

When a requirement is posted, matching tutors are notified (`NEW_MATCHING_LEAD`). A lead nobody sees for a day is usually a lead the parent has already solved elsewhere.

The fan-out is capped at **4× the enquiry's own unlock cap**, ordered by approved verifications then rating then review count. Messaging every tutor who matches would mean most recipients arrive to find the lead taken, which teaches them the notifications are not worth opening.

### 3.3 Free credits
- **10 credits**, granted once, when a tutor reaches verification level `ID_VERIFIED` (phone + email + ID all approved).
- Ledger reason `SIGNUP_BONUS`. Never granted twice — enforced by a unique partial index.

### 3.4 Expiry
| Thing | Lifetime |
|---|---|
| Requirement | 30 days from creation, or until student marks `HIRED` / `CLOSED` |
| Purchased credits | 365 days from purchase |
| Bonus credits | 90 days from grant |
| OTP | 10 minutes, max 5 attempts, max 5 sends per phone per hour |
| Refresh token | 30 days |
| Access token | 15 minutes |

### 3.5 Refunds
- Tutor may dispute an unlock within the `refunds.window_days` setting (**7 days** by default). The window exists because a dispute weeks later cannot be investigated — neither party remembers the call with any precision.
- Grounds are a **fixed set of codes**, not free text: `WRONG_NUMBER`, `UNREACHABLE`, `ALREADY_HIRED`, `NOT_LOOKING`, `DUPLICATE_REQUIREMENT`, `WRONG_SUBJECT_OR_AREA`, `ABUSIVE`, `OTHER`. Free text describes one bad lead; a code lets the platform find the enquiries that keep producing them.
- One dispute per unlock, enforced by a unique index. Otherwise a tutor could be refunded more than they were charged.
- Admin decides. Approved → credits returned with reason `REFUND`, unlock marked `REFUNDED`, **cap slot freed and the requirement reopened if it had capped**. A parent promised five responses who got one unusable one should end up with five usable ones.
- **Refunded credits carry no expiry.** They were paid for once already; a fresh clock would be a second penalty for a lead that was not the tutor's fault.
- **A rejection requires a note.** A rejection with no reason is one a tutor can neither argue with nor learn from, and it is where the belief that disputes are pointless comes from.
- Two abuse signals, both **flagging rather than blocking**: a tutor's dispute rate above 30% (over at least 5 disputes), and — the stronger one — a requirement disputed by 3 or more different tutors. One tutor disputing many leads may just be bad at phone calls; three tutors disputing the same enquiry is a fact about the enquiry. A hard cutoff would punish exactly the tutor whose leads really are bad.

### 3.5a Buying credits

The rule everything serves: **credits are granted exactly once, only for money a provider confirmed, and it stays provable afterwards.**

- **Packages are rows** (`credit_packages`), editable by an admin. `payments` copies the credits and amount at order time and never reads them back through the foreign key, so a repricing cannot rewrite an existing receipt. Retiring keeps a package resolvable; retiring the last active one is refused.
- **Nothing the client sends is trusted.** The amount and credits come from the package row. The checkout signature the browser reports is verified only to update the UI — the signed webhook is the authority, because a browser can close mid-payment and on mobile constantly does.
- **Once-only crediting has three layers**: the payment row loaded `FOR UPDATE`, `payments.credited_at`, and a unique index on `(provider, provider_payment_id)`. Repeat deliveries are normal provider behaviour, not a fault.
- **The webhook endpoint is unauthenticated** by necessity, so the HMAC signature is the only control. It is computed over the raw bytes as received, compared in constant time, and **fails closed** — a missing secret, missing header or mismatch all reject. Every delivery is recorded with its raw payload, invalid ones included.
- Orders never confirmed are cancelled after 2 hours. `CANCELLED` is not terminal for crediting: a late webhook still credits, because money that arrives late is still money that arrived.

### 3.5b Credit expiry

- Expiry appends a negative `EXPIRY` entry; the original grant is never edited (Invariant 1).
- **Write-offs are capped at the current balance.** Credits are fungible, so a tutor granted 10 who has spent 8 still has a 10-credit grant on record when it lapses. Writing off the full 10 would take them to −2 and bill them for credits they already used and paid for. Spent credits are never clawed back — given the choice, the platform takes the loss.
- Which grants have been processed is tracked in `credit_grant_expiries`, **not** by looking for a compensating ledger entry. A grant fully spent before it lapsed produces no entry, because nothing moved, and `credit_transactions_amount_nonzero` rightly refuses a zero-amount one.
- Tutors are warned 14 days ahead, **once per tutor** rather than once per grant.

### 3.6 Reviews
- Only a student who **unlocked or was unlocked by** that tutor may review them. One review per student-tutor pair.
- The engagement must be **`ACTIVE`**. A refunded unlock is one the tutor successfully disputed as a bad lead — the platform has already accepted the introduction was worthless. Letting that student rate the tutor anyway turns every refund into an invitation to retaliate, which quietly teaches tutors not to dispute.
- Every review is `PENDING` until an admin approves it. Nothing user-written goes public unmoderated.
- Rating is 1–5 integers. Tutor may post exactly one public reply per review.
- **The reply is moderated separately** and carries its own status. An approved review can hold a pending reply, and refusing a reply leaves the review published: they are written by different people days apart, and a tutor's answer being refused is no reason to unpublish the student's words.
- A published review can be **withdrawn** back to `PENDING` by an admin, for one reported after the fact.
- Aggregates (`avg_rating`, `review_count`) are **recomputed from the table** on every transition into or out of `APPROVED`, never incremented and never trusted from the client. See ADR #12.
- Reviewers are shown publicly as `Priya S.` via the same masking the lead feed uses. A full name against a review, on a page that also names a locality, is usually enough to identify a specific family.

### 3.7 Moderation (M5-05)
- **A suspension must be visible from outside the login path.** `users.status = SUSPENDED` blocks authentication and nothing else, so the search query and the public profile lookup both additionally require an `ACTIVE` account. Enforced at read time rather than by unpublishing the profile: a flag every future code path must remember to set is one that will eventually not be set, and the failure mode is a blocked tutor still taking enquiries. See ADR #14.
- **A suspension carries a mandatory reason**, stored on the row and shown to the user. Enforced by a `CHECK` as well as in Java. Reinstating clears it — a stale reason on an active account is how a screen ends up showing "suspended for fraud" beside a user in good standing.
- **Suspending a student takes down their live (`OPEN`/`CAPPED`) enquiries**, refunding the tutors who paid for them. Continuing to sell introductions to an account we have just judged fraudulent means charging tutors for leads we would then refund one dispute at a time. `HIRED` and `EXPIRED` enquiries are left alone: those introductions already happened.
- **Suspending a tutor does not undo their unlocks.** They paid for introductions that happened, and the students on the other end are real. The asymmetry with the rule above is deliberate.
- **Admin accounts cannot be suspended through the API.** Suspending the last admin locks everyone out of the console with no route back in through the product.
- **`REMOVED` is a distinct requirement state**, never a reuse of `CLOSED`. `CLOSED` is the student's own withdrawal; `REMOVED` is a takedown. Collapsing them makes "how many did we remove as spam?" unanswerable and shows the student their own withdrawal in place of a moderation notice. Removal also carries a mandatory reason, sent to the student.
- **Removing a requirement refunds every active unlock on it**, in the same transaction. A lead we remove as fake is a lead we should never have sold. It writes **no `refund_requests` row**: a dispute is a tutor's claim, this is the platform conceding unasked, and recording it as a dispute would inflate the dispute rate of tutors who complained about nothing.
- **Restoring a removed requirement does not reverse the refunds**, and recomputes the destination state from expiry and the unlock count rather than restoring a remembered one. The cost of a wrong takedown stays with the platform, which is what keeps the decision careful.
- **The moderation queue is enquiries at least three _different_ tutors have disputed.** `COUNT(DISTINCT tutor_id)`, not `COUNT(*)`: one tutor disputing many leads may just be bad at phone calls; three separate tutors disputing one enquiry is a fact about that enquiry.

### 3.8 Audit (M5-08)
- **Every mutating request under `/admin` is recorded**, by route rather than by call site. `AuditInterceptor` writes the entry; the service enriches it with before/after through `AuditContext`. An action no service described is still recorded, identified by its route.
- **Refusals are recorded, not only successes.** An authenticated caller who is not an admin lands in the log as `REFUSED`. A request with **no** credentials does not reach the interceptor at all — the security filter chain rejects it first (debt T19).
- **Two kinds of read are audited**, because reading them *is* the sensitive act: `/admin/files/**` (ID and education documents) and `/admin/requirements/**` (the only projection carrying a student's own phone number). Other admin reads are not — an entry per queue poll buries the entries that matter.
- **`audit_log` is append-only**, enforced by a trigger exactly as `credit_transactions` is. There is no retention job; adding one requires a documented policy and its own migration.
- **There is no endpoint that writes an audit entry.** Entries are a consequence of acting, never of asking. An API that can add an entry can add a false one.
- **The audit write runs in its own transaction and never throws.** A refused action has rolled back by the time it is recorded, and a failed audit write must not turn a completed action into an error the admin will simply repeat.
- **Before/after are partial by design.** Each call site sends the fields its decision moved. A full row snapshot would put phone numbers and document keys into a table nobody can delete from.

### 3.9 Rate limiting (M5-07)
- **Per-IP limits run in a filter ahead of Spring Security**, so a flood is turned away before authentication rather than after it. Everything in that filter is keyed on IP and nothing else: at that point the caller is unauthenticated, and a user id read from an unverified token is attacker-chosen — a forged one would spend a victim's allowance.
- **Per-user limits live in the service layer**, where the identity has been proven. The unlock allowance is per tutor, 30/hour.
- **The unlock limit is applied only to a _new_ unlock**, after the replay check. A tutor retrying a request whose response was lost buys nothing new; refusing that retry would turn a flaky connection into a lost credit, which is the failure the replay path exists to prevent.
- **Every per-IP number is sized for "one address behaving badly", not "one person behaving normally."** Carrier-grade NAT is the norm on Indian mobile networks, so an address can be a neighbourhood. Individual protection is the per-phone OTP cap (§3.4), not these.
- **`/api/v1/public/**` is limited very loosely on purpose.** The frontend renders public pages server-side, so those requests arrive from the frontend server's address unless the deployment forwards the visitor's. Until it does, a tight limit there would throttle the whole site (debt T21).
- **Buckets are token buckets, in memory, per instance.** A fixed window would allow double the intended rate across a window boundary. In-memory means the limit is per instance (debt T20).
- **Every refusal is `429` with `Retry-After` in seconds.** A 429 without one tells a client to back off but not by how much, and the usual response to that is an immediate retry.
- **Rate limiting is skipped entirely in dev mode**, for the same reason the OTP cap exempts seeded test accounts.

---


### 3.10 Data rights (M5-10)
- **Deletion anonymises; it never removes the row.** `credit_transactions` is append-only (Invariant 1) and `audit_log` is append-only (§3.8), and both reference `users.id`. Removing the row would either cascade rows out of an immutable ledger or leave dangling references in one.
- **The anonymised phone is `+99` followed by the zero-padded user id.** The column is `NOT NULL`, uniquely indexed and CHECKed against E.164, which rules out null, a constant and free text. `+99` is not an assigned country code, so the value can never collide with a real number, and the id makes it unique by construction. The original number is freed, so the same person may sign up again.
- **Identity and education documents are destroyed outright.** The only thing in the flow genuinely deleted rather than anonymised: they are Aadhaar and PAN scans, nothing references them, and no ledger depends on them. A storage failure does not abort the deletion — the reference is cleared regardless, leaving an orphaned file, which is a cleanup problem rather than a privacy one.
- **Retention policy.** Kept indefinitely, without personal identifiers: credit ledger entries, payment records, and audit entries for actions taken on the account. Kept as published: reviews the person wrote — they are about a tutor, others have relied on them, and they never carried a full name. Erased immediately: phone, email, password hash, profile fields, uploaded documents, and any live enquiry.
- **A tutor's unspent credits are forfeited on deletion**, and the screen says so before the button rather than after.
- **Admin accounts cannot be deleted through this route**, for the same reason they cannot be suspended: it can remove the last account that could undo it.
- **There is no admin route to export or delete somebody else's account.** A support tool that produces a full copy of any user's data on request is a social-engineering target.
- **A deleted account disappears from discovery automatically**, because search and the public profile lookup both require an `ACTIVE` account (§3.7) — no separate step to forget.

---

## 4. The two invariants that protect v2

These are not style preferences. Breaking either one forces a rewrite when we add booking and payments.

### Invariant 1 — Money is an append-only ledger
`credit_transactions` is **insert-only**. No `UPDATE`, no `DELETE`, ever.

```
balance(tutor) = SUM(amount) WHERE tutor_id = ? AND (expires_at IS NULL OR expires_at > now())
```

`credit_wallets.balance` is a **cache** for fast reads, updated in the same transaction as the ledger insert, and reconcilable at any time by replaying the ledger. If the cache and the ledger ever disagree, **the ledger is right**.

Every debit and credit carries a `reason` enum: `PURCHASE`, `SIGNUP_BONUS`, `UNLOCK`, `REFUND`, `ADMIN_ADJUSTMENT`, `EXPIRY`.

Why it matters: v2 commission, tutor payouts and escrow are just new `reason` values and a second ledger. With mutable balances they would be a migration nightmare.

### Invariant 2 — `lead_unlocks` is a generic engagement record
It carries `engagement_type` (`UNLOCK` today) rather than being unlock-specific. A v2 booking becomes `engagement_type = 'BOOKING'` on the same connection graph, so reviews, notifications, and "my students" / "my tutors" lists keep working untouched.

---

## 5. Data model

Tables, in dependency order. `id` is `BIGSERIAL` unless stated. Every table gets `created_at`, `updated_at` (`TIMESTAMPTZ NOT NULL DEFAULT now()`).

**Identity**
- `users` — `phone` (unique, E.164, the login identity), `email` (nullable, unique), `password_hash` (nullable — OTP-only accounts exist), `role`, `status` (`ACTIVE`/`SUSPENDED`/`DELETED`), `phone_verified_at`, `email_verified_at`, `last_active_at`
- `student_profiles` — `user_id` FK unique, `name`, `location_id`
- `tutor_profiles` — `user_id` FK unique, `display_name`, `headline`, `bio`, `photo_url`, `gender`, `date_of_birth`, `experience_years`, `fee_min`, `fee_max`, `fee_unit` (`PER_HOUR`/`PER_MONTH`), `fee_negotiable`, `teaching_modes` (array of `STUDENT_HOME`/`TUTOR_PLACE`/`ONLINE`), `travel_radius_km`, `languages`, `offers_demo`, `availability_note`, `verification_level`, `avg_rating`, `review_count`, `response_rate`, `profile_completeness`, `is_published`

**Catalog** (mostly seed data)
- `subjects` — `name`, `slug` (unique), `parent_id` (self-FK, forms the tree), `is_leaf`, `display_order`
- `boards` — CBSE, ICSE, IB, IGCSE, State boards
- `grade_levels` — Nursery…Class 12, UG, PG, Competitive
- `locations` — `state`, `city`, `locality`, `slug` (unique), `latitude`, `longitude`, `is_city` — flat table, city rows have null locality
- `tutor_subjects` — tutor × subject, plus `fee`, `grade_level_ids`, `board_ids`
- `tutor_locations` — tutor × location (where they will travel)
- `tutor_qualifications` — `degree`, `institution`, `year`, `document_url`, `is_verified`

**Marketplace**
- `requirements` — `student_id`, `subject_id`, `grade_level_id`, `board_id`, `location_id`, `mode`, `budget_amount`, `budget_unit`, `frequency`, `preferred_timing`, `gender_preference`, `description`, `status` (`OPEN`/`CAPPED`/`HIRED`/`CLOSED`/`EXPIRED`), **`unlock_cost_credits`** (locked at creation), `unlock_count`, `expires_at`
- `lead_unlocks` — `requirement_id`, `tutor_id`, `engagement_type`, `credits_spent`, `intro_message`, `status` (`ACTIVE`/`REFUNDED`), `unlocked_at`. **Unique on (requirement_id, tutor_id)**
- `refund_requests` — `lead_unlock_id`, `reason_code`, `details`, `status`, `decided_by`, `decided_at`

**Billing**
- `credit_packages` — `name`, `credits`, `price_paise`, `validity_days`, `is_active`, `display_order`
- `credit_wallets` — `tutor_id` unique, `balance` (cache, see Invariant 1)
- `credit_transactions` — `tutor_id`, `amount` (signed int), `reason`, `reference_type`, `reference_id`, `expires_at`, `balance_after`. **Append-only**
- `payments` — `tutor_id`, `package_id`, `razorpay_order_id`, `razorpay_payment_id`, `amount_paise`, `status` (`CREATED`/`PAID`/`FAILED`), `raw_payload` (JSONB)

**Trust & platform**
- `reviews` — `tutor_id`, `student_id` (both **user ids**, matching `lead_unlocks`), `rating`, `title`, `body`, `status`, `moderated_by`, `moderated_at`, `rejection_reason`, `tutor_reply`, **`tutor_reply_status`**, **`tutor_reply_at`**, **`tutor_reply_moderated_by`**. Unique on (tutor_id, student_id). The reply carries its own moderation status — a bare text column left nowhere for it to wait
- `verifications` — `user_id`, `type` (`PHONE`/`EMAIL`/`ID`/`EDUCATION`), `status`, `document_url`, `reviewed_by`, `reviewed_at`, `rejection_reason`
- `otp_codes` — `phone`, `code_hash`, `purpose`, `attempts`, `expires_at`, `consumed_at`
- `refresh_tokens` — `user_id`, `token_hash`, `expires_at`, `revoked_at`
- `notifications` — `user_id`, `type`, `payload` (JSONB), `read_at`, `sent_channels`
- `audit_log` — `actor_user_id`, `action`, `entity_type`, `entity_id`, `before`/`after` (JSONB), `ip`

### Money & precision rules
- **All rupee amounts are stored in paise as `BIGINT`.** Never `FLOAT`, never `DOUBLE`. Column names end in `_paise`.
- Credits are `INTEGER` counts, never fractional.
- All timestamps are `TIMESTAMPTZ`, stored UTC, rendered in `Asia/Kolkata`.

---

## 6. API conventions

Base path `/api/v1`. JSON only. `camelCase` fields.

- Auth: access JWT in `Authorization: Bearer`, refresh token in an **HttpOnly, Secure, SameSite=Lax cookie**. The refresh token never touches JavaScript.
- Errors are uniform:
  ```json
  { "code": "LEAD_UNLOCK_CAP_REACHED", "message": "Human readable", "fieldErrors": { "budget": "must be positive" } }
  ```
  `code` is a stable machine enum — the frontend switches on `code`, never on `message`.
- Lists are paginated: `?page=0&size=20`, response `{ content, page, size, totalElements, totalPages }`.
- Mutating endpoints that spend money (`POST /leads/{id}/unlock`, `POST /payments`) require an **`Idempotency-Key` header**. Replaying a key returns the original result rather than double-charging.
- Public (unauthenticated) endpoints: tutor search, tutor profile (masked), catalog, SEO pages.

---

## 7. Conventions

- Java package root `com.apnatutor`, one package per bounded module (see repo README). **Cross-module calls go through a service interface only** — never reach into another module's repository or entity.
- Entities never leave the service layer. Controllers speak DTOs (Java records).
- Flyway migrations: `V<n>__snake_case_description.sql`, forward-only. **Never edit an applied migration** — write a new one. `ddl-auto` is `validate` everywhere, including local.
- Every `@Transactional` boundary sits on the service, never the controller.
- **`backend/` and `frontend/` are self-contained projects** (ADR #11). Neither may read a file outside its own directory — no shared parent config, no relative paths climbing out. They communicate over HTTP only. Each carries its own `.env`, `.gitignore`, README and `CLAUDE.md`.
- Secrets come from environment variables only. Nothing secret is ever committed; each project's `.env.example` documents its own shape and must be updated in the same commit as any new variable.
- Frontend: Next.js App Router, TypeScript strict, Tailwind. Server Components for public/SEO pages, Client Components only where interactivity demands it.

---

## 8. Environments

| | Local dev |
|---|---|
| DB | `apnatutor_dev` on local PostgreSQL 18, port 5432, role `apnatutor` |
| Test DB | `apnatutor_test`, wiped and re-migrated by the test suite |
| SMS | Console-logging stub — the OTP is printed to the backend log |
| Email | Console-logging stub |
| Payments | Razorpay **test** keys |
| Files | Local disk under `backend/uploads/` |

No Docker in this project — PostgreSQL runs as a native Windows service (`postgresql-x64-18`).

**Configuration is per-project** (ADR #11):

| Project | File | Loaded by |
|---|---|---|
| Backend | `backend/.env` | `spring.config.import` in `application.yml` |
| Frontend | `frontend/.env.local` | Next.js, automatically |

Database setup scripts belong to the backend: `backend/scripts/db-setup.sql` and `backend/scripts/db-reset.ps1`.

---

## 9. Architecture Decision Log

| # | Date | Decision | Why |
|---|---|---|---|
| 1 | 2026-08-31 | Name: **ApnaTutor** | "Apna" = one's own; warm and trust-first, proven pan-India naming pattern (cf. Apna.co). Tutor in the name aids comprehension and SEO. |
| 2 | 2026-08-31 | **Lead-credit** model, not booking-commission | Avoids escrow, refunds, scheduling and disputes in v1 while still earning revenue. Proven by UrbanPro in this exact market. |
| 3 | 2026-08-31 | **Next.js** over Vite SPA | Programmatic SEO pages are the primary acquisition channel; an SPA ships an empty shell and will not rank. |
| 4 | 2026-08-31 | **Java 26**, not an LTS | Spring Initializr lists Java 26 as supported for Boot 4.1.1, and JDK 26 was already installed. Fallback to Temurin 21 if a bytecode-manipulating library breaks. |
| 5 | 2026-08-31 | **Native PostgreSQL**, no Docker | User's explicit choice. Consequence: Testcontainers is unusable, so integration tests run against a real local `apnatutor_test` database. |
| 6 | 2026-08-31 | Money in **paise as BIGINT** | Floating-point currency is a correctness bug waiting to happen. |
| 7 | 2026-08-31 | **Append-only credit ledger** | Makes v2 commission, escrow and payouts additive rather than a migration. |
| 8 | 2026-08-31 | **Unlock cap of 5** per requirement | Lead quality is the product. Uncapped unlocks turn a parent's phone into a spam target and kill retention on both sides. |
| 9 | 2026-08-31 | **Phone + OTP** as primary identity | Indian consumer norm; email-first signup suppresses conversion badly in this market. |
| 10 | 2026-08-31 | One role per account | Simplifies authorization in v1. Revisit only on real user demand. |
| 11 | 2026-08-31 | **`backend/` and `frontend/` are self-contained projects in one repo** | Each builds, tests, runs and deploys from its own directory with its own config, and neither reads a file outside itself — so either can be extracted, containerised or deployed independently without untangling shared paths. They stay in one repo so an API change and its client update can land in a single commit. **Reverses the earlier shared repo-root `.env`**, which coupled the two at the filesystem level; config is now `backend/.env` and `frontend/.env.local`. Shared *product* docs stay in `docs/` because the business rules genuinely bind both sides, and splitting them would invite two diverging copies. |
| 12 | 2026-09-01 | **Rating aggregates are recomputed from the reviews table, never incremented** | `review_count = review_count + 1` drifts the moment two moderators approve at once — both read the old value, both write the same new one, and a review vanishes from the count permanently. It also cannot be replayed: there is no way to ask an incremented counter whether it is still right. Deriving the aggregate makes approval, rejection and withdrawal one idempotent statement with no sign to get backwards, and gives `recompute-ratings` something to repair with. Same reasoning as Invariant 1, where the ledger is the truth and the balance is a cache. |
| 13 | 2026-09-01 | **Reviews address tutors by `tutor_profiles.id` on the wire, by user id in the database** | The database keys on the account, because eligibility joins `lead_unlocks` and every other money table keys the tutor that way. The API takes the profile id, because that is the only tutor identifier the frontend has anywhere else — public profiles, search results, the "tutors who responded" list. Two identifiers on the wire is how the wrong one ends up silently addressing a different person. |
| 14 | 2026-09-04 | **A suspension is enforced by the read queries, not by unpublishing the profile** | Setting `users.status = SUSPENDED` only blocks the login path; the tutor stays in search results and on their own profile page, which is a suspension that did not happen. The alternative was to unpublish the profile on suspension and restore it on reinstatement — cheaper per read, but it means a flag that every future code path has to remember to set, restore, and not clobber, and the failure mode is a blocked account still taking enquiries. Search and the profile lookup now both require an `ACTIVE` account. The cost is one primary-key lookup per candidate row on the hottest query in the application; the benefit is that there is no state to get wrong. |
| 15 | 2026-09-04 | **Removing a requirement refunds its tutors and writes no dispute row** | Two decisions in one. Refunding is not generosity: a lead removed as fake was a lead we should not have sold, and a takedown that kept the charges would teach tutors that credits can evaporate inside a lead. Not writing a `refund_requests` row is what keeps the abuse signal honest — `disputeRateFor` is the one number used to judge whether a tutor games refunds, and filling it with refunds they never asked for would fire the flag on the platform's own mistakes. The ledger entry, referencing the requirement, is the record instead. |
| 16 | 2026-09-04 | **The audit log is written from the HTTP layer, by route, not from each admin service** | Two requirements pull opposite ways: nothing may be missing, and an entry has to say what changed. Auditing inside each service satisfies the second and fails the first — every admin endpoint added later has to remember, and the failure is silent, because an action nobody logged looks exactly like an action nobody took. An `@Audited` annotation has the same flaw. So `AuditInterceptor` writes one entry per audited request because of the route, and services optionally enrich it with before/after through a request-scoped `AuditContext`. The cost is a ThreadLocal and a rule that lives in two places; the benefit is that a controller written next year is audited without its author knowing this file exists. |
| 17 | 2026-09-04 | **Rate limiting sits ahead of Spring Security, and is therefore IP-only** | A limiter behind the security filter chain never sees an anonymous request to a protected route, because security rejects it first — so the flood it most needs to stop is the one it cannot see. In front, the request is refused before authentication, which is also the cheapest place to refuse it. The price is that the caller is not yet authenticated: keying a bucket on a user id there would mean trusting a `sub` claim nothing has verified, and a forged one lets an attacker spend a victim's allowance rather than their own. Per-user limits therefore live in the service layer, giving two mechanisms instead of one. That duplication is the deliberate cost of not having a limiter that can be bypassed by editing a token. |
| 18 | 2026-09-04 | **A report is a signal, not an instruction** | Upholding an abuse report records that a moderator agreed with it and does nothing else. The alternative — upholding suspends the account, or unpublishes the review — makes the report button a weapon: a handful of coordinated reports removes a competitor, and the one step where a person looks is exactly the step it skips. The cost is that a moderator has to click through to another screen to act, which is the point rather than an oversight. |
| 19 | 2026-09-04 | **Deleted accounts are anonymised in place, with a synthetic `+99` phone number** | The row cannot go: two append-only tables reference it. The phone column cannot be nulled, blanked or set to a constant either — it is `NOT NULL`, uniquely indexed, and CHECKed against E.164. Building the value as `+99` plus the zero-padded id satisfies all three constraints at once and cannot collide with a real number, because `+99` is not an assigned country code. The alternative considered was a nullable phone with a partial unique index, which would have meant relaxing a constraint that has prevented one number becoming two accounts since V2 — a live guarantee traded away for a rare case. |
