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

- [x] #3 · [x] #4 · [x] #5 · [x] #6 · [x] #7 · [x] #8 · [x] #9 · [x] #10

#9's historical two-browser acceptance criterion is reconciled with #12/PR #46's strengthened HTTP-overlap gate and visible-alternative assertions. The PR #46 author reported clean-DB replay success for main5/main6 at `d89f4e5`; PR #60 also reports a clean-clone full-demo run. These are historical records and author reports, not executions performed for this correction. Authenticated English demo recovery is tracked separately in #17. The detailed evidence levels in the historical concurrency table below remain unchanged.

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
- [x] #12 Playwright E2E demo scenario, two contexts requesting at once (WJ)
- [x] #13 Record → replay fallback, latency measurements, loading UX (DY)
- [x] #14 Self-score against the judging criteria + Muse Code review → fix the weakest area (DY, WJ)

#13 recorded the demo scenario with key A (12 live calls, about $0.03, D-057/D-058) and verified it three ways on the real Docker stack through the built-in browser: replay from an empty database (intake, voice request, allocation, two-tab race within 5 ms, confirm, handover, statistics); live mode with an unroutable Meta base URL (the first Spark call waited the 8 s window, every step then completed from fixtures, including the stock-0 loser branch); and the record runs themselves. Codex wrote the first implementation but its sandbox could not run Gradle or vitest; four of its frontend tests failed locally and were rewritten, and the 60 s fallback cooldown was added after the network-loss run exposed per-turn waits. The recording confirmed the winner through the API; a "예약 확정하기" button for HELD reservations was then added to the allocation proposal in the same PR.

#12 replays the full demo on the real Docker stack through headless Chromium (D-059): photo intake → voice request → sedan NO_MATCH proposal → pre-race API check (exactly one available rack; the photo added 4 bins, 0 racks) → two independent browser contexts racing the last rack with Promise.all clicks → winner confirms via the "진열대 예약 확정하기" UI button → photo handover → stats agreement.

