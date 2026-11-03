# Member authentication design — issue #63

## Status and scope

This is a **design for subsequent implementation**, reflecting the user's answers on 2026-11-03. It does not describe implemented runtime behavior or the current database state. The base is `main` `5e62f13`; the working branch is `docs/63-member-auth-design`.

- Introduce the authentication runtime after the hackathon submission. Before submission, change only this design and contract; preserve the current demo and recorded fixtures (D-069).
- The flow is email/password registration → signup email verification → login. Email-based login is not introduced. Password reset emails serve a separate purpose.
- The user selected “delete, 30 days, 30 days”: allow registration with the same email after 30 days, use email verification for signup, and use the Resend API for delivery. Earlier explanations of terms and a lack of response were not approval; the one-year and permanent-retention recommendations were not adopted (D-070).
- This request covers local documentation, the contract, and verification. The user subsequently authorized local commits after checking for sensitive data and then requested one combined local commit including the missing password-change specification. Do not push, create a PR, close the issue, write or apply migrations, or send actual emails.
- OpenAPI 0.3.0 is the target contract. The current server and screens do not implement authentication. Successful generation is not evidence of successful runtime authentication.

## Members and email

| Item | Agreed design |
|---|---|
| State | `PENDING_VERIFICATION` → `ACTIVE` → `WITHDRAWN`. No restoration transition after withdrawal. Delete unverified registrations after 24 hours |
| Collection | Collect email and password only. Do not add real name, phone number, or date of birth |
| Email normalization | Trim leading and trailing ASCII spaces and lowercase the entire ASCII email. Maximum 254 characters, local part maximum 64. Reject whitespace, control characters, non-ASCII addresses, display names, and comments |
| Duplicates | At most one `PENDING_VERIFICATION` or `ACTIVE` member per normalized email. Case-insensitive; do not strip `+tag` or dots or merge provider-specific aliases |
| Re-registration | Reject registration while `withdrawn_email_block.blocked_until > now`. Allow it when `now >= blocked_until`, even before cleanup runs. Re-registration creates a new member ID and organization; do not restore ownership, transactions, or sessions |
| Password | 12–128 characters. Hash the original including spaces; no trimming or Unicode normalization. Server encoder: Argon2id, initially 19 MiB memory, 2 iterations, parallelism 1. Verify load and supporting dependencies during implementation. Never store or log plaintext |
| Unverified member | No domain APIs or token issuance. Only signup verification and code re-request are available. Return `EMAIL_NOT_VERIFIED` only when a login supplies the correct password |

Registration and verification/reset requests use the same response shape, wording, and similar processing time regardless of account existence or withdrawal blocks. Duplicate registration does not overwrite an existing member's password or code. Email changes, social login, organization invitations, and membership in multiple organizations are out of scope.

## Existing data and ownership

The current domain has `organization`, `event`, `item`, `pickup_request`, `reservation`, and `handover_log`, with no previous individual-member ownership links. Do not arbitrarily assign existing seeded organizations to new members or grant ownership based only on submitted IDs or email (D-071).

- Create one dedicated `organization` with each new registration. Use a fixed generic name; never copy email into the organization name. `organization.primary_member_id` is the sole source of member ownership: one organization per member; shared organization management is outside the subsequent implementation scope.
- New members create their organization's event with `createEvent` before starting photo intake. Without event creation, onboarding would require taking ownership of existing seed data, so this minimal API is included in the contract.
- Keep existing organizations' `primary_member_id` NULL and preserve their unassigned ownership. New members cannot access their private data or mutation APIs. Any explicit assignment requires separate review and work; none is applied here.
- Check that `item.organization_id` matches `event.organization_id`. The giver is the organization owner. Connect pickup requests through `pickup_request.organization_id`; the reservation receiver through `reservation.organization_id = pickup_request.organization_id`; handovers through reservation → item → giver organization. Do not replace all existing FKs with member FKs.
- `intake_draft` ownership follows event → organization. Body organizationId/eventId values require validation; they are not identity proof. Agent tools receive the same authenticated principal and undergo server revalidation.
- Filter lists to the caller's authorized scope; return 404 for another organization's private object regardless of existence. Forbidden actions return 403 without internal details.

