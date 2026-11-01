# TASKS

Update at the end of every phase so progress is visible at a glance. Mark blocked items with `BLOCKED — <reason>`.
The unit of work is a GitHub issue. Owners: **DY** = namdongyeob, **WJ** = WookJaes. Both do backend and frontend.

## Phase 0: Bootstrap + spike (no feature implementation)

- [x] 0-1 Repo layout: backend/, frontend/, contract/, docs/, fixtures/, spike/, docker-compose, .gitignore
- [x] 0-2 docs/SPEC.md, TASKS.md, DECISIONS.md (+ BUDGET.md, AGENT_MISTAKES.md)
- [x] 0-3 Harness: AGENTS.md (single rulebook, #25), five .claude agents, feature skill, hooks, .codex
- [x] 0-3b Conventions, review guide, code templates, PR/issue templates, commit-msg hook, ArchitectureTest
- [x] #18 Translate docs, harness and code comments to English (DY)
- [x] #1 Contract draft + `docs/ERD.md` (DY)
- [x] #21 CI checks, required on main (DY)
- [x] #23 Conclusion-first, traceable issue/PR/review templates + `pr-description` check (DY)
- [x] #25 One rulebook: `AGENTS.md` for every agent, `CLAUDE.md` removed (DY)
- [x] #2 Spike: model list, Spark text/image/function calling, SAM 3.1, Transcribe, cost → spike/REPORT.md (DY, key A, $0.44)
- [x] **G0 STOP** — report the REPORT summary and the decisions for humans (all five accepted: D-029–D-033)
- [x] #28 SPEC, contract 0.2.0 and ERD follow the G0 pipeline (DY)

## Phase 1: Features (target 11/1 night KST)

| Issue | What | Owner | Scope |
|---|---|---|---|
| #3 | Domain model, Flyway, seed | WJ | BE |
| #4 | AiGateway live/record/replay | DY | AI |
| #5 | Photo intake (Spark → SAM box prompts → inventory draft → confirm, D-029) + capture screen | DY | BE+AI+FE |
| #6 | Voice pickup request (Transcribe → Spark structuring) + request screen | DY | BE+AI+FE |
| #7 | Reservation core: conditional HOLD, idempotency, state machine, expiry scheduler + required tests | WJ | BE |
| #8 | Vehicle fit + reason codes | WJ | BE |
| #9 | Allocation agent (function calling, server validation, alternatives) + allocation board | WJ | BE+AI+FE |
| #10 | Handover recount + reuse stats + screens | WJ | BE+AI+FE |

- [x] #3 · [x] #4 · [x] #5 · [x] #6 · [x] #7 · [x] #8 · [ ] #9 · [x] #10

#9 implementation and local automated checks are complete, but its actual two-browser acceptance criterion remains open. #12 owns that browser scenario and the separately approved live end-to-end run. Do not treat database concurrency tests as browser execution or close #9 before that criterion is verified or explicitly reassigned.

#7 merged into main in PR #34 (`c414209`). In `backend/`, `./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*ReservationIntegrationTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*VehicleFitPolicyTest' --tests '*ReservationRulesTest' --tests '*ReservationSchedulerTest' --tests 'com.afterbooth.ArchitectureTest' --tests 'com.afterbooth.CoreSchemaIntegrationTest' --tests '*CoreDomainTest' --tests '*GlobalExceptionHandlerTest' --no-daemon` passed all 74 selected tests, including 32 reservation and two default-configuration PostgreSQL HTTP tests. Cargo defaults and overrides follow D-040; #9/#10 integration boundaries follow D-041/D-039. Frontend, live AI and end-to-end demo were not run.

#8 implements reusable detailed vehicle decisions through `VehicleFitPolicy.checkFit`; HOLD validation uses the same result. D-050 defines rotation diagnostics; D-040 demo cargo defaults remain unchanged. In `backend/`, `./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*VehicleFitPolicyTest' --tests '*DimensionsTest' --tests 'com.afterbooth.ArchitectureTest' --tests '*CoreDomainTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*ReservationIntegrationTest' --no-daemon` passed all 66 selected tests with no failures or skips, including 34 PostgreSQL HOLD/reservation tests. Frontend, the full backend suite, live AI and the end-to-end demo were not run; #9 owns the allocation tool and response mapping.

#9 implements the bounded allocation tool loop, shared server HOLD validation, contract responses and human alternatives, real inventory reads, request proposals and the polling allocation board. D-051 records the transaction and model-timeout boundary. Fresh independent and adversarial reviews found no blocking findings. The human-approved Muse Code review found two confirmed UI issues: M3 partial-HOLD recovery had no manual top-up, and M4 polling errors remained after recovery. Both were fixed and tested; D-052 records all four Muse findings and their dispositions, including the original screenshot. R1 documents database waits outside the model-loop budget. The main session inspected the actual diff and re-ran these gates on macOS with working Docker access (the PowerShell warning above concerns the earlier Windows environment):

```bash
cd backend
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*AllocationIntegrationTest' --tests '*MetaAiGatewayTest' --tests 'com.afterbooth.ArchitectureTest' --tests '*ReservationIntegrationTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*VehicleFitPolicyTest' --tests '*DimensionsTest' --tests '*ReservationRulesTest' --tests '*ReservationSchedulerTest' --tests '*PickupRequestIntegrationTest' --tests '*CoreSchemaIntegrationTest' --tests '*GlobalExceptionHandlerTest' --no-daemon
# 109 passed, zero failures/errors/skips; includes 11 allocation PostgreSQL Testcontainers tests.
cd ../frontend
npm run build && npm test && npm run lint && npm run format:check
# Build passed; 58 tests passed; lint and formatting passed.
```

The first backend run passed 107 of 108 tests; the replay assertion incorrectly compared JSON strings after PostgreSQL JSONB reordered object keys. Comparing parsed JSON fixed that assertion; the final gate also includes a new forbidden-tool-after-HOLD test. AI behavior was exercised through a local gateway test double and loopback HTTP stubs, without product live/record calls. Not verified: the full backend suite, actual two-browser/server UI scenario, the integrated demo, and product live AI. Existing recorded fixtures do not cover arbitrary allocation requests; default replay returns AI_UNAVAILABLE when the exact fixture is absent rather than inventing a result.

The subsequent independent pre-PR review found R5: already-held item IDs were excluded even when eligible stock remained, so a partial allocation could not be topped up on the same page. R5 is fixed by retaining eligible stock in alternatives, capped to the outstanding request quantity. A fresh independent re-review confirmed the fix with no new blocking findings. The main session re-ran `./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*AllocationIntegrationTest' --tests 'com.afterbooth.ArchitectureTest' --no-daemon` in `backend/`: all 20 tests passed (13 PostgreSQL allocation tests and seven architecture tests), with zero failures/errors/skips. `npm run build && npm test && npm run lint && npm run format:check` in `frontend/` passed, including all 59 tests. The new regression covers stock ten, request five, HOLD two, later model failure, same-item alternative three, manual HTTP top-up three, and over-cap rejection without quantity corruption; the mounted-screen regression verifies no reload, no allocation rerun and no duplicate HOLD on a double click. Contract, dependencies and migrations are unchanged. No product live/record call was made; the actual two-browser/server scenario and the full backend suite were not re-run for this fix.

PR #40 review revisions address R1–R8 (D-053/D-054). Replay inputs now exclude per-run identity and timestamps while retaining server decision inputs; a real loopback RECORD → closed-server REPLAY test confirms all three fixture keys match across different request/reservation IDs and shifted dates. Other-category search is read-only and cannot bypass agent HOLD category validation. Typed protocol errors keep model fallback separate from database errors; PostgreSQL tests prove an infrastructure failure rolls back earlier holds. Domain assembly and Korean item-labelled rejections are covered, and AI_UNAVAILABLE exposes manual stock selection without claiming AI success.

The main session ran in `backend/`: `./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*AllocationIntegrationTest' --tests '*AllocationServiceTest' --tests '*AllocationOutcomeTest' --tests 'com.afterbooth.ArchitectureTest' --tests '*ReservationIntegrationTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*AiGatewayModesTest' --tests '*MetaAiGatewayTest' --tests '*FixtureStoreTest' --tests '*VehicleFitPolicyTest' --no-daemon` — 110 tests passed with zero failures/errors/skips, including 15 allocation PostgreSQL tests. In `frontend/`, `npm run build && npm test && npm run lint && npm run format:check` passed, including 61 tests. Fresh independent and scoped adversarial reviews found no new blocking findings. Only local commits are authorized for this revision; the remote PR still refers to the preceding head. No external AI call was made. Actual two-browser execution, the integrated demo and the full local backend suite were not run for this revision; the earlier PR head's GitHub backend CI did run its full suite successfully, which does not certify these new changes.

#10 implements photo recount, human mismatch resolution, reuse statistics and the Korean handover screen (D-055). It starts from merged #9 on `origin/main` `c679021`; the existing #9 worktree was not modified. Spark counts the reserved category, shared SAM batching checks every box, and completed zero/incomplete results remain resolvable. AI preparation holds no database transaction; final recount, existing #7 completion/restoration and the idempotent response commit together after locking and revalidation. Existing contracted event reads now work on the real server. No contract, migration or dependency changed.

The main session inspected the implementation diff and ran these final gates on macOS:

```bash
cd backend
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*HandoverIntegrationTest' --tests '*HandoverPhotoCounterTest' --tests 'com.afterbooth.ArchitectureTest' --tests '*ReservationIntegrationTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*ReservationRulesTest' --tests '*ReservationSchedulerTest' --tests '*IntakeIntegrationTest' --tests '*IntakePipelineTest' --tests '*IntakeProcessorTest' --tests '*CoreDomainTest' --tests '*AllocationIntegrationTest' --tests '*PickupRequestIntegrationTest' --no-daemon
# 146 passed, zero failures/errors/skips, including 14 PostgreSQL handover tests and 20 counter tests.
cd ../frontend
npm run build && npm test && npm run lint && npm run format:check
# Build, lint and formatting passed; 72 tests passed, including 11 handover screen tests.
```

The first backend gate exposed incomplete prepared-action wiring: a missing supplier failed compilation, then the unchanged controller call caused two cancellation/scheduler restoration assertions to fail. Both were corrected; the final gate also proves twelve simultaneous HTTP preparations do not exhaust the connection pool, and an in-flight duplicate conflicts before replaying the completed first result. A loopback real-gateway RECORD → closed-server REPLAY test covers serialization and fixture lookup without external model access.

Separately, actual headless Chromium at 390×844 ran against Vite without MSW, the real Spring HTTP server and PostgreSQL Testcontainers. A temporary test-only launcher supplied a local AiGateway double to the real counter. `node /private/tmp/afterbooth-10-browser/browser.cjs` passed matching completion, same-file retake, partial resolution, Spark zero, completed SAM without masks, 503 recovery, empty/nonempty event filters, and real request → HOLD → confirm → zero-count photo → cancel. HTTP reads confirmed handed-over quantities, exact remaining inventory and total reuse ten; no browser page errors occurred. The temporary launcher, images and screenshot remain outside the repository and are not required product artifacts. This is real browser/server verification with synthetic AI, not live model verification or the full demo.

Fresh read-only Codex CLI review found R1: rotating the action key after any HTTP error could lose a committed result behind a 502/504. R1 is fixed by preserving keys for ambiguous infrastructure errors, with mounted regressions for 502, 504 and 500. A second fresh independent re-review, including adversarial state/concurrency/AI-evidence inspection, approved with no blocking findings. The designated companion plugin is unavailable; the installed Codex CLI provided the substitute, rather than claiming Claude or companion execution. The main session also inspected the final diff and test XML. Secret scanning and whitespace checks passed before local commits. No GitHub write, push, PR or external product AI call was made. Not verified: full backend suite, real model accuracy/latency, the complete intake-to-allocation demo and the separate two-browser #12 scenario.

Dependency order: #1 → (#3 and #4 in parallel) → #5·#6 (DY) / #7 → #8 → #9 → #10 (WJ)

## Phase 2: Integration + demo hardening (target 11/2–11/3)

- [x] #11 Integrate, run everything with one `docker compose up` (DY)
- [ ] #12 Playwright E2E demo scenario, two contexts requesting at once (WJ)
- [ ] #13 Record → replay fallback, latency measurements, loading UX (DY)
- [ ] #14 Self-score against the judging criteria + Muse Code review → fix the weakest area (DY, WJ)

## Phase 3: Submission (target 11/4 morning)

- [ ] #15 English README (DY)
- [ ] #16 Demo script + 2–3 minute video (WJ, key B)
- [ ] #17 Submission checklist (DY, WJ)
- [ ] **G3 STOP** — report how to run, demo results, self-score, remaining risks