Historical concurrency evidence (corrected for #49):

- The original gate compared HTTP start times with UI-completion times. Delayed rendering or UI observation could make sequential HTTP requests pass the overlap assertion. [PR #46 R6](https://github.com/afterbooth/after-booth/pull/46#pullrequestreview-5727945762) reproduced that false positive with a loopback browser probe.
- The strengthened gate in `d89f4e5` compares HTTP start and response-body completion intervals on the same monotonic clock, using `request` and `requestfinished`/`requestfailed` events: `max(startA, startB) < min(endA, endB)`. UI-completion times remain latency information only.

| Run or group | Mode | Overlap check | Source | Confirmation level |
|---|---|---|---|---|
| main1–main4 | replay | Original HTTP-start/UI-completion gate, before R6 was fixed | Pre-fix TASKS record at `b3e5d73`; R6 | Historical main-session verification record, not verification by this correction session |
| main5 / main6 | replay | Strengthened HTTP intervals; reported overlap true, A/B 37.5/33.9 ms and 34.9/32.9 ms respectively | [Author reply](https://github.com/afterbooth/after-booth/pull/46#issuecomment-6557913650), after `d89f4e5` | Author-reported clean-DB runs; no original execution logs inspected here |
| Six implementer runs in the cumulative record | replay | Per-run gate and revision are not established by the cited aggregate record | TASKS at `d89f4e5`, preserved in merge `fa27bbd` | Historical implementer attribution only; individual run identities and gate coverage remain unclear |
| live1 | live | Original gate; “overlap true/true” is the former assertion result | Historical TASKS record at `b3e5d73`; BUDGET #12 live entry | Historical human-approved run record; not live success evidence for the strengthened HTTP gate |

The cumulative record at `d89f4e5` (merged as `fa27bbd`) reports **twelve green replay scenario runs from clean DBs**, attributed to six historical main-session runs and six implementer runs, including review-fix runs. This preserves the recorded total, not twelve independently reverified runs or twelve strengthened-gate passes. main5/main6 are the two named post-fix runs within the six main-session runs, not additional runs to add to twelve. The author reply reports “11 passed” for each: one full-demo scenario plus ten browser-free helper tests, not eleven full-demo executions. The original logs were not inspected in this correction session, so the remaining implementer runs are not assigned a gate by inference.

The historical aggregate also reports `simultaneous-reject` for every replay run, click/request gaps of 5–9 ms and varying winners (#2/#3); these are attributed observations, not proof of HTTP overlap for all twelve runs. The author reply specifically reports `simultaneous-reject` and winner #2 for main5/main6. The recorded `stock-0-after-commit` branch was not executed in the #12 runs; its detector matches the recorded fixture (search shows availableQty 0, the model makes no holdQuantity call) and fails closed on any other loser shape.

The historical human-approved live1 record reports one successful full scenario from a clean DB: 19 AI calls, all `outcome=live` with no fallback, about $0.04 (BUDGET #12), branch `simultaneous-reject`, winner #2, click gap 0.7 ms. Its “overlap true/true” predates the strengthened gate and does not establish live HTTP-interval overlap. Preserved live latencies: photo → auto result 6.4 s (inside the 15 s demo bar), voice 4.3 s, first allocation 9.4 s, race 6.4/10.4 s, handover 3.4 s, whole scenario 38.2 s (n=1). This correction cross-checked the ledger and documentation; it did not reverify live execution or its original logs.

[PR #46 re-review R9](https://github.com/afterbooth/after-booth/pull/46#pullrequestreview-5728050243) independently checked the revised watcher with two-context Chrome probes against a loopback server and ran ten browser-free tests; discovery listed eleven tests. Those checks were not full product E2E runs. That review did not rerun the full product replay/live scenario or either race branch on the actual stack because Docker's Linux engine was unavailable. The author reply also explicitly did not rerun live mode or execute `stock-0-after-commit`. No demo, live/record call or new runtime verification was performed for this documentation correction.

Latency per step (mode=replay, env=macOS Docker app on localhost:18080 + headless-shell Chromium, n=1 per run, wall clock in ms):

| step | main1 | main2 |
|---|---|---|
| guard: server health and replay mode | 17 | 17 |
| photo intake: upload bins photo → auto result | 1314 | 1317 |
| photo intake: confirm registration | 92 | 66 |
| request #1: voice WAV → interpretation | 32 | 33 |
| request #1: save pickup request | 45 | 51 |
| request #1: allocation proposal (sedan cannot fit) | 112 | 112 |
| pre-race API check: stock of one rack | 7 | 6 |
| race A: same text → saved request | 224 | 222 |
| race B: same text → saved request | 257 | 239 |
| race: both allocations dispatched together | 105 | 103 |
| race allocation A: click → outcome | 95 | 96 |
| race allocation B: click → outcome | 104 | 103 |
| winner: confirm held reservation in UI | 51 | 43 |
| handover: rack photo → completed | 73 | 122 |
| stats: UI and API agreement | 4 | 5 |
| whole scenario (test body) | ~2700 | ~2700 |

Photo → auto result is 1.3 s in replay, inside the 15 s demo bar; replay latency is not model latency (live expectation ≈ 8 s per `aiLatency.ts`, not measured here — the live 15 s bar belongs to #16/G3).

### #14 self-score (main `e45f08e`, 2026-11-02)

Sources: a fresh Claude `reviewer` in scoring mode (Opus, medium effort) and a read-only Codex CLI adversarial pass over the core paths (`codex exec --sandbox read-only`, gpt-6.1-sol; the branch had no diff, so the companion's `--base origin/main` mode had nothing to review, and the first run ended on a model-capacity error before answering). The main session re-checked every finding used below against the code. Neither tool ran builds, tests or the demo; run results are quoted from the #12/#13 records above.

**Impact — 5/10 (weight 30).**
- Strong: the problem is concrete (teardown deadline, racks, bins, tables; `README.md:3`), and the allocation board counts down to the deadline (`AllocationBoard.tsx:52`).
- Weak: reuse was reported only as an item count (`ReuseStats.totalReused`), and the demo ends at "총 1개 재사용". Neither the UI nor the README gives a number for the scale of the waste or for what was saved. The README still says the write-up comes in Phase 3.
- Weak: SPEC demo step 1 promised "display rack 4, storage bin 12", but the recorded photo registers "수납함 4개" (4 storage bins).

**Use of Meta AI Tooling — 6/10 (weight 25).**
- Strong: every model leads to an action.
  - Spark estimate → SAM 3.1 box prompts → inventory draft (`IntakePipeline.java:120-140`).
  - Transcribe → Spark structuring → a saved pickup request.
  - Spark recount plus a SAM gate decides HANDED_OVER (D-032/D-034).
- Strong: function calling has substance (`AllocationService`).
  - Three strictly validated tools, all executed by the server, with typed rejections returned to the model.
  - A bounded loop, and the agent can create at most a HOLD.
  - Codex found no non-Meta model in the runtime, no call that bypasses `AiGateway`, and no agent-created CONFIRMED.
- Weak: nothing in the UI named a Meta model. The intake stage text was generic, and the tool trace was a collapsed `<details>` with no tool names (`AllocationProposal.tsx:64-77`).
- Weak: SAM masks are not drawn; the overlay draws boxes.
- Weak: the recorded agent explanations leak enum names and IDs ("SEDAN에 적재 불가", "(DISPLAY_RACK) … itemId 1", "확보(HELD)했습니다" in `fixtures/replay/spark/550b4b0f…`, `93c1d7db…`, `74038e7a…`).
- Muse Code: four `[Muse Code]` decisions (D-024, D-052, D-061, D-062). Only D-052 has a screenshot in `docs/evidence/`.

**Demo — 6/10 (weight 25).**
- Strong: the full scenario runs against the real stack with two browser contexts racing for the last rack (`e2e/tests/demo.spec.ts`).
  - Twelve green replay runs and one live run, 38.2 s in total with photo → result in 6.4 s (#12 record above).
  - The live → fixture fallback is covered by D-057 and the #13 network-loss run.
- Weak: the home page rendered a full-height `min-h-dvh` block, so the capture control was below the fold on a phone (`App.tsx:22`). It also showed "서버 UP · AI replay", and a stale bootstrap comment remained (`App.tsx:9`).
- Weak: the race loser's headline was a flat failure line, so SPEC step 4's "the agent immediately offers the other an alternative" was not visible at a glance.
- Weak: replay needs byte-identical inputs in a fixed order (`fixtures/README.md` §Demo scenario). A judge who runs `docker compose up` with their own photo gets AI_UNAVAILABLE or a failed draft, and the README does not list the sample files (Codex R2).
- Missing: the video (#16).

**Technical Depth — 8/10 (weight 20).**
- Strong: conditional stock updates (`ItemRepository.java:10-18`) and all seven required Testcontainers tests (`ReservationIntegrationTest`): 100 threads for the last 2, restoration, idempotency, forbidden transitions, the per-request cap, and confirm racing expiry in both orders.
- Strong: record/replay keyed by a SHA-256 of the canonical, redacted request, with single-flight recording and model DTOs free of per-run IDs (D-053). `ArchitectureTest` enforces layering.
- Weak: allocation holds the request-row and item locks across later model turns, including the explanation turn after the HOLD succeeds (D-051; Codex R5).
- Weak: a duplicate Spark box around one object can pass the SAM gate (Codex R3, plausible, not reproduced).
- Weak: intake confirmation and manual holds rotate the idempotency key after any HTTP error, including an ambiguous 5xx (Codex R1; the compose stack has no proxy that produces 502/504).

**Weighted total: 61/100.** The weakest criterion is Impact.

Top 3 fixes, all done on `feat/14-self-score-fixes` (Closes #14):

1. **Impact as a number.** Reuse statistics now report `reusedVolumeLiters`, the summed registered volume of handed-over equipment (contract commit `5c15e96`). The handover screen shows it as an estimate, in liters below 1,000 L and in m³ rounded down above. SPEC demo step 1 now matches the recorded photo.
2. **Meta models visible from the first screen.**
   - Photo intake is the top of the home page, and the health line moved to the bottom.
   - Every AI step names its model: Muse Spark, SAM 3.1, Muse Voice Transcribe.
   - The agent's tool trace is open by default and shows each tool name with 통과/거절.
3. **The race loser gets the alternative as a headline.** "대신 {label} {n}개 어떠세요?" is built from the first server alternative, with no AI or fixture change.

Not done, with reasons:
- Rewriting the allocation prompt to remove enum names and IDs would change every allocation fixture key. It would need a human-approved re-record and fresh E2E runs of both race branches two days before submission, and it would invalidate the inputs #16 records against.
- Codex R1, R3 and R5 are correctness hardening outside the demo path, below the three fixes above in score impact. They stay listed here.
- Codex R6 (the raced rack is seeded, not the photographed bins) needs a new recorded scenario. #16 should narrate it honestly instead.
- Muse Code reviewed the photo intake core path (D-066, [evidence](evidence/muse-intake-review.png)). M1 and M5 are fixed on this branch; the other ten findings have recorded dispositions.
- README figures and a "try it with these sample files" section belong to #15. The video opening and narration belong to #16. Missing Muse Code screenshots belong to #17. Each of those issues has an English comment from #14.

### #51: abandoned idempotency recovery

- [x] #51 Recover abandoned IN_PROGRESS keys without duplicating active requests (D-067).

Implementation uses a separate PostgreSQL session-lock pool, final idempotency-row locking and original-session ownership checks. No schema, migration, dependency, API, frontend or AI fixture change. Scope includes single and multiple instances sharing the same database and protocol; post-DONE asynchronous intake worker restart remains excluded by user decision.

The first main-session gate passed formatting and compilation, then ran 114 selected tests: 113 passed and one failed. The unchanged twelve-preparation HTTP regression timed out acquiring its twelfth dedicated connection after 1,001 ms with eleven active connections. A fresh read-only Codex independent and adversarial review confirmed this as blocking R1; no other correctness blocker was substantiated. The user explicitly approved increasing only the connection acquisition timeout to five seconds. The final main-session gate below passed all 114 tests, with zero failures, errors or skips. The twelve-preparation assertion is unchanged and uses no test-only warming.

Run from `backend/` with `JAVA_HOME` set to the installed Java 21 runtime; automated tests force `AI_MODE=replay`:

```bash
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*IdempotencyRecoveryIntegrationTest' --tests '*IdempotencyLockPoolIntegrationTest' --tests '*PickupRequestIntegrationTest' --tests '*ReservationIntegrationTest' --tests '*ReservationRulesTest' --tests '*ReservationSchedulerTest' --tests '*HandoverIntegrationTest' --tests '*AllocationIntegrationTest' --tests '*IntakeIntegrationTest' --tests '*DefaultVehicleHoldIntegrationTest' --tests '*HoldItemValidationTest' --tests '*HandoverResolveValidationTest' --tests 'com.afterbooth.ArchitectureTest' --no-daemon --console=plain
# BUILD SUCCESSFUL; 114 tests passed, zero failures/errors/skips.
```

The main session inspected the actual implementation, new untracked files and test XML. Seven PostgreSQL recovery tests cover same-fingerprint orphan replay, fingerprint rejection, stored business errors, concurrent recovery, separate service instances, stale-owner late success/business failure/runtime cleanup, and final-row-lock protection after lock-session termination. Three pool tests cover invalid settings, bounded acquisition with the main pool still available, release and dead-session eviction. Domain regressions include recovered HOLD stock/reservation count and recovered photo-handover recount/log uniqueness, cancellation/expiry during AI, atomic rollback/error replay and strict input validation from #47/#53.

Independent re-review approved with zero blocking findings and zero suggestions; R1 is resolved by the user-approved five-second timeout and the unchanged passing twelve-preparation regression. The designated Claude reviewer/companion tooling is unavailable; a fresh read-only Codex subagent used the repository reviewer role and review/re-review format, including adversarial concurrency and connection-lifecycle inspection. No Claude execution or remote CI result is claimed. A local PR-body draft follows the repository template and uses `Issue: Refs #51`. Staged secret scanning, whitespace checks and manual diff inspection passed; the existing pre-commit and commit-msg hooks remain enabled.

Not verified: actual application process kill/restart, mixed-version rolling deployment, full backend suite, frontend/browser/demo flows, external Meta model accuracy/latency and post-DONE async intake worker restart. Tests used synthetic requests and test-owned PostgreSQL containers/sessions only. No secret files, real database rows, product live/record calls, GitHub writes, push or PR were used.

### #61: lock pool prefill

- [x] #61 A burst on the empty idempotency lock pool can strand a request until the 5 s timeout (D-068).

CI run 41435677051 failed the twelve-preparation regression with `idempotency-locks ... total=11, active=11, idle=0, waiting=0` after 5 s. The HikariCP 7.0.2 bytecode shows why: each creator task adds at most one connection, a task is submitted only when the waiting count exceeds the queued tasks, and with minimum idle 0 nothing else grows the pool. The user chose to prefill the pool. When `minimum-idle` is not set, it now defaults to the configured `maximum-pool-size` (16), so overriding only the maximum still starts (`IdempotencyLockPoolSettingsTest`). Nine runs before the change on this 16-core PC did not reproduce the failure (six normal, three under full CPU load, where the pool took about 2 s to reach 12 connections). The race is therefore inferred from the CI report and the HikariCP code, not reproduced locally.

Run from `backend/` on Windows through PowerShell:

```bash
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests "com.afterbooth.HandoverIntegrationTest" --tests "com.afterbooth.IdempotencyRecoveryIntegrationTest" --tests "com.afterbooth.global.idempotency.IdempotencyLockPoolIntegrationTest" --tests "com.afterbooth.ArchitectureTest" --no-daemon
# BUILD SUCCESSFUL; 32 tests (15 handover, 7 recovery, 3 lock pool, 7 architecture), zero failures/errors/skips.
```

A debug-logged rerun of the twelve-preparation test passed. Its log shows 16 `idempotency-locks` connections added, ending at `total=16/16, idle=16/16`. Not verified: the slow CI runner itself, until this PR's CI runs; the full backend suite locally.

## Phase 3: Submission (target 11/4 morning)

- [x] #15 English README (DY) — PR #60, merge `02ab1d9`; clean-clone commands and Mermaid rendering are historical author-reported checks. Post-authentication README updates and revalidation belong to #17, separately from that historical completion.
- [ ] #16 Demo script + 2–3 minute video (WJ, key B)
- [ ] #17 Submission checklist (DY, WJ)
- [ ] **G3 STOP** — report how to run, demo results, self-score, remaining risks

## Member authentication design (#63)

- [x] #63 local specification and contract: incorporated the user's answers and updated SPEC, scope exclusions, OpenAPI, ERD, DECISIONS and shared implementation boundaries. Contract validation, client generation, build, 118 tests, lint and formatting passed; independent re-review found no blockers. This does not mean authentication is implemented or the GitHub issue is closed. See AUTH_DESIGN for commands, warnings and unverified scope.
- Confirmed: immediate personal-data deletion / 30 days of transaction history from withdrawal / 30 days of re-registration blocking. Email verification is for signup; delivery uses the Resend API.
- Introduce the authentication runtime after submission. The user subsequently canceled the earlier commits while preserving changes and authorized one combined local commit, including the omitted authenticated password-change specification (D-075), after a sensitive-data check. This task does not implement features, write/apply migrations, send mail, push, create a PR or close the issue. See `docs/AUTH_DESIGN.md` for final identifiers and verification results.

- [x] #63 password-change correction: changePassword, its policy/schema references and D-075 are defined. Final contract validation and client generation passed (35 operations, 298 local references); build, 118 tests, lint and formatting passed. Fresh independent review found no blockers. No runtime implementation is included.

## Signup implementation (#65, D-076/D-077)

- [x] #65 shared schema/member/Argon2id, signup/challenges and pending cleanup.
- [x] #65 single common chain, anonymous CSRF, exact-Origin and PostgreSQL request limits.
- [x] #65 local Fake/deployment Resend, default disabled; actual sending excluded.
- [x] #65 protected loopback HTTPS signup → confirmation → ACTIVE and password matching; regression gates and independent review. Local commits use repository hooks after the final staged-content scan.
- [x] #66 reused #65 for login/JWT/refresh/sessions and completed the final signup → verification → login integration; completed under #66, outside #65 scope.

Explicit authorization supersedes the historical post-submission schedule without changing prior verification evidence. Existing-domain authorization, frontend and deployment remain later work. Mark completion only with current evidence.

Current #65 execution (2026-11-03): the main session ran all commands below; no historical #63 or canceled implementation result is used.

- `spotlessApply compileJava compileTestJava spotlessCheck`: passed, including a final compile after a lexical variable rename to avoid a scanner false positive.
- Broad selected backend gate: 192 tests; 189 passed, three failed. One old assertion expected one migration; two issuance fixtures removed only the current alias after the rotation fix. Fixed the exact migration-version expectation and subject-specific interval fixture cleanup without weakening runtime checks.
- Corrected related gate: 60 tests, zero failures/errors/skips. Added actual legacy-row schema upgrade, asynchronous sending outside locks, and purpose-binding tests. Combining unchanged passing regression cases with the corrected run gives 195 distinct passing tests; this is not a second complete 192-test run or the full backend suite.
- Frontend `npm ci --no-audit --no-fund`, `npm run build`, `npm test -- --maxWorkers=1`, `npm run lint`, `npm run format:check`: passed; 118 tests. Lint retains two pre-existing useHandover warnings.
- Parsed OpenAPI comparison excluding descriptions/summaries: structure unchanged, 35 unique operation IDs and 298 resolved local references. Orval generation passed. The contract explanation is its own local commit.
- Independent fresh read-only review and Codex CLI adversarial review: per-API IP scopes and mixed-current rotation findings corrected, re-review approved without blocking findings. Claude reviewer could not run because its OAuth session expired; the required companion script is absent. No trust or credential setting was modified; independent read-only review replaced the unavailable reviewer.
- Changed-file pattern scan, manual diff/privacy review, `git diff --check`, and generated Fake-code-output scan passed. Repository hooks repeat the staged scan and commit-message validation.
- Not verified: full backend suite, frontend/browser signup UI, signup → verification → login (#66), actual Resend delivery/receipt, deployment/provider settings, real DB/user data and live Meta calls.
- Read-only checks of current #65–#70 found no additional issue-body changes necessary. No GitHub issue/comment/status/checkbox or other remote write was made.

Exact backend selections used (from backend/ with Java 21):

```bash
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*Signup*Test' --tests 'com.afterbooth.global.security.*Test' --tests 'com.afterbooth.member.mail.*Test' --tests 'com.afterbooth.ArchitectureTest' --tests 'com.afterbooth.CoreSchemaIntegrationTest' --tests 'com.afterbooth.ReservationIntegrationTest' --tests 'com.afterbooth.DefaultVehicleHoldIntegrationTest' --tests 'com.afterbooth.AllocationIntegrationTest' --tests 'com.afterbooth.HandoverIntegrationTest' --tests '*IntakeIntegrationTest' --tests '*PickupRequestIntegrationTest' --tests 'com.afterbooth.IdempotencyRecoveryIntegrationTest' --tests '*ControllerTest' --tests '*GlobalExceptionHandlerTest' --tests '*SpaShellTest' --tests '*HoldItemValidationTest' --tests '*HandoverResolveValidationTest' --no-daemon
# Broad run: 192 cases, three fixture failures corrected below.
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests '*Signup*Test' --tests 'com.afterbooth.AuthSchemaUpgradeTest' --tests 'com.afterbooth.global.security.*Test' --tests 'com.afterbooth.member.mail.*Test' --tests 'com.afterbooth.ArchitectureTest' --tests 'com.afterbooth.CoreSchemaIntegrationTest' --no-daemon
# Corrected related run: 60 passed, zero failures/errors/skips.
./gradlew spotlessApply compileJava compileTestJava spotlessCheck --no-daemon
# Final lexical-only scanner correction: passed; no behavior change.
```

## PR #71 review correction — R1 encoded signup paths

- [x] Confirmed the remote review targets the clean local head `583fc51`; reused the existing signup worktree without changing the shared checkout.
- [x] Reproduced R1 with three new actual loopback HTTPS/PostgreSQL regressions before production changes: missing security checks, successful-response no-store and canonical/encoded IP-quota sharing all failed. An initial mistyped method filter selected no tests; it is not reproduction evidence.
- [x] Reused parsed-path POST matcher objects for signup authorization, CSRF, exact-Origin enforcement and IP-scope selection; authentication no-store uses parsed namespace matchers too (D-078). No firewall relaxation, dependency, schema, API contract or policy-limit changes.
- [x] Protected encoded paths with valid synthetic business bodies: missing/wrong Origin, cookie and CSRF return 403 without member/organization/challenge mutations, activation, attempt consumption or password-hash changes. Valid encoded signup/verification still works. Canonical and encoded aliases share each API's twenty/IP/hour quota and return Retry-After on exhaustion.
- [x] Main-session final gate below passed: 38 tests across six suites, zero failures/errors/skips, including seven real HTTPS security cases, PostgreSQL rate/key-rotation checks, signup/final-password checks, actual local Fake HTTPS flow and ArchitectureTest.

```bash
# backend/, Java 21
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests 'com.afterbooth.global.security.AuthSecurityHttpsTest' --tests 'com.afterbooth.global.security.AuthRateLimiterIntegrationTest' --tests 'com.afterbooth.global.security.AuthSecurityPropertiesTest' --tests 'com.afterbooth.LocalFakeSignupHttpsTest' --tests 'com.afterbooth.SignupIntegrationTest' --tests 'com.afterbooth.ArchitectureTest' --no-daemon
```

Fresh independent read-only review and the installed Codex CLI adversarial re-review both approved R1 as resolved with no blockers. Final changed-file/staged-content scanning and repository hooks apply before local commit. Tests use generated synthetic material and disposable PostgreSQL; available XML/Gradle outputs contain zero generated Fake-code lines. No real secrets, user data or provider calls were accessed. Not verified in this correction: full backend/frontend suite, browser UI, additional context-path deployment, real mail, deployment or updated remote CI. No push, GitHub comment/review/status modification or merge is included.

## Issue #66 login/session implementation

- [x] Login, RS256 JWT and per-request database authentication, extending #65.
- [x] Shared member locking, refresh rotation/reuse, current/all-session logout and expiry cleanup.
- [x] Authenticated CSRF, exact-Origin, durable login limits and parsed-path regression protection.
- [x] Current PostgreSQL/concurrency/real loopback HTTPS and affected regression gates.
- [x] Independent adversarial review and sensitive-data scan; local commit uses repository hooks. No remote writes.

Implementation base: `origin/main` `4e10f10f51a09d7808ca58c84156072df52b4a70`; branch `feat/66-login-refresh-logout`, worktree `/private/tmp/afterbooth-66-login-refresh-logout`. Fetched origin/main before branching; the shared main checkout and other worktrees were preserved. Historical #63/#65 results above are not evidence for this implementation.

### Current acceptance evidence (2026-11-03)

| #66 acceptance group | Current evidence |
|---|---|
| Shared foundations | Extended the existing AuthSecurityConfiguration/Properties, AuthRateLimiter/HmacKeyRing, CsrfSessionService/Controller/HashedCsrfRepository, MemberRepository, single Argon2id encoder and cleanup scheduler. Reused signup credentials/services and existing Fake/MailGateway for final flow; no second chain/store/encoder/mail implementation |
| Login credentials and member state | SessionIntegrationTest checks ACTIVE, absent/wrong/WITHDRAWN generic errors, correct-password-only EMAIL_NOT_VERIFIED, normalized email and original whitespace/Unicode password |
| Login limits | LoginRateLimitIntegrationTest checks rolling 15-minute failures, fifth-failure restriction/Retry-After, expired windows, old/current HMAC aliases and rollback independence. LoginSmallPoolIntegrationTest forces three occupied connections/eight waiting logins, then five 401 and three 429, no 503 or sessions; correct credentials remain restricted |
| RS256/JWT/configuration | JwtServiceTest signs synthetic adversarial tokens for issuer/audience, algorithm/kid/key, missing/wrong-type claims, numeric/UUID boundaries, duplicate/trailing JSON, tampering, rotation overlap/retirement and malformed/weak/mismatched key configuration. Exact 600-second expiry; no external key fetching or fallback |
| Every-request DB validation | Actual LocalFakeSignupHttpsTest changes synthetic member auth_version/state and family ownership, then observes immediate 401. Renaming the disposable test session table temporarily produces HTTP 503 AUTH_UNAVAILABLE; restoration runs in finally. No authentication cache |
| Refresh lifetime/rotation/reuse | SessionIntegrationTest checks SHA-256-only storage, stable family ID, exact seven-day expiry, atomic rotation, retained used hashes, concurrent winner/loser and committed reuse revocation. Actual HTTPS reuse returns 401 and invalidates the winner JWT and refresh |
| Current/all logout and idempotency | Service and HTTPS tests distinguish family-only revocation from all-family/version invalidation; current logout tolerates missing/repeated cookies and incidental expired Bearer. Logout-all retry with invalid JWT returns 401 after valid CSRF. Authentication flow leaves idempotency_record empty |
| Common member locking and stale credentials | Common MemberLockService locks ascending IDs before family/domain locks. Tests queue login/refresh behind real row locks, mutate password/version, then prove stale credentials cannot issue new sessions. Refresh/logout concurrency completes without a surviving valid family |
| Authenticated CSRF | Login/refresh replace and link CSRF state; GET reissues while retaining a valid member link under member lock. Logout-all removes member-linked rows while preserving unrelated anonymous state. Explicit linked expiry cleanup preserves member/anonymous data |
| HTTP security/cookies | Actual loopback HTTPS exercises signup/login/refresh/logout/all, missing/wrong/expired CSRF, absent/non-exact Origin, encoded path aliases, canonical/encoded twenty/IP quota and Retry-After, no-store, separate secure cookie headers/deletions and refresh-cookie-only domain rejection. Existing signup HTTPS regressions remain passing |
| Key configuration and rotation | Runtime RSA/TLS/HMAC generation only; malformed/missing key configuration fails closed. Public-key overlap and old/current HMAC consistency tested without real key files or secrets |
| Expiry cleanup | Existing scheduler removes expired families/tokens and delegates existing foundation cleanup, preserving used hashes until family expiry. Expired records fail authentication before physical deletion |
| Durable/shared rate storage | Same PostgreSQL auth_rate_limit table and cleanup; independent instances, rolling failures, previous keys, transaction rejection and concurrency tests. Existing hourly/issuance limit regression tests retained |
| Final local flow | Real server + disposable PostgreSQL + runtime loopback TLS/RSA/HMAC + explicitly enabled local Fake: signup → captured code confirmation → login → protected API → refresh → current/all logout. Synthetic code captured privately; no actual mailbox/delivery claim |

### Commands and outcomes

All commands below ran in backend/ with `JAVA_HOME=/Users/jeong/Library/Java/JavaVirtualMachines/corretto-21.0.12.1/Contents/Home`, AI_MODE=replay and Docker-backed disposable PostgreSQL. No PowerShell is required on this current macOS host; the earlier warning concerns Windows/Git Bash.

```bash
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests 'com.afterbooth.SessionIntegrationTest' --tests 'com.afterbooth.LoginRateLimitIntegrationTest' --tests 'com.afterbooth.global.security.*Test' --tests '*Signup*Test' --tests 'com.afterbooth.member.mail.*Test' --tests 'com.afterbooth.ArchitectureTest' --tests 'com.afterbooth.AuthSchemaUpgradeTest' --tests 'com.afterbooth.CoreSchemaIntegrationTest' --tests 'com.afterbooth.ReservationIntegrationTest' --tests 'com.afterbooth.DefaultVehicleHoldIntegrationTest' --tests 'com.afterbooth.AllocationIntegrationTest' --tests 'com.afterbooth.HandoverIntegrationTest' --tests '*IntakeIntegrationTest' --tests '*PickupRequestIntegrationTest' --tests 'com.afterbooth.IdempotencyRecoveryIntegrationTest' --tests '*ControllerTest' --tests '*GlobalExceptionHandlerTest' --tests '*SpaShellTest' --tests '*HoldItemValidationTest' --tests '*HandoverResolveValidationTest' --no-daemon
# 219 tests: 209 passed, ten intake fixture failures corrected below.
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests 'com.afterbooth.SessionIntegrationTest' --tests 'com.afterbooth.LoginRateLimitIntegrationTest' --tests 'com.afterbooth.LoginSmallPoolIntegrationTest' --tests 'com.afterbooth.global.security.*Test' --tests '*Signup*Test' --tests 'com.afterbooth.member.mail.*Test' --tests 'com.afterbooth.ArchitectureTest' --tests '*IntakeIntegrationTest' --no-daemon
# 88 tests passed; zero failures/errors/skips. Includes corrected intake and final login transaction protocol.
./gradlew spotlessApply compileJava compileTestJava spotlessCheck --no-daemon
# Final lexical-only scanner correction; no behavior change.
```

The earlier core run passed 36/37: an expired-family fixture used separate clock_timestamp calls and violated the exact seven-day CHECK. Fixed only synthetic SQL to use one statement_timestamp. The broad run's intake failures came from late dynamic-property binding during real-server startup and absent Bearer headers on its two raw HTTP upload tests. Explicit early synthetic JWT properties and genuine member/session/signature headers corrected them. Preserve all production security checks; no test authentication bypass was added. The final 88-case run verifies these corrections and the changed authentication/limit code; unaffected passing domain cases from the broad run were not repeated. This is not a full backend-suite pass.

### Review and sensitive-data checks

- Fresh independent read-only adversarial review found R1 queued login quota bypass, R2 incidental expired Bearer blocking refresh/logout, R3 missing signed adversarial JWT coverage and R4 nested transaction pool exhaustion. All were fixed; final re-review approved without blocking findings.
- The installed Codex CLI completed an additional read-only static review with no actionable findings; it ran during the initial corrections and is not final execution evidence. The repository-specific companion script was unavailable; no Claude reviewer run is claimed. No review was posted remotely.
- Main session inspected actual production/test diffs and XML counts, ran every gate reported here and verified no credential dumps. A scanner false positive on a long Java identifier was removed by a local-variable rename without scanner exceptions. Changed-file scan, private-key/JWT literal and credential-logging checks, and git diff --check passed. Generated Fake-code lines in the checked test XML/Gradle logs: zero. Repository pre-commit and commit-msg hooks apply again to the staged commit.
- No contract, dependency or migration changes. Consequently API generation/frontend checks were not repeated; frontend sources are unchanged.
- Not verified: full backend suite, browser/frontend token storage and single-flight, object ownership/roles/list filters or actor-bound domain idempotency (#67), password management (#68), withdrawal/retention orchestration (#69), real provider delivery/receipt/settings, deployment or live AI. The existing demo now needs configured JWT keys and an ACTIVE member before protected domain calls.
- #67 reuses the authenticated principal/member lock; #68 and #69 reuse member locking, version/session revocation and member-linked CSRF invalidation; #70 integrates the contracted cookies/token responses and client serialization. Those issues retain their actual scope/dependencies.
- GitHub issue-body correction needed: none. No push, PR, merge, issue/comment/status/checkbox change or deployment was performed.

## Issue #67 — Member accounts, owned events and domain authorization (2026-11-03)

Implementation base: fetched origin/main 0539cdb, containing #65/#66; isolated branch feat/67-member-authorization and worktree .claude/worktrees/67-member-authorization. Issue #68 is implemented in its own worktree. D-081 records the transaction, lock and authorization choices. Existing main and unrelated worktrees were preserved.

Implemented scope:

- Read-only current account and principal-owned event creation with strict fields, future deadline validation and actor-bound idempotency.
- Own organizations/events/intake drafts/pickup requests; reservation party reads, receiver confirmation/cancellation and giver handover writes. Active authenticated members can view tradable business fields, while raw conditions remain owner-only and unclaimed/withdrawn providers are hidden.
- Shared ACTIVE/session checks and sorted member-first locks before domain writes, explicit async intake actors, scheduler lock ordering and original-resource authorization before idempotent replay. Every represented response provider/receiver is recorded for subsequent withdrawal invalidation.
- AI allocation preparation runs outside transactions. Actual proposals are revalidated and written together with the idempotency response, preserving partial model-failure behavior and rollback after a final database failure. The model prompt/payload and contract are unchanged.

Verification notes so far:

- The first compile gate passed production compilation and caught a negative mock assertion with the wrong AiGateway.segment signature. Corrected the test; subsequent compileJava, compileTestJava, spotlessApply and spotlessCheck passed.
- The initial new authorization run passed seven architecture cases, then failed six catalog cases because a test helper changed the global SecurityContextHolder strategy after filters cached it. Removed that test-only global mutation and explicitly propagated contexts in test executors. No production authentication bypass was introduced.
- Windows native allocation/page-file failures also terminated Gradle test workers. Parent separated compilation from test execution and used a temporary direct JUnit Platform launcher with the exact Gradle worker runtime classpath, Mockito startup agent, replay mode, 256 MiB test heap and Serial GC. These are direct JUnit executions, not a successful Gradle test-task claim.
- Current new tests passed six catalog and eleven domain authorization cases, including actual signup/verification/login services followed by MockMvc HTTP, cross-actor/legacy replay protection, actual member-lock waits, withdrawn preparation rejection and a database trigger proving final HOLD/idempotency rollback together. Real email, browser or live AI is not implied.
- AllocationIntegrationTest passed all 15 cases. ReservationIntegrationTest passed 32/33; the remaining clock-controlled case now observes INVALID_TRANSITION as a thrown typed domain error after earlier member serialization rather than the older update-race return value. The test was adjusted to the same public error and unchanged final state/quantity/history; re-execution and remaining affected suites are recorded below.
- Independent read-only security/adversarial review found R1 (unrecognized mutation aliases falling back to organization-only permission) and R2 (insufficiently synchronized final-lock/rollback regressions). Both were fixed and static re-review approved with no blocking findings. Allocation response-provider metadata, lock ordering and wrong organization 404 were also corrected during initial review. No Claude reviewer or companion tool run is claimed.

Not verified: full backend suite, frontend/browser authentication integration, actual #69 withdrawal/retention orchestration, real delivery/receipt/provider settings, deployment and live Meta AI. Withdrawal tests simulate member state/version transitions under the shared protocol; new signup tests use captured synthetic mail. Remaining current checks and exact counts follow below.

### Executed commands and final correction boundary

Compilation/formatting in PowerShell, backend/:

```powershell
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xms32m -Xmx192m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC' --init-script C:\Users\ehddu\AppData\Local\Temp\afterbooth-agent-tests.gradle spotlessApply compileJava compileTestJava spotlessCheck --no-daemon --max-workers=1
# BUILD SUCCESSFUL after the last production/test corrections.
```

The first affected regression pass found that finalRowLockProtectsCommitWhenSessionDies blocked behind the newly introduced member lock before reaching the existing NOWAIT row check. A short isolated preflight probe fixes the actual duplicate-request regression; the original test expectation is preserved. Review also found a same-item trace correction: final revalidation of a later failed proposal must not erase an earlier accepted HOLD trace. A focused AllocationFinalizerTest checks one actual accepted quantity and separate OK/REJECTED outcomes. Independent static re-review approved both changes without blocking findings.

Direct JUnit execution uses an external AfterboothSelectedTests launcher and afterbooth-selected-test.ps1, each under C:\Users\ehddu\AppData\Local\Temp. The script uses the exact classpath from Gradle's test-worker argument file, sets AI_MODE=replay, starts the existing Mockito JAR as an agent and runs one class with heap 256 MiB/Serial GC. It invokes JUnit's LauncherFactory and SummaryGeneratingListener without mocks around the test runner or disabled product security. Commands take this form:

```powershell
& 'C:\Users\ehddu\AppData\Local\Temp\afterbooth-selected-test.ps1' -TestClass 'com.afterbooth.DomainAuthorizationIntegrationTest'
```

Synthetic server logs and per-class summaries remain outside the repository. The launcher reports test/container failures through its exit code. These runs include disposable PostgreSQL, actual Spring/MockMvc and existing raw-HTTP intake cases where present; mocks and loopback AI fixtures retain their explicit boundaries. No full backend/Gradle test-task success or browser/live-model success is inferred.

### Final affected verification

All 218 distinct tests in 20 affected classes passed, with zero failures/skips/aborts in their final runs:

| Test class | Passed |
|---|---:|
| CatalogAuthorizationIntegrationTest | 6 |
| DomainAuthorizationIntegrationTest | 35 |
| ReservationIntegrationTest | 33 |
| IdempotencyRecoveryIntegrationTest | 7 |
| AllocationFinalizerTest | 1 |
| AllocationIntegrationTest | 15 |
| DefaultVehicleHoldIntegrationTest | 2 |
| HandoverIntegrationTest | 15 |
| IntakeIntegrationTest | 10 |
| IntakeProcessorTest | 11 |
| PickupRequestIntegrationTest | 4 |
| PickupRequestServiceTest | 14 |
| ConditionServiceTest | 27 |
| ConditionInterpretationReplayTest | 1 |
| AllocationServiceTest | 7 |
| RequestControllerTest | 10 |
| OrganizationControllerTest | 2 |
| SystemControllerTest | 1 |
| IntakeControllerTest | 10 |
| ArchitectureTest | 7 |

The 35-case authorization suite includes 22 separately parameterized anonymous-route checks, forbidden giver cancellation/unrelated confirmation, foreign allocation, owned/foreign GIVER condition interpretation and ACTIVE/revoked RECEIVER checks. Catalog coverage also rejects foreign intake confirmation. The final reservation and idempotency recovery runs resolve the earlier failures; actual successful quantities, party roles, state transitions, stock restoration and response atomicity are verified rather than inferred from mocks.

The parent checked the actual implementation/test diff, per-class summaries and exit codes. Compilation/formatting and independent re-review passed. Changed-file secret scanning, whitespace and local PR-body validation are checked before commit. The 218-case total combines final affected runs on the unchanged relevant code; it is not an additional full-suite execution. No test pass is attributed to the aborted Gradle workers.

## PR #73 backend CI correction (2026-11-03)

CI run 41619198975 at c1d9f84 executed 555 tests: 37 failed and five were skipped. The earlier per-class checks did not expose shared-JVM fixture contamination. The downloaded backend report identifies three test-only causes: AuthenticatedMockMvcConfiguration reused an actor from another test database; HoldItemValidationTest/HandoverResolveValidationTest still used unowned seed organizations without receiver/giver actors; HoldTtlStartupTest omitted the new DomainAccess constructor dependency.

The correction changes no production source or security rules. Default test authentication reuses an actor only when its session/member identity exists in the current database. Revoked, version-mismatched and explicitly invalid credentials still reach the real JWT validation and return 401. Validation fixtures provision synthetic owners, apply receiver/giver roles and clear their actor after each case. The isolated TTL slice mocks DomainAccess.

Regression evidence: the new foreign-database actor test failed before the helper correction (OrganizationControllerTest: four passed, one failed). After correction, five focused authentication regression cases cover foreign actor, current actor restoration, revoked actor, version mismatch and explicit invalid Bearer. Compilation/Spotless passed. All 93 cases in eight affected classes then passed together in ONE JUnit JVM, zero skips/failures/aborts, using the previously documented Gradle-runtime-classpath launcher. The group includes OrganizationControllerTest, HoldTtlStartupTest, HoldItemValidationTest, HandoverResolveValidationTest, IntakeControllerTest, RequestControllerTest, ReservationIntegrationTest and HandoverIntegrationTest.

```powershell
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xms32m -Xmx192m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC' --init-script C:\Users\ehddu\AppData\Local\Temp\afterbooth-agent-tests.gradle spotlessApply compileJava compileTestJava processTestResources spotlessCheck --no-daemon --max-workers=1
& 'C:\Users\ehddu\AppData\Local\Temp\afterbooth-selected-test.ps1' -TestClass 'com.afterbooth.inventory.controller.OrganizationControllerTest' -AdditionalClasses @('com.afterbooth.HoldTtlStartupTest','com.afterbooth.HoldItemValidationTest','com.afterbooth.HandoverResolveValidationTest','com.afterbooth.intake.controller.IntakeControllerTest','com.afterbooth.request.controller.RequestControllerTest','com.afterbooth.ReservationIntegrationTest','com.afterbooth.HandoverIntegrationTest')
# Compilation/formatting passed; all 93 same-JVM cases passed.
```

The parent inspected the actual diff and failure/success summaries. Full Linux CI is re-run by the PR push; local checks do not claim that result in advance. Existing exclusions for live AI, real mail, UI and deployment remain unchanged.

## Issue #68 — Password change and reset (2026-11-03)

Implementation base: fetched origin/main 0539cdb; isolated branch feat/68-password-management and worktree .claude/worktrees/68-password-management. Issue #67 proceeds separately from the same base. D-082 records reuse of the existing authentication foundations.

- Implemented authenticated password change and email-code reset, including strict input fields, exact-Origin/CSRF/Bearer checks, operation-specific IP limits and durable current-password failure limits.
- Reused existing challenge issuance, Fake/Resend adapters, Argon2id encoder, member locking, session/version revocation and CSRF lifecycle. Credentials are never stored in domain idempotency responses; identity and organization ownership remain unchanged.
- PasswordIntegrationTest: 17 PostgreSQL tests passed after correcting synthetic expiry/rate-window fixtures. Includes partial-failure rollback, stale credentials, actual service races and observed member-lock waits. Withdrawal is a SQL protocol simulation, not an execution of #69.
- LocalFakePasswordHttpsTest: five actual loopback HTTPS/PostgreSQL tests passed with synthetic data and privately captured Fake codes. SessionIntegrationTest (nine), ArchitectureTest (seven), MailGatewayTest (six) and ResendMailGatewayTest (eight) also passed in the first affected gate. Local Fake and loopback Resend stubs do not prove real mailbox ownership or delivery.
- Fresh independent static review found R1 (unstable exact-lifetime expiry fixtures) and R2 (unobserved lock waits); both were corrected and independently re-reviewed with no blocking findings. No Claude reviewer or companion-plugin execution is claimed.

Verification ran from this worktree's backend/ in PowerShell, AI_MODE=replay. The first combined gate ran 69 cases: 34 passed and 35 failed due to one expiry fixture, Mockito dynamic attachment failure and a Docker engine failure. A subsequent daemon/worker run hit Windows native allocation/page-file exhaustion; it is not a test pass. Reduced-memory single-class execution then passed all 17 PasswordIntegrationTest cases. The temporary init script is outside the repository and enables Mockito's startup Java agent, test heap 256 MiB, Serial GC, two processors, one test class per worker and a one-entry Spring test-context cache; daemon heap is 192 MiB. The repository's build configuration is unchanged.

Commands completed:

```powershell
.\gradlew.bat spotlessApply compileJava compileTestJava spotlessCheck test --tests 'com.afterbooth.PasswordIntegrationTest' --tests 'com.afterbooth.LocalFakePasswordHttpsTest' --tests 'com.afterbooth.SessionIntegrationTest' --tests 'com.afterbooth.SignupIntegrationTest' --tests 'com.afterbooth.global.security.AuthRateLimiterIntegrationTest' --tests 'com.afterbooth.member.mail.MailGatewayTest' --tests 'com.afterbooth.member.mail.ResendMailGatewayTest' --tests 'com.afterbooth.ArchitectureTest' --no-daemon --max-workers=1
# Compilation/formatting passed; 34/69 passed, failures described above.
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xms32m -Xmx192m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC' --init-script C:\Users\ehddu\AppData\Local\Temp\afterbooth-agent-tests.gradle spotlessApply compileJava compileTestJava spotlessCheck test --tests 'com.afterbooth.PasswordIntegrationTest' --no-daemon --max-workers=1
# BUILD SUCCESSFUL; all 17 tests passed, zero failures/errors/skips.
```

Not verified: full backend suite, frontend/browser integration, actual Resend sending/receipt, production/provider settings, actual #69 withdrawal implementation, deployment and live AI. No remote writes or real delivery occurred. Foundation regressions and final changed-file checks are recorded below when complete.

Final foundation regression gate:

```powershell
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xms32m -Xmx192m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC' --init-script C:\Users\ehddu\AppData\Local\Temp\afterbooth-agent-tests.gradle test --tests 'com.afterbooth.SignupIntegrationTest' --tests 'com.afterbooth.global.security.AuthRateLimiterIntegrationTest' --no-daemon --max-workers=1
# BUILD SUCCESSFUL; 16 signup and five shared-limit PostgreSQL tests passed, no failures/errors/skips.
```

Across the successful affected runs, 73 distinct cases passed: 17 password, five password HTTPS, nine session, 16 signup, five shared-limit, six Fake/mail configuration, eight loopback Resend and seven architecture cases. The parent inspected actual diffs and XML counts. Changed-file secret scanning and git diff --check passed. No contract or frontend change required regeneration; no full-suite pass is claimed.

## Issue #69 — Withdrawal and retention (2026-11-03)

Implementation base: `origin/main` `091cae2`, containing #65–#68; isolated branch `feat/69-withdrawal-retention`, worktree `/private/tmp/afterbooth-69-withdrawal-retention`. This session resumed the stopped worktree (uncommitted prior work, last prior run 292 tests with one failure) and completed it through local commits. The prior run is history, not evidence; only the gates below count. D-083 authorizes the scope; no remote writes, real mail/Meta calls, production data or deployment.

Implemented scope:

- `withdrawMember` (DELETE /api/members/me): ACTIVE Bearer + current-password confirmation, parsed-path CSRF/Origin/Bearer checks through the shared chain, principal-derived identity, strict `WithdrawMemberRequest`, 204 with no-store and refresh+CSRF cookie clearing. No new rate scope (D-083); 429 declared but unreachable.
- Atomic withdrawal transaction: email advisory lock, ascending member locks, ACTIVE/authVersion/JWT recheck under lock, password match, HELD/CONFIRMED block on actual status (expired TTL still blocks; MISMATCH matters only on CONFIRMED), session revocation + auth_version increment, member-linked CSRF/challenge purge, HMAC email block, full personal-data scrub, `retention_until` earliest deadline. Immediate JWT/login/refresh rejection afterward.
- Single-server owned-work disposal (D-083): `MemberWorkRegistry` pause/drain/commit protocol for mail/intake/allocation/handover/condition payloads; queued payloads survive failed attempts and are discarded only after commit.
- Retention cleanup: per-minute scheduler with overdue monitor, advisory-serialized worker, handover → transition → reservation deletion in FK order, same-transaction `reuse_archive` + `archived_handed_over_qty` accumulation, unreferenced-organization/request/item/draft/event/member purge preserving counterparty refs and archive shells, read blocking at expiry.
- Reuse stats combine surviving HANDED_OVER rows with the archive, summing exact cm³ before a single division by 1000. Reuses the #65 schema (no new migration), #66 member-lock/session services and #67 DomainAccess; no second chain/store/encoder/mail, no contract change.

Verification (orchestrator-run gates, macOS, Docker engine, `JAVA_HOME=.../corretto-21.0.12.1`, AI_MODE=replay, disposable Testcontainers PostgreSQL, synthetic data only):

```bash
# backend/ — full #69 footprint + touched-domain regressions
./gradlew spotlessApply compileJava compileTestJava spotlessCheck test --tests WithdrawalIntegrationTest --tests WithdrawalPrivacyIntegrationTest --tests WithdrawalMailIntegrationTest --tests RetentionIntegrationTest --tests LocalFakePasswordHttpsTest --tests ArchitectureTest --tests MemberWorkRegistryTest --tests SignupCleanupIsolationTest --tests ReuseStatsPrecisionTest --tests MemberInputPrivacyTest --tests GlobalExceptionHandlerTest --tests AllocationServiceTest --tests IntakeProcessorTest --tests ConditionServiceTest --tests ReservationIntegrationTest --tests HandoverIntegrationTest --tests IntakeIntegrationTest --tests PickupRequestIntegrationTest --tests AllocationIntegrationTest --tests SessionIntegrationTest --tests SignupIntegrationTest --tests DomainAuthorizationIntegrationTest --tests CatalogAuthorizationIntegrationTest --tests PasswordIntegrationTest --tests AuthSecurityHttpsTest --tests DefaultVehicleHoldIntegrationTest --tests HoldTtlStartupTest --no-daemon
# 27 suites, 287 tests, zero failures/errors/skips (XML counts inspected).
# Post-review-fix gate (9 suites incl. R1/R2/R4 SQL coverage): 75/75 green.
# R6/R7 gate (MemberWorkRegistryTest 9, IntakeProcessorTest 13, ArchitectureTest 7, --rerun-tasks): 29/29 green.
```

The stopped session's single failure was a test-side hardcoded CSRF count (login legitimately creates a member-linked CSRF row); the corrected before/after preservation assertion passes. The completion round added pending-member, stale-principal, same-member-race and service-level reuse-total tests. Secret scan over all changed files and `git diff --check` are clean; the one scanner match (a `password` local) was renamed per the PasswordService precedent with no behavior change.

Review: fresh read-only review found blocking R1 (`trackChild` drain-count leak on thread-start failure) plus R2–R5; all fixed with regression tests. Fresh re-review approved with no blocking findings; follow-up R6 (intake log-pinning test) and R7 (`Error` compensation) also implemented and green. Declined as cosmetic: `@VisibleForTesting` on the test seam and empty `errorType` for anonymous classes; CONVENTIONS scope-list `member` entry left as a follow-up note. Codex CLI review was unavailable (provider usage limit until 2026-11-04 03:18); independent read-only reviews replaced it, and no Codex execution is claimed.

Not verified: full backend suite, frontend/browser/E2E (no frontend files changed), real Resend/Meta provider retention and delivery, multi-server withdrawal (excluded by D-083), deployment, production data. No push, PR, issue/comment/status change or merge was performed.

## Issue #70 — Frontend authentication and design (2026-11-04)

Historical PR #76 delivery at `d02c982`; the checklist below preserves that delivery record. Current follow-up results and remaining gaps are recorded under #17 below.

- [x] Authentication screens, in-memory session with coordinated refresh, event picker, login gate and visual refresh (PR #76).
- [x] Real local server run (`cd e2e && npm run test:auth`, Fake mail, replay): signup → verification → login → account → own event → photo intake, cross-member denial, giver/receiver request → hold → confirmation, withdrawal blocked then allowed.
- [ ] Authenticated demo E2E: `e2e/tests/demo.spec.ts` is skipped. New members cannot own the seeded organizations, and the recorded allocation and handover replay fixtures depend on the seeded database, so the demo needs a login step and new recordings. Owner: demo-qa. Recording needs human approval (credit gate); schedule it before G3, because main has no automated demo run until it lands.
- [ ] Time-controlled pending-registration expiry, re-registration isolation and expired transaction-history messaging in the browser.
- [ ] Password change and reset against the real server (component tests only so far).
- [ ] Decide how the live demo signs in before G3 (signup needs the private Fake code, so a pre-created demo account is needed).


## Issue #17 — current authenticated English submission readiness (2026-11-04)

Baseline: fetched `origin/main` `d02c98232cad21ae65d5c94a6bb45fee77bc23f0`; branch `fix/17-submission-readiness` (renamed from `codex/17-submission-readiness`), isolated `/private/tmp/afterbooth-17-submission-readiness`. The original checkout remains on main. The user's latest correction requires all new repository records in English; existing historical content remains unchanged.

- [x] Reconciled historical #9/#15 completion against current GitHub issues/PR evidence; documented the actual authentication migration without changing SQL.
- [x] Fixed refresh-notice sign-out and giver/receiver cancellation controls, with regression tests and unchanged server permissions.
- [x] Converted current UI, user messages, labels, AI instructions, contract descriptions and current run guides to English; retained Korean user input, recordings, Unicode passwords and Asia/Seoul semantics.
- [x] Added actual HTTPS two-member transaction verification through HANDED_OVER and statistics with an explicitly synthetic AiGateway and local Fake mail. This is separate from real Meta replay evidence.
- [x] Added pending-registration boundary/non-enumeration and re-registration ownership isolation checks. Actual HTTPS verifies expired-history API denial; browser-level time-controlled messaging remains unverified.
- [x] Real HTTPS browser authentication suite: six tests passed, including password change/reset, resend verification and refresh-trouble logout. The refresh 503 is Playwright-injected; logout/cookies/reload use the real server.
- [x] Reviewed blank login and credential-free refresh screens at mobile/desktop widths; no human teammate review is inferred.
- [x] Rewrote the full-demo test for owned events/inventory and three isolated authenticated contexts; strict TypeScript checking and discovery passed. Runtime success is not implied.
- [x] Authenticated English-output full Meta replay: separately approved real recordings and complete owned-data E2E verified; see the full-demo completion record below. The original ConditionInterpretationReplayTest failure was resolved by the separately approved single-condition recording below; remaining full-demo recordings are still missing.
- [ ] Actual teammate reviews, current video, submission form access/fields and final external submission.

See [SUBMISSION_READINESS](SUBMISSION_READINESS.md) for commands, evidence boundaries, current failures, G3 report and human actions. #17 and Phase 3 remain incomplete; no push, PR, merge, GitHub write, deployment, upload or external submission was performed. The earlier #70 checklist above is its historical delivery record; this section describes newly verified scope and remaining gaps.


### #17 replay recovery preparation (2026-11-04)

Continue the existing `fix/17-submission-readiness` branch from `a9237b0`; preserve preceding commits and original recordings. Reproduced the condition replay failure and verified its exact missing English hash against the original request. Added a separately gated one-attempt condition recorder and deterministic owned-demo database setup/ID assertions. Production member-owned RECORD remains forbidden; no paid call was made. The full-demo recorder and both race recordings remain BLOCKED pending staged preparation and explicit paid approval; ENGLISH_RECORDINGS_READY remains false.

Current checks: 60 focused backend tests and three additional no-network photo/handover request preparation checks plus format/compile passed; E2E type checking passed; six authenticated browser checks passed; owned-demo fresh DB reset smoke passed; demo readiness remains an explicit failure with ten overlap/helper tests passing. See [SUBMISSION_READINESS](SUBMISSION_READINESS.md#replay-recovery-preparation-on-fix17-submission-readiness-2026-11-04) for exact synthetic inputs, request differences, cost limits and unverified scope. No full-demo success or Phase 3 completion is claimed.


### #17 approved single-condition recording (2026-11-04)

- [x] The user approved one Spark attempt, $2 ceiling. Recorded the exact missing `4c5fb1…` condition fixture through the real Meta API; no retry or additional paid capability call. Estimated cost $0.001451 is recorded in BUDGET.
- [x] Replayed the formerly failing ConditionInterpretationReplayTest successfully; 20 related backend checks passed with zero failures/errors/skips. Verified canonical hash, preserved all 19 original recordings and scanned the new fixture for secrets and credential/personal-data patterns.
- [ ] Full English authenticated demo, both race recordings, real photo handover/statistics and the complete recording runner remain incomplete. ENGLISH_RECORDINGS_READY is still false; approval did not include further paid requests.

Post-recording full E2E command: typecheck, six auth checks and ten helpers passed; the overall command failed at the retained missing-full-demo-recordings gate. No full E2E success is claimed.


### #17 full English owned-data replay completed (2026-11-04)

- [x] Preserve branch `fix/17-submission-readiness`, preceding commits and all 20 pre-existing recordings byte-for-byte; no production API, schema, dependency, auth, ownership or member recording protection changes.
- [x] Separately approved bounded recording: 12 actual Meta exchanges (11 Spark, one SAM), estimated $0.03397650; zero failures/retries and no new Transcribe calls. BUDGET and the curated fixture index record the evidence.
- [x] Controlled simultaneous rejection and post-commit stock-zero candidates each passed the full shared photo → condition → allocation/race/alternative → receiver confirmation → giver photo handover → HANDED_OVER/statistics assertions. Natural scheduling also passed key-free replay, with no new recording.
- [x] Cleared ENGLISH_RECORDINGS_READY only after full candidate replay success. The default `npm --prefix e2e test` then passed TypeScript, six auth checks, the complete ordinary-product-jar demo and ten helpers. No capture scheduler or paid recorder is used by that default run.
- [x] Backend format/compile/bootJar plus 28 related tests passed, zero failures/errors/skips: real new-fixture round trips, budget/privacy/hash/architecture and original condition/intake replay regressions. Initial test-configuration scanning collision was fixed and the affected command passed serially.

The requested automated replay scope is complete. Human screen review, video, forms and external submission are separate issues and were not performed. English speech, live performance and actual billing reconciliation remain unverified. See [ENGLISH_REPLAY](ENGLISH_REPLAY.md) and the latest [SUBMISSION_READINESS](SUBMISSION_READINESS.md) section; earlier blocked entries are historical snapshots. No push, PR, merge, deployment or external submission.


Additional domain regressions passed: 71 tests across AllocationIntegrationTest, HandoverIntegrationTest, DomainAuthorizationIntegrationTest and CatalogAuthorizationIntegrationTest. Total affected backend evidence is 28 + 71 passing checks across separate runs, not a full-suite claim. The independent reviewer inspected privacy/scheduling/budget logic but hit its usage limit before a completed verdict; this is not recorded as approval. Final secret/canonical checks passed and the product jar excludes test-only recording tools.