## API access

Every domain API requires an `ACTIVE` member's Bearer JWT. “Public” below means business fields visible to authenticated members, not anonymous access. Exclude other members' emails, transcripts, free text, and operational logs. Concrete HTTP schemas are in `contract/openapi.yaml` (D-072).

| operationId | Permission and filtering |
|---|---|
| `changePassword` | ACTIVE member only; derive the member from the JWT, verify the current password, require CSRF/Origin, and revoke all sessions on success |
| `getHealth` | Anonymous access; existing health information only |
| `listOrganizations`, `listEvents`, `getEvent` | Own organization and events only. Do not automatically expose unassigned seeded ownership |
| `listItems`, `getItem` | Expose tradable item business fields to active members. Include `conditionsTranscript` only for the owner. Withdrawn givers' items cannot be listed or receive new HOLDs |
| `createEvent` | Create an event for the caller's organization only. Validate name, venue, and future teardown time; derive organizationId from the principal |
| `createIntakeDraft`, `getIntakeDraft`, `confirmIntakeDraft` | Event's giver organization owner only |
| `interpretConditions` | GIVER requires an owned event; RECEIVER requires an active member. Do not infer organization permissions from voice or text |
| `createPickupRequest`, `getPickupRequest`, `runAllocationAgent` | Receiver organization owner only. The create request's organizationId must match the caller's organization |
| `listReservations`, `getReservation` | Giver or receiver party only. Manipulating list filters must not expose others' transactions |
| `holdItem`, `confirmReservation`, `cancelReservation` | That request/reservation's receiver only. Givers cannot confirm or cancel on the receiver's behalf |
| `submitHandoverPhoto`, `resolveHandover` | Item's giver only. Preserve server handover state and quantity validation |
| `getReuseStats` | Non-personal overall aggregates are visible to active members. eventId filters are limited to owned events. No email-level or member-level statistics |

Do not add the counterparty's member profile to reservation or handover responses. Do not reuse another party's original request content containing personal information in API responses.

## Withdrawal, active transactions, and concurrency

`withdrawMember` requires the current password again and JWT/CSRF checks. The withdrawal transaction commit is the atomic transition boundary. Success returns 204; active transactions return 409 `WITHDRAWAL_BLOCKED`.

| Actual state | Blocks withdrawal |
|---|---|
| Reservation `HELD`, `CONFIRMED` | Yes, for both giver and receiver. Expired TTL still blocks until the state actually changes |
| Reservation `HANDED_OVER`, `RELEASED`, `EXPIRED` | No |
| Handover `MISMATCH` | Yes if its reservation is `CONFIRMED`. A historical mismatch with a terminated reservation does not block |
| Handover `COMPLETED` | Not a blocking reason by itself |

Do not introduce a separate `CANCELLED` or handover `IN_PROGRESS` state. Cancellation uses the actual `RELEASED` state.

Withdrawal, new HOLDs, reservation confirmation, and final handover writes **lock the relevant member rows in ascending ID order before existing domain locks**, then recheck member state. Acquire multiple organization/request locks in a consistent order too. All write paths, agent tools, and schedulers must follow this protocol. Do not keep a DB transaction open during AI preparation; recheck immediately before the final write. If withdrawal commits first, any ongoing preparation cannot be saved and sensitive inputs are discarded. If a transaction is created first, withdrawal fails. Do not use a plain SELECT followed by a separate UPDATE that allows a new transaction to enter during withdrawal.

The withdrawal commit changes member state, increments `auth_version`, revokes all sessions, deletes signup/reset codes, creates the blocked-email HMAC, removes personal information, and disables new transactions for the member's organization together. **Every protected request starting after the commit immediately returns 401 even with a previously valid JWT.** Reads started before the commit are not retroactively recalled; final locked checks also prevent in-flight writes from committing after withdrawal.

## Privacy: delete / 30 days / 30 days

Calculate 30 days as `withdrawn_at + 30 × 24 hours`. Display response times using the existing KST convention. The 30-day transaction-history period starts **at the withdrawal commit**. If the other party withdraws earlier, use the earlier deletion deadline for that transaction; a later withdrawal does not extend it (D-070).

