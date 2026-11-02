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

## Phase 3: Submission (target 11/4 morning)

- [ ] #15 English README (DY)
- [ ] #16 Demo script + 2–3 minute video (WJ, key B)
- [ ] #17 Submission checklist (DY, WJ)
- [ ] **G3 STOP** — report how to run, demo results, self-score, remaining risks
