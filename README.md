# AfterBooth

When a festival, exhibition or pop-up ends, the booth equipment (display racks, storage bins, folding tables) has to be cleared before the teardown deadline, often within hours. Whatever has no new owner by then gets thrown away. AfterBooth hands that equipment to the next event or group before the deadline.

Built for the Meta Global AI Developer Hackathon (Indie track) by a team of two. The current UI and user-facing messages are English and mobile first. Times are shown in Asia/Seoul (KST). The product spec is [`docs/SPEC.md`](docs/SPEC.md).

## How it works

1. **Use only your own work.** An active member creates a private organization and events. Events, inventory, requests, reservations, and statistics are authorized from that member’s organization; seeded or another member’s organization cannot be selected or taken over.
2. **Photograph the booth.** The giver takes one photo. Muse Spark finds and counts each item, SAM 3.1 checks every box Spark drew, and the result becomes an inventory draft that a person reviews and confirms.
3. **Say what you need.** The receiver speaks or types a request, for example “display racks for a weekend event, I only have a sedan.” Muse Voice Transcribe turns supported WAV input into text and Muse Spark turns it into structured conditions (category, quantity, vehicle, pickup time).
4. **Get an allocation.** A Muse Spark agent searches stock, checks whether the item fits the receiver’s vehicle, and can place a temporary HOLD through server tools. When nothing fits, the person gets the server’s alternatives, such as folding tables instead of a 180 cm rack that will not fit in a sedan.
5. **Race for the last item.** Two receivers can ask for the last display rack at the same moment. Exactly one HOLD succeeds; the other side sees an alternative.
6. **Hand over with a photo.** At pickup, one photo is recounted by Spark and checked by SAM 3.1. Only a matching count completes the handover; otherwise a person settles the difference.
7. **See what was reused.** Statistics show the reused count per category and per event, and the reused volume in liters from the items’ registered sizes.

## Meta models in the product

Every AI call in the product goes to the Meta Model API through one gateway (`AiGateway`). No other model provider is used at runtime.

| Model | What it does | What its output leads to |
|---|---|---|
| Muse Spark | Finds and counts items in a booth photo with category, box, size and condition | An inventory draft, then inventory items after a person confirms |
| SAM 3.1 | Segments each item from Spark's boxes (box prompts); an item without a mask is flagged for review | Review flags on the draft; at handover, the gate that lets a recount complete |
| Muse Voice Transcribe | Transcribes a spoken pickup request (16 kHz mono WAV) | Text for Spark to structure |
| Muse Spark | Structures the request against a JSON schema | A saved pickup request |
| Muse Spark (function calling) | Runs the allocation agent with `searchCandidates`, `checkVehicleFit` and `holdQuantity` | A server-validated HOLD, never a confirmed reservation |
| Muse Spark | Recounts the reserved category in the handover photo | HANDED_OVER and the reuse statistics, or a mismatch for a person |

## AI proposes, the server decides

- The agent can only call the three tools above. The server executes every call, validates stock, the pickup deadline, the request's quantity, the category and the vehicle fit, and returns a typed rejection to the model when a call breaks a rule.
- The strongest state the agent can create is a HOLD. A person confirms it.
- The agent loop is bounded: at most 6 model turns within a 30-second agent-loop budget, at most 12 tool calls per model response and at most 3 rejected calls ([D-051](docs/DECISIONS.md)). If a model call fails before any HOLD, the run restarts once from the first turn ([D-057](docs/DECISIONS.md)).
- A handover never completes on the model's word alone: Spark's count must equal the reserved quantity and SAM 3.1 must segment every counted box ([D-032](docs/DECISIONS.md), [D-034](docs/DECISIONS.md)).

## Architecture

```mermaid
flowchart TD
    U["Browser<br/>React 19, English UI"] -->|REST /api| API["Spring Boot 4.1 API"]
    API --> D["Domain services<br/>intake, request, allocation, handover, stats"]
    D --> DB[("PostgreSQL 17")]
    D --> GW["AiGateway<br/>live / record / replay"]
    GW -->|replay| FX[("fixtures/replay<br/>recorded responses")]
    GW -->|live or record| META["Meta Model API<br/>Muse Spark, SAM 3.1, Muse Voice Transcribe"]
```