| Target | Immediately upon withdrawal | After 30 days |
|---|---|---|
| Member email, password hash, verification/reset codes, queued mail payload | Delete. Temporarily retain only withdrawn state and internal ID | Remove remaining member rows and FK links |
| Refresh Tokens, sessions, CSRF state | Invalidate all sessions and remove member-linked CSRF state. Withdrawn session rows may be deleted | No remaining authentication information |
| Request purpose/transcript, item conditions_transcript, free draft labels, failure messages, photos/audio, identifying free text | Remove member-organization-owned inputs or replace them with fixed non-personal values. Regenerate item labels from the existing category table. Replace organization/event names and venue with fixed non-personal values too | Remove drafts and inputs unnecessary for transaction tracing |
| Reservation, handover, and transition history | Retain only item type, quantity, state, processing time, and relationships. Do not retain photo boxes, original content, or personal profiles | Delete individual transaction history and references. Show retention-ended information in the counterparty's screen too |
| Domain idempotent response copies | Delete original response bodies and personal-data fingerprints from `idempotency_record` entries related to the member or transaction. Check party access before replay | Remove with transaction details. A new key must not recreate deleted originals |
| Email re-registration block | Separately retain only normalized email HMAC-SHA-256 + key_version + blocked_until. No original email, member_id, or organization FK | Delete the block record. Allow registration at the deadline regardless of cleanup delay |
| Non-personal aggregate statistics | Retain reused quantity/volume aggregates without member or original-content links | Retain aggregates only and delete individual transactions. No member-level or email-level analysis |

The 30-day history linked by internal IDs and an HMAC usable for email matching are not fully anonymous. Do not use a plain email hash because dictionary attacks are easy. Manage HMAC keys outside the DB and support versioned matching without extending retention. Check registration against still-valid previous keys during rotation; remove a key version older than 30 days when it has no associated block data. After expiry, the same email may create a new account if no new block exists.

**Cleanup and retention implementation requirements:** record transaction deletion deadlines in `reservation.retention_until`. Complete immediate deletions before the withdrawal commit. An implementation with large media must not report withdrawal complete before confirming deletion guarantees. Block reads at expiry first; run cleanup every minute, monitor failures, and retry. The maximum operational delay for physical deletion is one hour; reads and reprocessing after 30 days are prohibited. These are operational retention choices, not a determination of statutory periods.

After confirming that the counterparty has no active transactions, compute the transaction set to delete. After 30 days, delete `handover_log` → `reservation_transition` → `reservation`, then clean up unreferenced requests, drafts, items, events, and organizations belonging to the withdrawn organization. If a counterparty request still references other terminated transactions, keep it but remove the withdrawn party's relationship. Do not arbitrarily delete existing unassigned organizations or another member's original inputs.

Before deleting reservation details, accumulate only event/category quantity and exact volume (cm³) sums in `reuse_archive` in the same transaction. Lock the reservation and commit aggregation with original deletion to avoid double counting. Accumulate `item.archived_handed_over_qty` too, preserving inventory equality after reservation-detail deletion. Sum remaining originals and the archive: add quantities; add exact cm³ from both sources and divide by 1000 with rounding down only at the end. Do not put member IDs, reservation IDs, processing times, or original content in the archive. Immediately replace withdrawn events' names, venues, and organization names used in statistics with generic values, and remove member FKs. Preserve non-personal event/organization shells referenced by statistics only as needed for aggregates.

Do not log email, codes, tokens, mail bodies, or original uploads. Backups expire on a rolling schedule within 30 days, have restricted access, and cannot serve application reads. On restore, reapply deletions and retention deadlines before resuming service. Local DB deletion does not immediately delete information held by Resend/Meta. Verify provider retention, deletion, and region settings before sending actual personal information. This work did not verify those settings or transmit actual user data. Use synthetic fixtures only.

## JWT, sessions, and logout

