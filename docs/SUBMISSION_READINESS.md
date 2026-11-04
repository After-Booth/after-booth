# Issue #17 submission readiness — 2026-11-04

**Not submission-ready. No external submission was made.** The requested automated English owned-data replay scope is now complete; see the latest full-demo verification section below. Video, forms and submission remain outside this task. Automated checks, authenticated transactions, real Meta recordings, human screen review and submission are separate evidence categories.

## Baseline and scope

- Fetched remote main: `d02c98232cad21ae65d5c94a6bb45fee77bc23f0` (PR #76).
- Branch: `fix/17-submission-readiness` (renamed from `codex/17-submission-readiness`); worktree: `/private/tmp/afterbooth-17-submission-readiness`.
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
| `a9237b0` | Authenticated English submission readiness report and run-guide updates |
| `cce5370` | Exact English replay recording inputs and deterministic demo-state preparation |
| `e5d7b28` | Approved single-condition recording and replay recovery |
| `1fe9ab4` | Full owned-data English replay, both race branches and E2E pass |

The documentation commit `a9237b0` adds current README/fixture/run guidance, this G3 report, exact verification records and TASKS status. The rows above also cover the later replay preparation, approved recordings and full-demo verification. Local commits are the endpoint: no push, PR, main merge, GitHub issue/comment/status change, visibility change, deployment, video upload or external submission occurred. The original checkout remains on main.


## Replay recovery preparation on `fix/17-submission-readiness` (2026-11-04)

This section is the pre-approval snapshot committed in `cce5370`. The approved execution below supersedes its statements about the missing condition fixture and zero calls; full-demo blockers remain.

This follow-up started at `a9237b0` on the existing `fix/17-submission-readiness` branch with a clean working tree. No branch switch, new worktree or main-based reset was performed. The branch was renamed from `codex/17-submission-readiness` to its current name during this work; the baseline above uses the current name.

### Reproduced failure and exact input

`ConditionInterpretationReplayTest` fails in the current build: the original Transcribe exchange succeeds in REPLAY, then Spark fails and ConditionService returns AI_UNAVAILABLE. The new offline `EnglishConditionRecordingTest.identifiesExactEnglishRequestWithoutNetworkOrInventedResponses` independently verifies the current canonical key and the complete request difference:

- Missing: `spark/4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711.json`.
- Original: `spark/0890f3ea8678a3cf3e08248a08b4e7299843bb93cf909058c3e9600cbc5a3dc0.json`.
- Only the system message differs for this condition request: current `ConditionPrompt.SYSTEM` requires English purpose text. The original Korean transcript, RECEIVER context, reference date `2026-10-31`, schema, `muse-spark-1.3` and `minimal` effort remain identical. The new request is serialized through the actual MetaAiGateway; no response is synthesized.
- No-network real-pipeline preparation also pins the first Spark requests for bins intake (`d65a99d1191a0ef88b8c7e5c859570facfc128fceb16a8e61ea7aea65c8a2805`), rack intake (`97e4168ccb9cb37cd0082effe2effc27d42ac36e9bac55a1802322b1e54322ed`) and rack handover (`31186f2f5caf929e96f0f27e1499f19cc7e70e17bf09b4940ecd6e87b23cce1b`). Photo SHA-256 values are checked before request generation. These three preparation tests abort before any response and do not establish a successful pipeline.
- Other demo requests have additional differences: English model/tool messages and actual owned inventory IDs, dimensions, condition and stock. AllocationModelData excludes auth/session/request IDs and wall-clock timestamps, but retains item IDs and all server decision inputs. Genuine model tool-call IDs and earlier replies determine subsequent turn hashes.

### Isolated initial state and order

The owned-demo stack creates its own disposable PostgreSQL container, migrates it, checks that no member exists, and resets only that container's seed domain tables with identity restart. It does not accept an external database target. Default auth tests retain their existing seed setup. Authentication, ownership, constraints and production member-recording protection are unchanged.

1. Create three verified synthetic accounts in independent HTTPS browser contexts, giver first and receivers A/B next. Credentials, codes and ephemeral signing/TLS keys stay outside fixtures and documentation. Expected organization IDs: 1/2/3.
2. Giver creates event #1, `Synthetic Reuse Expo`, venue `Synthetic Hall`, teardown `2030-01-01T18:00` KST. Pin the condition reference date to `2026-10-31`.
3. Intake `spike/samples/bins-orange-stack.jpg`, then `spike/samples/rack-gondola-single.jpg`, without edits; assert four bins and one rack. Expected rack item ID: 2. Real model output supplies actual dimensions/condition; do not edit recordings to force a vehicle result. Stop and review if model output does not satisfy the scenario.
4. Receiver A uploads the unchanged Korean `audio-receiver-racks-sedan.wav`: request #1, three display racks, SEDAN. Check no HOLD, VEHICLE_TOO_SMALL and an actionable alternative. This is Korean speech with English generated purpose/explanations, not English speech verification.
5. Both receivers enter exactly `We need one display rack. We will collect it in a van.`; save requests #2/#3. Race the last rack: exactly one HOLD, loser NO_MATCH with QTY_UNAVAILABLE and a visible, enabled alternative.
6. Winner confirms the HOLD. Giver uploads `rack-gondola-single.jpg` for handover; assert HANDED_OVER, quantity one, stock zero and event-filtered reuse statistics matching the API.
7. A separate fresh-state run must also cover the other losing branch: both searches see stock one then a rejected HOLD, versus a search after the winner commits that sees stock zero. Browser overlap alone does not prove both branches were recorded.

### Paid gate: staged recording, not a full-demo authorization

Production MetaExchange intentionally rejects RECORD inside member-owned work (D-083). Do not enable recording globally or suppress the member scope to film the authenticated app. `EnglishConditionRecordingTest.recordsExactlyOneApprovedSparkRequest` instead submits one immutable synthetic condition request directly through AiGateway outside member work. Before transport it checks the full expected hash, standard model, official endpoint and repository fixture directory. It makes at most one new HTTP attempt, with no validation re-ask or automated retry; an existing fixture is reused.

The prepared first stage is one Spark call only: model `muse-spark-1.3`, POST `/v1/chat/completions`, effort `minimal`, exact missing key above. It reuses existing real Transcribe evidence and does not call SAM or Transcribe. Estimated typical cost is about $0.002, based on the historical condition measurement. Current [official pricing](https://dev.meta.ai/docs/pricing-rate-limits) is $1.25/M input and $4.25/M output tokens; [official model configuration limits](https://dev.meta.ai/docs/quickstart) list 1,048,576 context and 131,072 output tokens. Charging both maxima conservatively gives $1.867776 per attempt: propose a $2 approval ceiling for this single attempt. This is a provider-limit/rate bound, not a client-side hard dollar meter. Adding a token parameter would change the exact request hash and cannot be done just for the recording.

After separate approval, a human-controlled secret loader may supply the key to the existing process environment without exposing it; select only `*EnglishConditionRecordingTest.recordsExactlyOneApprovedSparkRequest` with `-PrecordEnglishCondition`. No environment secret file was inspected. Record response usage/cost in BUDGET after the attempt, including an unsuccessful attempt, scan the fixture and run ConditionInterpretationReplayTest again. Never paste a key into chat or a command argument.

The full-demo recorder is not yet executable. Follow-on photo/SAM and allocation tool turns require reviewed synthetic request capture and actual earlier Meta replies; the ordinary authenticated app RECORD path remains forbidden. The old $0.05–$0.15 full-demo forecast is not a hard spending cap or approval. A separately bounded plan is still required for those stages. One condition fixture does not clear ENGLISH_RECORDINGS_READY.

### Newly verified and remaining

- Java 21: `spotlessApply compileJava compileTestJava spotlessCheck` and 60 selected tests passed: exact English request, FixtureStoreTest, AiGatewayModesTest, MetaAiGatewayTest, MemberInputPrivacyTest and ArchitectureTest. An initial new-test type error was fixed; overlapping build output was discarded and the successful verification ran serially.
- Three additional SyntheticDemoRequestPreparationTest photo/handover first-request checks passed with format/compile.
- E2E TypeScript passed; `test:demo` still fails explicitly at the unchanged readiness gate and passes all ten overlap/helper tests. A separate auth execution passed six real-browser checks. A separate owned-stack smoke executed the disposable seed reset and cleanup successfully.
- The original condition replay remains failing until an approved real fixture exists. Full English authenticated replay, both recorded race outcomes, HANDED_OVER/statistics with real recordings and English speech remain unverified.
- No paid call, real mail, environment secret-file access, real-user/production database access, external write or screenshot-evidence collection was performed. No new Meta fixture or budget ledger entry exists. Submission remains blocked.


## Approved single-condition recording result (2026-11-04)

The user separately approved the proposed single Spark attempt, with a $2 ceiling. No follow-on paid calls were authorized or executed. The existing secret loader supplied credentials to the process without printing or manually inspecting the environment file; no real-user or production DB was accessed. Branch `fix/17-submission-readiness` and preceding commits were preserved.

`EnglishConditionRecordingTest.recordsExactlyOneApprovedSparkRequest`, selected with `-PrecordEnglishCondition`, passed with one real `muse-spark-1.3` RECORD exchange (`outcome=recorded`, 2,436 ms). The resulting fixture is `fixtures/replay/spark/4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711.json`. Its request hash was independently recalculated and matched the filename. The parsed real response passed the category, quantity, vehicle, nonblank English purpose and no-tool-call assertions; no response was translated or manually edited.

Usage was 542 prompt and 182 completion tokens, including 150 reasoning tokens, for an estimated $0.001451 at current Standard rates. [BUDGET](BUDGET.md) records the calculation and cumulative estimate; actual dashboard billing remains unverified. Original 19 recordings are byte-identical to the pre-work digest baseline. The new fixture passed the repository secret scanner and structural inspection for credential fields, email patterns, key markers, bearer values and inline media payloads. No credentials or temporary keys were found.

A fresh key-free, replay-only backend run passed all 20 selected checks with zero failures/errors/skips: ConditionInterpretationReplayTest, the exact English hash test, SyntheticDemoRequestPreparationTest, FixtureStoreTest and MemberInputPrivacyTest. The previously reproduced condition failure is resolved by a real Meta recording. This does not validate English speech: the unchanged source WAV and Transcribe evidence remain Korean.

ENGLISH_RECORDINGS_READY remains false. The full-demo recording runner, owned-rack intake, English allocation turns, both losing race branches and completed photo handover/statistics with genuine recordings remain unfinished. The one-condition approval does not cover those paid requests. No push, PR, merge, screenshots for D-061/D-062/D-084 or external submission occurred.


Post-recording `npm --prefix e2e test` was run in full: TypeScript passed; all six authentication browser checks passed against a fresh disposable HTTPS stack; ten overlap/helper checks passed; the full demo failed explicitly at the unchanged ENGLISH_RECORDINGS_READY prerequisite. The overall command exited 1. This is a verified remaining failure, not a full E2E pass.


## Full owned-data English replay verification completed (2026-11-04)

This result supersedes the earlier missing-recordings/full-runner blockers for the automated replay scope only. Work continued from `e5d7b28` on the same clean `fix/17-submission-readiness` branch; no branch reset or external write occurred. The user explicitly approved additional bounded recording, separate from the initial one-condition call.

- Actual new Meta exchanges: 12 successful recordings, 11 `muse-spark-1.3` Standard and one `sam-3.1`; no retry, failed attempt or new Transcribe call. Spark usage: 9,718 input / 4,548 output tokens. Estimated total $0.03397650 under the $2 / 30-attempt session limits, recorded in BUDGET. Prior condition-call cost remains separate. Actual dashboard billing was not inspected.
- All 20 pre-existing fixture files are byte-identical to the pre-run digest baseline. The new fixture index lists actual request hashes and usage metadata; all canonical keys are recomputed and new responses are replayed through the actual AiGateway. No response was translated, fabricated or edited.
- The product stayed in REPLAY. A test-classpath-only application captured missing request manifests; an independent approved recorder reconstructed typed gateway inputs and validated the complete serialized request before calling the provider. Production member-owned RECORD protection and all authorization/idempotency/quantity rules remain unchanged. No member work scope was cleared or moved to an unowned executor.
- Both controlled full candidates passed: two plans propose against stock one and finalization rejects the loser; or the second active server request searches after the first real HOLD commits and sees stock zero. Test-only barriers/waits change timing only, never stock, tool results or model replies. Both runs assert actual HTTP interval overlap, exactly one winner, zero loser holds, QTY_UNAVAILABLE and a visible enabled alternative. Each completes receiver confirmation, giver photo handover, HANDED_OVER and API/UI statistics. A separate natural scheduling replay candidate passed without paid calls.
- Only after actual full replay success was ENGLISH_RECORDINGS_READY set true. The default `npm --prefix e2e test` then passed on the ordinary product jar: TypeScript, 6 auth checks, 1 full owned-data scenario and 10 overlap/helper checks; exit 0. It uses no capture application/scheduler and never calls Meta.
- Backend Java 21 `spotlessApply compileJava compileTestJava spotlessCheck test ... bootJar prepareReplayTools` passed all 28 selected tests with zero failures/errors/skips. Classes: EnglishOwnedDemoReplayTest, EnglishReplayRecorderTest, ConditionInterpretationReplayTest, IntakePipelineReplayTest, MemberInputPrivacyTest, FixtureStoreTest and ArchitectureTest. The original two replay tests first exposed a test-configuration scan collision; explicit preparation-main activation fixed it, and the successful combined run was serial.

The shared scenario keeps every original domain/outcome assertion. Its event/reservation filters now target the native combobox's actual accessible name, including dynamic option text. Account navigation waits for the account screen before resetting intake/request screens. Original hashes and fixed photo bytes remain authoritative. The live rack detection supplied one GOOD rack, 90 × 60 × 150 cm; no dimensions were edited to force success. The owned rack is item #2; bins are item #1.

A proposed paid extension for natural scheduling was rejected by automatic approval review as outside the approved two-branch scope; that command did not execute. The natural candidate was instead run as replay only and passed, so no additional paid scope was needed. Independent read-only review inspected the member protection, scheduling and reservation design without finding an actionable blocker in the inspected portion, but the reviewer hit its usage limit before completing a final verdict; no completed independent approval is claimed.

No manual/video capture, form work, submission, screenshots for D-061/D-062/D-084 or real mailbox delivery was performed. The source WAV and existing Transcribe evidence remain Korean; English speech recognition and live-model performance are not established by replay. Temporary credentials/TLS/HMAC/JWT material and private request/budget session files stay outside Git. Current executable details are in [ENGLISH_REPLAY](ENGLISH_REPLAY.md).


Final additional regression run: AllocationIntegrationTest (15), HandoverIntegrationTest (15), DomainAuthorizationIntegrationTest (35), CatalogAuthorizationIntegrationTest (6) — 71 passed, zero failures/errors/skips against disposable PostgreSQL. Together with the separately successful 28 replay/tool/privacy/architecture checks, 99 backend checks passed across two runs; no full-backend-suite pass is claimed. Changed-file secret scanning and all 12 new fixture canonical/status/credential/email/inline-media inspections passed; all 20 originals remain byte-identical. The ordinary product jar was inspected and contains none of the test-only capture/recorder classes.
