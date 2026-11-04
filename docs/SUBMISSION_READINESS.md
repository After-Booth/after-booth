# Issue #17 submission readiness — 2026-11-04

**Not submission-ready. No external submission was made.** Automated checks, authenticated transactions, real Meta recordings, human screen review and submission are separate evidence categories.

## Baseline and scope

- Fetched remote main: `d02c98232cad21ae65d5c94a6bb45fee77bc23f0` (PR #76).
- Branch: `codex/17-submission-readiness`; worktree: `/private/tmp/afterbooth-17-submission-readiness`.
- The original checkout stays on `main`; it started clean. No shared-folder branch switch occurred.
- Reviewed tracked source, Markdown, contract, build, Compose, CI and tests. Generated API clients/MSW files are intentionally untracked build outputs, not missing source.
- Current UI, server messages, AI explanations, README and run guides are English. The user's latest correction also requires new repository records in English. Preserve historical records and original Korean inputs.
- Purpose-specific local commits only. No external writes, deployment or paid AI calls.

## Historical evidence reconciliation

| Item | Current evidence | Meaning and limit |
|---|---|---|
| #15 | CLOSED; PR #60 merge `02ab1d9` | Historical author-reported clean-clone and Mermaid checks justify correcting TASKS. They do not verify the current authenticated build. |
| #9 | CLOSED; PR #46 R6–R8, main5/main6 reports, PR #60 demo record and visible/actionable-alternative assertions | Correct the historical acceptance checkbox. Do not attribute the strengthened overlap gate to every past run. |
| #70 | CLOSED, but PR #76 says `Refs #70` and explicitly lists remaining criteria | Closure alone does not prove full acceptance. Track real-server password recovery, time-controlled checks, full transactions and authenticated demo separately. |
| #17 | Latest body/comment require submission checklist, two teammates' review, exact inputs and a current video | Leave unconfirmed items unchecked. |

PR #60 and #46 executions are historical author reports, not this session's runs. Original logs and actual billing were not independently rechecked. The model roles, three historical live runs and approximately $0.605 estimate match BUDGET/TASKS; they are not speed/cost measurements of this English authenticated build.

## Runtime and provider boundary

Current commands are in the [README](../README.md), [E2E guide](../e2e/README.md) and [fixture guide](../fixtures/README.md). The default Compose HTTP app/health is separate from HTTPS authentication success. Authentication remains enabled. Mail defaults to disabled; local-only Fake requires explicit activation. Real mailbox delivery and deployment Resend configuration are unverified.

Current automated verification uses `AI_MODE=replay`, synthetic members, disposable PostgreSQL and explicitly identified test doubles. Fake codes stay in test memory. There is no public code lookup or verification bypass. Temporary keys/certificates are outside Git. No secret configuration file or real-user/production database was read.

## English replay and remaining demo

English condition/allocation prompts, category labels and tool feedback change request hashes. Existing allocation recordings also contain seeded `itemId: 1`, which differs from newly owned member inventory. Original request/response files and key validation remain unchanged. Synthetic test responses are not Meta recordings.

The authenticated English full demo and real-model allocation → completed photo handover → statistics, including the last-item race, remain incomplete until new recordings exist. Default E2E fails explicitly rather than hiding this behind a skipped demo. An auth-only pass is separate from full-demo success. Original audio and the preserved transcription bias are Korean; English speech recognition/structuring/allocation is unverified.

Proposed paid recording: one synthetic giver and two receivers, owned events/inventory and exact approved English inputs, covering the scenario and both race outcomes in new fixtures, followed by replay checks. Based only on historical 19-call runs at about $0.04 plus extra branches, an initial estimate is $0.05–$0.15; it is neither current pricing nor a success guarantee. Confirm purpose, cap, inputs and pricing before separate approval. This request does not authorize spending, so no call was made.

## Submission checklist and G3

| Item | Status | Required action before submission |
|---|---|---|
| Public repository | GitHub metadata `visibility=public` confirmed | Check final access/link; no visibility setting changed |
| Product, architecture and model roles | README updated; historical measurements scoped | Human review of the final changes |
| Demo login | Synthetic accounts and isolated HTTPS sessions defined | Prepare verified accounts before filming; exclude credentials |
| English AI full demo | Incomplete | Approved recording, full transaction/statistics and race verification |
| Both teammates' screen review | Unconfirmed | Actual review evidence from both people; agent review is not a substitute |
| Current 2–3 minute video/link | Unconfirmed | Record/review current English authenticated flow; humans handle upload |
| Submission form fields/access/links | Unconfirmed | Inspect the actual form and its required fields |
| Actual external submission | Not performed | Human final review and explicit submission action |

This document provides the G3 report without marking Phase 3 complete. Historical self-scores are not a current readiness score. Rescoring is deferred because English replay, video and human review remain open. Dates and constraints in SPEC are existing records, not newly confirmed external form requirements.

## Current execution results

All checks below are newly executed for this worktree unless explicitly labelled historical. No real Meta or Resend call was made.

| Check | Command or scope | Result |
|---|---|---|
| Bug-only mounted regressions | Separate baseline copy: `npm test -- --maxWorkers=1 src/App.test.tsx src/features/allocation/Allocation.test.tsx src/features/handover/HandoverScreen.test.tsx src/features/handover/HandoverRecovery.test.tsx` | 62 passed; formatting passed |
| English frontend | `npm test -- --maxWorkers=1` | 17 suites, 141 passed |
| Last menu/text adjustments | `npx vitest run --maxWorkers=1 src/App.test.tsx src/features/auth/AccountFlows.test.tsx` | 9 passed |
| Frontend gates | `npm run build`, `npm run lint`, `npm run format:check` | Passed; lint retains two pre-existing useHandover warnings |
| Backend preparation | Java 21: `./gradlew spotlessApply compileJava compileTestJava spotlessCheck bootJar --no-daemon --max-workers=1` | Passed |
| Actual HTTPS transaction/password checks | `./gradlew test --tests com.afterbooth.LocalFakePasswordHttpsTest --no-daemon --max-workers=1` | 7 passed; includes two-member full transaction with a synthetic AiGateway and expired/used reset codes |
| Time and ownership checks | SignupIntegrationTest, WithdrawalIntegrationTest, RetentionIntegrationTest | Successful affected runs; pending lifetime, generic responses, ownership isolation and expired-history access |
| Real browser authentication | `npm run test:auth` in e2e/ | 6 passed; local HTTPS, fresh PostgreSQL, local Fake, replay |
| Full-demo readiness | `npm run test:demo` in e2e/ | Expected failure: missing current English recordings; ten overlap/helper tests passed |
| Existing real-gateway condition replay | ConditionInterpretationReplayTest | Failed: original Transcribe replay matched; new English Spark request has no recording |
| Standalone E2E typing and helpers | `npm ci --offline --ignore-scripts --no-audit --no-fund`, `npm run typecheck`, `npm exec --no -- playwright test tests/overlap.test.ts --reporter=list` | Passed; 10 helper tests passed; committed configuration covers Node modules, ECMAScript modules and DOM iteration |
| Source and links | `git diff --check`, tracked-file existence and current guide relative links | Passed; zero missing tracked files, generated client intentionally untracked |

The exact missing Spark file is `fixtures/replay/spark/4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711.json`. The audio digest matched the original Transcribe fixture; its transcript matched the historical condition request. Recomputing canonical SHA256 with the changed system prompt produced this missing key. No raw auth data or recording body was printed.

The actual HTTPS integration exercises two normal local Fake signups, an owned event/photo inventory, receiver request, the allocation tool loop, HOLD and confirmation, giver MISMATCH/cancel 403, completed recount, HANDED_OVER and statistics. A second mismatch verifies receiver cancellation. After withdrawal, test-only timestamp movement verifies retained physical history becomes inaccessible through the actual API. The AiGateway response is synthetic throughout that transaction test; it does not establish real-model accuracy or replay readiness.

The browser's refresh failure is injected HTTP, followed by real server logout 204, refresh-cookie removal and reload with no automatic login. A failed server logout still follows the existing local-cleanup policy; it cannot guarantee server revocation or cookie removal. Password expiry/used-code and 24-hour/30-day tests are backend checks; time-controlled browser messaging remains unverified.

Visual QA: inspected credential-free login and refresh views, including 360/390/1280 widths. Initial mobile navigation overlap was corrected with shorter Pickup/Board labels while retaining accessibility names; fresh bounding-rectangle checks and visual inspection passed. No human teammate review is inferred. Full intake/request/handover/account screen coverage at every viewport is not claimed.

A final affected backend run first passed 90 of 91 cases and reproduced a reservation-list display defect: JPQL selected the stored label directly. The fix preserves one-query loading, projects the category and derives its English label. Final ReservationIntegrationTest (33) and ArchitectureTest (7) passed; the other successful suites were CoreDomain (10), ErrorHandler (11), Handover (15) and Allocation (15). These are separate executions, not a claimed single all-green 91-test command. See the [exact backend verification record](evidence/issue-17-backend-verification.md).

The authenticated full-demo test code now uses three verified members, an owned event and photo inventory, an explicit race, receiver confirmation, giver handover and event statistics. It has passed strict TypeScript checking and Playwright discovery; runtime success is blocked by missing recordings.

The two retained lint warnings concern synchronous photo URL state in an effect and generation-ref invalidation in cleanup. Existing URL cleanup and async invalidation remain intact; no defect was reproduced to justify unrelated refactoring.

An initial fresh Codex CLI review found no actionable regressions and independently ran 141 frontend tests and whitespace checks. A separate read-only review of the rewritten E2E found two issues: saved-request screen reuse and unhandled Java spawn failure. Both were corrected; an actual no-Java PATH fault verified rejection and zero leaked disposable containers/TLS directories. The main session inspected those fixes. Neither review executed backend/browser checks. The companion plugin and a Claude review were not run. No PR or remote review was created.

Staged secret scans, actual staged-diff inspection and repository commit hooks passed for the completed commits. A scanner finding on a new static synthetic demo password was resolved by generating it in memory with randomUUID; no allow marker was added. The privacy reporter emits only fixed diagnostics, whitelisted HTTP status and source locations, never arbitrary assertion values. No build output, browser traces, credentials, test keys or screenshots are committed. Final documentation receives the same staged checks before commit.

## Language and repository hygiene

English changes cover the frontend document title/language, app shell, HTTP fallback messages, AI progress, auth/intake/request/allocation/handover screens and hooks, mocks and associated tests. Backend changes cover ErrorCode, signup guidance, category display, intake failure messages, allocation reasons/explanations/tool summaries, condition/allocation prompts and the reservation listing projection. Contract descriptions/examples were committed separately and generated clients were regenerated through the frontend build. No generated client was hand-edited or committed.

Intentional Korean remains in original user/transcript examples and the request mock, Korean input-recognition rules, Unicode/password/input tests, historical TASKS/DECISIONS/BUDGET/spike records, committed migrations and original provider recordings. These are not current fixed UI strings. The transcription bias remains Korean; no English speech success is claimed. Existing persisted labels are preserved and mapped to English category labels for display, including the query projection. No runtime dependency, API shape, ownership rule or migration was introduced. E2E now declares TypeScript and Node types as direct development dependencies and commits its own tsconfig; npm ci and npm run typecheck passed without frontend type-root overrides.

## Local commit inventory

| Commit | Purpose |
|---|---|
| `6216e80` | Contract-only English display descriptions and examples |
| `6897148` | Historical #9/#15 completion and migration documentation, rewritten in English after the user's undo |
| `c718064` | Refresh logout and receiver-owned cancellation controls with regression tests |
| `80e4bb8` | English product text, server messages, prompts and related regressions |
| `3c56d5a` | Actual HTTPS synthetic-AI transactions and time/ownership checks |
| `dc73532` | Reservation listing display projection fix and remaining affected English assertions |
| `e86b788` | Isolated authentication E2E, recovery/privacy checks, owned-data demo gate, standalone TypeScript configuration and E2E run guide |

The final documentation commit adds current README/fixture/run guidance, this G3 report, exact verification records and TASKS status. Local commits are the endpoint: no push, PR, main merge, GitHub issue/comment/status change, visibility change, deployment, video upload or external submission occurred. The original checkout remains on main.