| Item | Policy |
|---|---|
| Access JWT | 10-minute lifetime. `sub`=member ID, `sid`=refresh_session ID, `authVersion`=auth_version, plus `iss`, `aud`, `iat`, `exp`, `jti`. No email, organization name, or original content |
| Signature validation | RS256 only. Validate server-fixed issuer/audience and key ID; reject none/other algorithms. Keep private keys in deployment secret storage. Public-key rotation may overlap for existing JWTs' maximum lifetime |
| Immediate blocking | On every protected request, query DB ACTIVE state, auth_version, and unrevoked/unexpired sid. Fail closed with 503 AUTH_UNAVAILABLE on DB failure. No local cache delaying withdrawal enforcement |
| Refresh | CSPRNG 256-bit opaque value; store SHA-256 only. Family absolute lifetime 7 days, never extended by refresh. Under the same refresh_session family, mark the previous refresh_token as used and create a new hash row |
| Reuse | Reusing a used refresh token revokes the whole family and returns 401. Client single-flight serializes concurrent refreshes. Server family locking permits one rotation. Keep used-token evidence until family expiry |
| Single-device logout | Revoke the current cookie's whole family and immediately block JWTs with that sid. Return 204 even without a cookie. CSRF mismatch returns 403 |
| Logout all / password change or reset | Revoke all families and increment auth_version. Immediately block all Access JWTs too. No automatic login after password change/reset. Password change/reset also invalidates all outstanding PASSWORD_RESET challenges and member-linked CSRF sessions |
| Client storage | Access JWT in memory only. Never localStorage/sessionStorage, URLs, or service-worker caches. On page reload, fetch CSRF then refresh |
| Authentication responses | `Cache-Control: no-store`. Access token in JSON; refresh only in an HttpOnly cookie. Never store credential responses in the shared idempotency table |

Login failures return the same 401 `UNAUTHENTICATED` regardless of email existence or withdrawal. After five failed attempts per email in 15 minutes, limit for 15 minutes; IP limit is 20 requests/hour (429, Retry-After). No forced permanent account lock. Expired, revoked, or invalid-signature JWTs also return the same 401. A retry after withdrawal fails authentication; do not reuse a deleted password to create a success response.

## Cookies and CSRF

- Assume same-origin HTTPS deployment. `__Host-afterbooth-refresh`: `HttpOnly; Secure; SameSite=Lax; Path=/`, no Domain, Max-Age no greater than the family's remaining lifetime (maximum 604800 seconds). Delete with the same name, Path, and attributes and Max-Age=0. Authentication integration tests use HTTPS even on localhost.
- `getCsrfToken` creates a random 256-bit synchronizer token and server-side `csrf_session`. `__Host-afterbooth-csrf-session` is also HttpOnly, Secure, SameSite=Lax, Path=/, maximum one hour. Send the JSON csrfToken from memory as `X-CSRF-Token`. Do not put login or mail-sending side effects on GET.
- **Every authentication POST, changePassword POST, and member-withdrawal DELETE** validates the CSRF token and an exact configured origin match, even before login. Reject missing Origin too. Cookie security must not rely solely on SameSite. Associate CSRF state with the member on successful login/refresh and reissue it when login identity changes.
- Domain writes authenticate with Bearer only; never accept the refresh cookie as a substitute. Domain APIs do not require a CSRF header; CORS allows only the explicitly configured same origin. Do not add wildcard CORS to expose authentication APIs to external origins.
- Clear browser token memory after withdrawal, logout, password change, or reset. If a CSRF session becomes invalid, request `getCsrfToken` again before the next authentication action. Authentication APIs, changePassword and withdrawMember do not require the domain `Idempotency-Key` and do not use existing response caching/replay.

## Authenticated password change

`POST /api/members/me/password` (`changePassword`) closes the omission identified when comparing the design with draft issue 5. It is separate from the email-based forgot-password flow. No new tables or email delivery are needed (D-075).