- `backend/`: Java 21, Spring Boot 4.1, Flyway migrations, `RestClient` to the Meta Model API behind `AiGateway`.
- `frontend/`: React 19, Vite, TypeScript, Tailwind v4. The API client and mocks are generated from [`contract/openapi.yaml`](contract/openapi.yaml), the API contract of record.
- The React build is served by Spring Boot, so one app container serves both the API and the UI ([D-056](docs/DECISIONS.md)).

## Concurrency and correctness

- **Stock never goes negative.** A hold decrements stock with a conditional update (`available_qty >= :q`). Zero affected rows means the hold failed. Cancel and expiry change the reservation status with a conditional update (`status = :expected`) and restore stock only when that update wins, so stock comes back exactly once.
- **One request, one quantity.** Holds for the same pickup request take the request row lock, so holds on different items never exceed the requested quantity, whether a person or the agent places them.
- **State machine.** `HELD → CONFIRMED → HANDED_OVER`, `HELD | CONFIRMED → RELEASED`, `HELD → EXPIRED`. Forbidden transitions are rejected. A scheduler expires holds past their TTL and releases confirmed reservations past the pickup deadline, and a confirm racing that expiry has exactly one winner.
- **Idempotency.** Domain writes that create or change state carry an `Idempotency-Key` (a UUID per user action). The server fingerprints method, path, and body (or a multipart file hash): the same key and fingerprint replays the first response, while a different fingerprint or an in-flight original returns `IDEMPOTENCY_CONFLICT`. Before replaying, the server authenticates the member and rechecks authorization on the original resource; keys are scoped to the server-derived member. Authentication APIs, membership withdrawal, and password change are exempt. Credentials, verification codes, tokens, and their responses are never stored in the idempotency record.
- **Crash recovery for idempotency keys** ([D-067](docs/DECISIONS.md)). A request holds a PostgreSQL session advisory lock on a dedicated connection for its whole lifetime, including AI preparation. If the process dies, PostgreSQL releases that lock, and a retry with the same key and body recovers the abandoned `IN_PROGRESS` record. While the original request is still running and holds its lock, a retry gets `IDEMPOTENCY_CONFLICT`. There is no age threshold and no background sweep, so recovery happens on a matching retry once the database has noticed the dead connection.

The required tests run against a real PostgreSQL in Testcontainers (`backend/src/test/java/com/afterbooth/ReservationIntegrationTest.java`):

1. 100 threads ask for the last 2 units, and exactly 2 succeed: `exactlyTwoOfOneHundredThreadsCanHoldLastTwoItems`.
2. Cancel and expiry restore stock exactly once: `cancelRestoresExactlyOnceAcrossConcurrentCalls`, `expiryRestoresExactlyOnceAndSchedulerReleasesMissedPickups`.
3. A repeated idempotency key never decrements twice: `repeatedKeyReplaysStoredResponseWithoutDecrementAndRejectsDifferentBody`.
4. Forbidden state transitions are blocked: `forbiddenTransitionsAreRejected`.
5. Agent tools cannot bypass server validation: `holdRulesRejectLateOversizedAndExcessQuantityForUserAndAgent`.
6. Concurrent holds on different items never exceed the request quantity: `differentItemsForSameRequestCannotExceedRequestQuantity`.
7. Confirm racing hold expiry has one winner and restores stock once: `confirmSampledBeforeExpiryLosesToSchedulerWithoutDoubleRestoration`, `schedulerSampledAfterExpiryLosesToConfirmWithoutRestoringStock`.

Idempotency crash recovery is covered by `IdempotencyRecoveryIntegrationTest`, which simulates a crash by terminating the test's own PostgreSQL lock session.

## Record, replay and live fallback

`AiGateway` runs in one of three modes (`AI_MODE`):

- **replay** (the default, and every automated test): answers from recorded responses in [`fixtures/replay/`](fixtures/README.md) without any network call or key. A fixture is keyed by the SHA-256 of the canonical, redacted request, so a request that was never recorded fails instead of getting a made-up answer.
- **record**: makes the live call once and stores the response; identical concurrent requests share one call.
- **live**: calls the Meta Model API. If a recorded answer exists for the same request and the live call fails or exceeds its window (Spark 8 s, SAM 5 s, Transcribe 6 s), the recorded answer is used and later recorded calls skip the network for 60 seconds ([D-057](docs/DECISIONS.md)). This keeps the stage demo running through a network drop between steps; a drop inside an intake or handover step fails that attempt, and the retry answers from the recording.