- Require an ACTIVE member's Bearer JWT, the exact allowed Origin and `X-CSRF-Token`. Resolve the target member from the principal; do not accept a member ID or email in the body.
- `ChangePasswordRequest` requires `currentPassword` and `newPassword`, both following `MemberPassword` (12–128 characters, no trimming or normalization); reject unknown properties. The new password replaces the existing Argon2id hash only after the current password matches. No additional rule requiring the two passwords to differ is introduced.
- Success is 204 with no credentials or automatic login. Atomically update the password hash, increment auth_version, revoke every refresh family, invalidate every outstanding PASSWORD_RESET challenge and delete member-linked CSRF sessions. Immediately reject all previously issued JWTs, including the calling session. Expire refresh and CSRF cookies in separate Set-Cookie headers with their existing security attributes; the frontend clears tokens and member data and returns to login.
- Lock the member row before the final credential/version/state check. Password changes, email-based resets, withdrawal, and credential checks that issue sessions use the same member-row serialization rule, so a stale password check cannot create a valid new session after a password change. Prepare expensive hashes outside long-held locks, but recheck the source credential and auth_version under the final lock before committing.
- A wrong current password returns 403 `INVALID_CURRENT_PASSWORD`; invalid input returns 400 `INVALID_INPUT`; absent, expired or revoked authentication returns 401 `UNAUTHENTICATED`; failed CSRF/Origin checks return 403 `CSRF_REJECTED`. Throttling returns 429 `RATE_LIMITED` with Retry-After; DB authentication lookup failure returns 503 `AUTH_UNAVAILABLE`.
- Apply a separate password-change scope: five failed current-password attempts per member in 15 minutes trigger a 15-minute block; limit requests to 20/IP/hour. Persist failed-attempt counters without rolling them back with the rejected credential mutation. Share the existing auth_rate_limit foundation and do not log either password.
- Do not require Idempotency-Key and do not store request/response credentials in idempotency_record. After success, the old JWT fails even on retry; recover an ambiguous response by logging in with the chosen new password rather than replaying a stored credential response.
- Follow-up implementation tests cover correct/incorrect current passwords, malformed input, inactive/withdrawn users, another-member ID injection, CSRF/Origin rejection, request limits, all-session revocation, outstanding reset-code rejection, new-password login and races with reset/login/withdrawal. These are test requirements, not runtime tests performed in this design task.

## Signup verification, password reset, and Resend

| Item | Policy |
|---|---|
| Code | Six CSPRNG-generated digits, valid for 10 minutes after issuance, at most five verification attempts per challenge, one successful use. Separate `SIGNUP_VERIFY` and `PASSWORD_RESET` purposes |
| Code storage | HMAC challengeId+purpose+normalized email+code using a server secret. Never store the plaintext code in the DB. Validate email, challengeId, purpose, and expiry together. Signup confirmation also requires password and sets it as the final password on success. Never activate the old unverified account password, preventing pre-registration attacks. The frontend passes the chosen password only through verification-screen memory and clears it immediately afterward. Signup confirmation cannot change an ACTIVE account's password. Accumulate failed attempts transactionally. Confirmation does not use the issuance-specific 60-second interval; apply 20 attempts/IP/hour and five attempts/challenge |
| Issuance | Per purpose/email: 60-second interval and five/hour; IP: 20/hour. Use a rate-limit store shared across instances. Email limits return the same 202 with a dummy challenge; only IP limits return 429 + Retry-After. Apply the same limits to nonexistent accounts |
| Registration | registerMember creates an unverified member and returns only a generic 202. The user then calls requestEmailVerification to receive a challengeId and email. Registration itself does not send a duplicate email |
| Re-request | When issuance is allowed, a new challengeId immediately invalidates the previous challenge. For nonexistent, active, or withdrawn accounts, return only an identically shaped dummy challengeId without signup mail |
| Signup success | verifyEmail atomically sets the hash from the password submitted with email-code proof, changes PENDING_VERIFICATION to ACTIVE, and returns 204. It issues no session; the user logs in with the password |
| Password reset | Send only for ACTIVE accounts; externally return the same 202+challengeId. Submit email, challengeId, code, and new password to resetPassword. Success is 204, revokes all sessions and invalidates every outstanding PASSWORD_RESET challenge and member-linked CSRF session. This is not repeated signup email verification at login |
| Request retry | Recover a lost mail-code request response by requesting a new challenge after the issuance interval. Retrying confirmation after consumption returns 400 INVALID_AUTH_CHALLENGE. Never idempotently cache raw token responses |
| Expiry cleanup | Used, expired, and dummy challenges cannot be used; delete no later than one hour after expiry. Delete email/IP rate-limit keys within the limit window + one hour. Delete unverified members after 24 hours |

Delivery uses **server-side Resend `POST https://api.resend.com/emails`**. Subsequent implementation defines `MailGateway`/`ResendMailGateway` using the existing Java RestClient; this work adds no SDK dependency. Tests use FakeMailGateway and loopback HTTP without real external sending (D-073).

- Supply the API key and verified sender domain/address through deployment secrets; never pass them to the frontend. Never put real keys or user addresses in docs or fixtures.
- Attach a challenge-ID-based `Idempotency-Key` to Resend requests. Retries of the same send use the same key and body. The provider's deduplication retention is 24 hours, not the code lifetime.
- Prepare the code in a local transaction, then perform HTTP without holding DB locks during external sending. Do not duplicate plaintext email/code into a separate persistent outbox. On timeout, retry the same request from memory at most twice, after 1 and 2 seconds; never resend after the code's 10-minute expiry. If the process stops, do not automatically recover delivery; the user re-requests.
- Keep the public response a generic 202 so delivery failure does not reveal account existence. Detect mail outages through non-personal metrics; tell users to check their inbox and retry after an interval. Request acceptance does not guarantee inbox delivery.
- Send signup verification and password reset mail only. No marketing mail, tracking of verification-code reads, or click tracking. Domain verification, sender setup, Resend retention/region/deletion settings, and actual receipt are subsequent deployment verification items.