Because the key covers the exact request bytes, replay only answers the recorded demo inputs in the recorded order. New photos or recordings need live mode.

`RECORD` is deliberately rejected while processing member-owned work, so an authenticated person’s photos, audio, or request data cannot be captured by turning recording on. The approved fixture process instead prepares only reviewed synthetic inputs outside a member request, verifies the exact serialized gateway request before transport, and requires separate human approval for every paid recording scope. [ENGLISH_REPLAY](docs/ENGLISH_REPLAY.md) defines that boundary; it is not a way to enable recording for manual use.

## Run and verify locally

Authentication is required. The root `docker-compose.yml` provides PostgreSQL and the app image in replay mode, but its app service exposes HTTP and supplies no authentication HMAC/JWT settings or mail activation. **The old “Docker only” command is not a supported signup/login or complete-demo recipe after authentication.** Starting a container or getting `/api/health` is separate from proving authentication and the transaction flow. Do not disable authentication to revive the old demo.

### Automated local verification

Install Docker, Java 21+, **Node.js 24**, and Chromium. Run each command from the directory shown; `npm --prefix` commands are intentionally run from the repository root.

| Working directory | Command | Purpose |
|---|---|---|
| repository root | `npm --prefix frontend ci` | Install the frontend lockfile dependencies. |
| repository root | `npm --prefix frontend run build` | Generate the API client and build the frontend. |
| `backend/` | `./gradlew bootJar` | Build the jar used by the browser harness. |
| `e2e/` | `npm ci` | Install the independent E2E lockfile dependencies. |
| `e2e/` | `npm run install-browsers` | Install the Playwright Chromium shell. |
| `e2e/` | `npm test` | Run TypeScript, authentication browser checks, and the full owned-data replay demo. |

The harness starts the real jar at `https://localhost:5443`, a disposable PostgreSQL 17 container on a random loopback port, in-memory signing/HMAC material, and a short-lived TLS directory. It uses the `local` profile with explicitly enabled local-only Fake mail and `AI_MODE=replay`; Chromium pins only that temporary certificate. On normal completion and startup/test failure cleanup, it stops the temporary server and database container and removes the TLS directory. Do not put generated keys, certificates, codes, accounts, or test output in Git. Fake mail proves local behavior, not real mailbox ownership or delivery. Replay makes no paid provider calls. See [`e2e/README.md`](e2e/README.md) for the exact scope and prerequisites.

### Manual local use — not executed here

A manual local setup needs a private PostgreSQL database, HTTPS with a locally trusted certificate, exact `afterbooth.auth.allowed-origin`, an HMAC current-version/key map, JWT issuer/audience/current-kid/private-key/public-key maps, and explicitly selected local Fake mail. Generate keys locally and keep their values outside the checkout. The automated harness in [`e2e/tests/authStack.ts`](e2e/tests/authStack.ts) is the executable property-structure reference, not a copy-and-paste production configuration. Default mail is disabled; Fake is rejected outside `local` or alongside production profiles. Actual delivery requires deployment-only Resend configuration and separate verification.

For a manual flow, sign in as synthetic members in separate HTTPS browser sessions. The giver creates their own event and inventory; the receiver creates their own request, receives a HOLD, and confirms it; a last-item race needs a second receiver session. The giver performs photo recount and any quantity settlement, while a receiver cancels only their own reservation. Never select or take over a seeded organization. No manual setup or manual end-to-end run was executed for this documentation change.

### Current demo status

The authenticated English-output full demo is **verified in replay** with three synthetic members and owned data. Separately approved real Meta recordings cover the changed requests; original recordings remain unchanged. Both last-item race branches passed complete candidate runs, followed by the ordinary product-jar E2E. The original WAV is Korean: English speech recognition is not claimed. See [`docs/ENGLISH_REPLAY.md`](docs/ENGLISH_REPLAY.md) for exact inputs, recording boundaries and checks.

```bash
npm --prefix e2e run test:demo  # Full owned-data demo, verified in replay.
npm --prefix e2e test          # TypeScript, 6 auth checks, full demo and 10 helpers.
```

Authentication checks alone do not certify allocation-agent → completed photo handover → statistics. Confirmation followed by cancellation is not completed handover. The current evidence, blockers and required human actions are in [`docs/SUBMISSION_READINESS.md`](docs/SUBMISSION_READINESS.md).

The automated exact-input replay is verified through HANDED_OVER and statistics; it is not evidence of a completed manual run. For the current manual boundaries and the supplied-video script, see [Manual local use](#manual-local-use--not-executed-here) and [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md).

### Historical replay inputs

The former unauthenticated Korean demo used `spike/samples/bins-orange-stack.jpg`, the **Korean** WAV `spike/samples/audio-receiver-racks-sedan.wav`, the original text `진열대 한 개가 필요해요. 밴으로 가져갈게요.`, and `spike/samples/rack-gondola-single.jpg`. These are historical inputs, not a working authenticated English demo recipe. Preserve their bytes and do not rename the WAV as English. Existing samples and original recordings do not need a real API key; missing exact requests fail rather than inventing responses.

### Provider cost and recording boundary

[`docs/BUDGET.md`](docs/BUDGET.md) is the single source of truth for planned and recorded Meta provider cost, approval boundaries, dates, and the cost ledger. Automated replay and the supplied-video review make no provider call. Any future `live` or `record` call needs separate human approval first, must keep keys human-managed and outside committed data, and must append its actual outcome/cost to that ledger. The historical Compose overrides do not supply the current HTTPS/authentication setup, so they are not a current complete-demo recipe.

### Demo video

- **Supplied attachment:** a private 00:50 video reviewed for this documentation update. It is not committed to this repository and is not a public link.
- **Public video:** no public URL has been supplied. No upload or link was created by this work.
- **Script and limits:** [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) maps the attachment’s observed scenes and identifies what it does not establish.

## Historical measurements (before authentication and English conversion)

The following are historical project records, not runs performed for the current authenticated English build.

- Three human-approved live runs of the full scenario, each from a clean database, each with 19 AI calls answered live and no fallback:
  - one through the UI with Playwright: photo to result in 6.4 s, the whole scenario in 38.2 s ([`docs/TASKS.md`](docs/TASKS.md) #12);
  - two through the HTTP API on 2026-11-02: the whole flow in 40.7 s and 36.3 s.

  That is three runs, not a benchmark. The authoritative historical and current provider-cost ledger is [`docs/BUDGET.md`](docs/BUDGET.md); this README intentionally does not duplicate its amounts.

## Development tools

The product's AI is Meta-only. The development work used three coding agents, all disclosed here:

- **Claude Code** and **Codex** wrote and reviewed most of the code under one rulebook, [`AGENTS.md`](AGENTS.md), with agent definitions in [`.claude/`](.claude) and [`.codex/`](.codex). The repository review workflow calls for a Claude reviewer agent and Codex before human merge; individual historical substitutions and gaps are recorded ([`docs/REVIEW_GUIDE.md`](docs/REVIEW_GUIDE.md)).
- **Muse Code** (Meta) reviewed and implemented parts of the core paths. Each use is logged with a `[Muse Code]` tag in [`docs/DECISIONS.md`](docs/DECISIONS.md):
  - D-024: review of the API contract.
  - D-052: review of the allocation agent ([screenshot](docs/evidence/muse-allocation-review.png)).
  - D-061 and D-062: implementation and review of strict quantity parsing for handover and holds.
  - D-066: review of the photo intake path ([output rendering](docs/evidence/muse-intake-review.png)).
  - D-084: withdrawal implementation completion record.

## Repository map

- [`docs/SPEC.md`](docs/SPEC.md): product requirements and the demo scenario.
- [`contract/openapi.yaml`](contract/openapi.yaml): the API contract. [`docs/ERD.md`](docs/ERD.md): the data model.
- [`docs/DECISIONS.md`](docs/DECISIONS.md): every design decision, numbered. [`docs/TASKS.md`](docs/TASKS.md): progress, verification records and the self-score.
- [`fixtures/`](fixtures/README.md): recorded AI responses for replay. [`spike/REPORT.md`](spike/REPORT.md): the Phase 0 model spike.