Official references (read 2026-11-03): [Resend send-email API](https://resend.com/docs/api-reference/emails/send-email), [Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys), [Sender domain verification](https://resend.com/docs/dashboard/domains/introduction). Security references: [OWASP CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), [OWASP forgot password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html). Lifetimes, limits, and retention periods are this product's design choices, not mandatory values from those sources.

## Shared boundaries for subsequent implementation

These are **subsequent work units**, not invented issue numbers. One work unit defines shared schemas/configuration; other work must not recreate them concurrently (D-074).

| Work unit and prerequisites | Owned files / responsibilities | Contract reused by other work |
|---|---|---|
| Member, ownership, and retention foundation; first | New member domain/repository; one shared Argon2id password encoder implemented in #65; Flyway migration for all new authentication tables/columns; unassigned existing data; deletion/aggregate cleanup | Final ERD tables and fields. Never edit existing migrations. #66 and password-management flows reuse #65's encoder |
| Shared security foundation; after schema and encoder | `com.afterbooth.global.security`, a single SecurityFilterChain configuration, JWT signer/verifier, session service, CSRF/rate limits implemented in #66 | Reuse #65's password encoder. Domain/signup work must not redefine active-member/session checks, principal, lock order, or encoder |
| Shared mail foundation; after schema/security contract | MailGateway, Resend adapter, templates, and sending configuration under `com.afterbooth.member.mail` | Signup/reset use the same gateway. Consolidate configuration changes in one file; a human supplies real mail keys in deployment settings |
| Signup/login/password-change/reset/withdrawal APIs | Member controller/service and policies, invoking the shared foundations | New auth/member operationIds and createEvent. Never recreate member schema, SecurityFilterChain, or mail gateway |
| Existing domain authorization | Principal propagation, organization checks, final-write revalidation in inventory/intake/request/allocation/handover/stats | Existing operationIds and ownership matrix. Agent tools and idempotent paths use the same authorization |
| Frontend integration; after contract/shared foundation | Authentication screens, in-memory auth state, CSRF, refresh single-flight, existing API headers | Do not edit generated clients. Shared `src/api/http.ts` changes belong only to this work unit |
| Integration verification | DB/HTTP/browser checks using synthetic data without personal information | Withdrawal versus HOLD/handover races, immediate JWT rejection, 30-day boundaries, refresh reuse, code attempts, authorization bypasses, inventory/statistics invariants after deletion |

Shared foundations require security dependencies and schema changes; explain reasons and impacts before actual application. This work adds no new dependencies, runtime code, or SQL.

## Identifiers for subsequent references

- SPEC: `Member authentication rollout scope (#63)`.
- New operationIds: `createEvent`, `getCsrfToken`, `registerMember`, `requestEmailVerification`, `verifyEmail`, `loginMember`, `refreshSession`, `logoutSession`, `logoutAllSessions`, `requestPasswordReset`, `resetPassword`, `changePassword`, `getCurrentMember`, `withdrawMember`.
- New designed tables: `member`, `withdrawn_email_block`, `refresh_session`, `refresh_token`, `auth_challenge`, `csrf_session`, `auth_rate_limit`, `reuse_archive`.
- Existing table extensions: `organization.primary_member_id`, `reservation.retention_until`, `item.archived_handed_over_qty`, `idempotency_record.actor_member_id`, `idempotency_record.related_organization_ids`.
- Decisions: D-069 rollout scope; D-070 privacy/30-day re-registration; D-071 ownership; D-072 contract/tokens/permissions; D-073 Resend/signup verification; D-074 shared work boundaries; D-075 authenticated password change and single-commit authorization.

## Verification record

Final local results on 2026-11-03, before the English translation. Node v26.0.0 / npm 11.12.1. Unless stated otherwise, commands ran from this worktree's frontend/ directory. The user's latest instruction requests English documentation. Post-translation checks are recorded below.

| Command or check | Result |
|---|---|
| `npm ci --ignore-scripts --no-audit --no-fund --offline` | Installed 243 packages from the offline cache using the existing lockfile. No dependency additions or lockfile changes |
| `node --input-type=module` using `@scalar/openapi-parser.validate` and contract/document/generated-output assertions | OpenAPI 3.1 schema valid, zero errors, 34 unique operationIds, 289 local references resolved; every operation has a documentation reference and generated function. Confirmed CSRF, 600-second JWT, signup-confirmation password, and D-069–D-074 |
| `npm run build` | prebuild's `npm run gen:api` (Orval 8.45.0) succeeded; TypeScript and Vite build succeeded |
| `npm test -- --maxWorkers=1` | 13 files / 118 tests passed. Existing screen/HTTP/MSW tests, not evidence of actual member authentication |
| `npm run lint` | Exit 0. Two existing set-state-in-effect/exhaustive-deps warnings in unchanged useHandover.ts remain |
| `npm run format:check` | Passed |
| Root `git diff --check` | Passed |
| Root `node scripts/secret-scan.mjs docs/AUTH_DESIGN.md docs/SPEC.md docs/ERD.md docs/DECISIONS.md docs/CONVENTIONS.md docs/TASKS.md docs/AGENT_MISTAKES.md contract/openapi.yaml frontend/orval.config.ts` | Passed. Output contained no real secrets, tokens, or personal information |

The first build failed because adding 401/403/503 responses to list APIs that previously had only success responses made generated return types unions with errors. A global `forceSuccessResponse` attempt failed because it conflicted with existing explicit status branches. The final configuration applies success-only types to the four list operations (listOrganizations/listEvents/listItems/listReservations) whose existing HTTP mutator throws errors. Other operations retain status narrowing. No screen or http runtime code changed. One mistaken root-level npm run build attempt failed because there was no package.json; the final commands above ran from frontend.

Independent read-only review found and fixed signup pre-registration hijacking (P1) and archive volume truncation (P2). The contract and docs now finalize the mailbox owner's chosen password at verification and preserve exact cm³ volume. The same reviewer's re-review found no blocking issues. The main session directly checked the actual diff and final verification output.

### Not verified / subsequent implementation

- Authentication endpoints, actual JWT revocation, CSRF cookies, code/attempt/request limits, password hashing, member ownership, and deletion jobs are specifications only; no runtime verification was performed.
- No DB migrations, constraints, lock races, 30-day cleanup, or archive inventory/statistics were verified against a DB; no SQL was written or applied.
- Resend keys, domains, sender, actual receipt, provider retention/region settings, browser HTTPS authentication integration, the full demo, and backend checks were not performed. This change has no backend runtime code and makes no real mail/AI calls.
- Generated client/MSW, node_modules, and dist are existing ignored local outputs. No commits, pushes, PRs, or issue closure had occurred at that verification point.

### Acceptance criteria evidence

| Issue #63 criterion | Specification / evidence |
|---|---|
| Pre-submission rollout decision and implementation scope | SPEC member authentication rollout scope, D-069 |
| Immediate JWT/refresh blocking, active-transaction rejection, email re-registration | AUTH_DESIGN withdrawal/sessions, SPEC, D-070/D-072. The user's subsequent answer changes permanent blocking to a 30-day block |
| Privacy choices, questions/answers, targets/periods | User selection and delete/30 days/30 days table, D-070. One-year/permanent retention not adopted |
| Member state, email, existing ownership | AUTH_DESIGN members/ownership, ERD extension, D-071 |
| Tokens, logout, cookies, CSRF | AUTH_DESIGN session/cookie sections, OpenAPI security/authentication operations, D-072 |
| Signup verification, password change/reset, mail delivery | AUTH_DESIGN password-change/mail sections, OpenAPI ChangePasswordRequest and challenge/password schemas, D-073/D-075 |
| APIs, errors, client generation | OpenAPI 0.3.0, 35 operations verified after the password-change correction, successful gen:api in build |
| Shared foundations and final identifiers | AUTH_DESIGN shared boundaries/reference list, ERD, D-074. No subsequent issue numbers invented or assumed |

### English translation verification

On 2026-11-03, translated the documentation added for #63, including OpenAPI summaries/descriptions, into English. Preserved existing Korean product messages, labels and speech/input examples. Updated the earlier documentation-language exception to reflect the user's latest instruction. The comments in frontend/orval.config.ts were already English.

- `node --input-type=module`: OpenAPI validation passed; all 34 operation IDs remain unique and referenced in AUTH_DESIGN. Comparing the parsed contract with its pre-translation snapshot, excluding summary/description text only, confirmed identical schemas, security declarations, paths, identifiers and examples. No added Markdown/comment lines contain Korean; remaining Korean contract snippets match the existing product examples.
- `npm run build` in frontend/: client generation, TypeScript and Vite passed again after translation.
- Root `git diff --check` and the secret-scan command listed above passed again.
- Did not repeat the 118-test suite, lint or format checks for this documentation-only translation; their results above belong to the preceding design verification. Runtime code and generation settings are unchanged by this translation.
- No commits or pushes had been made at that verification point; runtime implementation and database changes remain out of scope.

### Local commit authorization and sensitive-data check

The user subsequently authorized local commits after checking for important or sensitive data. Rechecked the exact changed-file allowlist, the repository secret scanner and diff hygiene before staging. The user later canceled the two local commits while preserving changes, requested the missing password-change specification, and explicitly authorized a single combined local commit for #63 as an exception to the contract-only split rule. Existing ignored generated outputs and dependency/build directories are excluded. The repository hooks repeat the staged-content secret scan and Conventional Commit validation. No remote write is authorized.

### Password-change correction and combined-commit verification

The user identified the missing authenticated password-change flow through the earlier draft issues, canceled the two local commits while retaining their changes, and requested one combined Conventional Commit after correction. The staged files matched the earlier commit tree before this correction; no reset or deletion of user changes was necessary. Draft-issue policy wording and implementation work assignments remain deferred to future issue creation as requested.

Final correction checks on 2026-11-03:

| Command or check | Result |
|---|---|
| Root `node --input-type=module`, using @scalar/openapi-parser, YAML and strict assertions | OpenAPI valid; 35 unique operations; 298 local references resolved; ChangePasswordRequest fields, Bearer/CSRF, 204/400/401/403/429/503 responses, INVALID_CURRENT_PASSWORD and no-store confirmed. All 35 generated functions and documentation references exist. All unrelated structural contract fields match the pre-correction snapshot after removing the new operation, request schema and error enum value |
| frontend/ `npm run build` | Orval generation, TypeScript and Vite passed |
| frontend/ `npm test -- --maxWorkers=1` | 13 files / 118 existing tests passed; these do not verify the unimplemented password-change runtime |
| frontend/ `npm run lint` | Exit 0 with the same two existing useHandover.ts warnings |
| frontend/ `npm run format:check` | Passed |

The new password-change and related reset-invalidation behavior remain specifications only. No authentication runtime, backend source, SQL, real DB, mail delivery or external AI execution is included. Fresh independent read-only review found no blocking issues. Its decision-table formatting and final operation-count suggestions were corrected. Final diff hygiene and changed-file secret scanning passed; the staged-content scan and commit-message checks run again at commit time. One initial scanner match was a documented error-code name following the words "password:"; rewording the description removed the false positive without bypassing the scanner. No actual secret was involved.
