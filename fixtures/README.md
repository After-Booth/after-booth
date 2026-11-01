# fixtures

AI responses that `AiGateway` reads when `AI_MODE=replay`, plus input files for E2E and tests. `record` mode writes here.

- No photos or audio with real faces or personal data
- secret-scan checks before each commit that no key or token ended up in a response
- `spike/` holds the raw Phase 0 responses (see its README); they are not read by replay

## Replay format

One file per unique recorded request: `fixtures/replay/<capability>/<key>.json`, where `<capability>` is `spark`, `sam` or `transcribe` (`com.afterbooth.ai.FixtureStore`). RECORD reuses this file until a human deletes it to re-record.

```json
{
  "request": { "method": "POST", "path": "/v1/responses", "body": { "...": "redacted request" } },
  "response": { "status": 200, "body": { "...": "the response as parsed JSON" } }
}
```

- `<key>` is the SHA-256 (hex) of `POST <path>\n<canonical body>`.
  - `<path>` is the endpoint path from `afterbooth.ai.paths.*` (`/v1/chat/completions`, `/v1/responses`, `/v1/asr/transcribe`). The base URL is not part of the key.
  - `<canonical body>` is the request JSON with keys sorted at every level and no whitespace, after redaction: every `data:<mime>;base64,...` string becomes `{"bytes", "redactedDataUrl", "sha256"}` (the same redaction `spike/run.mjs` uses).
  - Transcribe is multipart, so its body is `{"request": <the JSON request part>, "audio": {"bytes", "sha256"}}`.
- Anything in the request changes the key: model ID, prompt, schema, tools, `reasoning_effort`, image or audio bytes. Spark answers differ between runs, so the response is never part of the key.
- So anything time- or race-dependent in a request misses in replay. Prompts take the current time from the injected `Clock`, and replay runs use a fixed `Clock`. Photos and audio must be byte-identical inputs. For the allocation demo, record both outcomes of the race.
- The file holds the redacted request for humans and the response. The key and headers are never written. Only 2xx responses are recorded.
- Concurrent identical requests share one recorded response. Incremental recording adds missing request keys and reuses existing files without overwriting them.
- A replay request with no file throws `MissingFixtureException` naming the capability, the key, the expected path and how to record it. A file without a 2xx `response.status` and a `response.body` object throws `IllegalStateException` naming the file. Replay never calls the network and never makes up a response.

## Recording

`AI_MODE=record` reuses an existing fixture without an HTTP call and logs `outcome=reused`. For a missing fixture, it makes the live call and writes the file above. Concurrent identical requests in one gateway share a single in-flight call and its response. Failures propagate to all waiters and are not cached. Later runs add missing branches without overwriting earlier responses; a human deletes an individual file to re-record it. New calls spend credits, so a human runs recording (AGENTS.md hard rule 6; the Claude Code hook asks first), then logs the cost in `docs/BUDGET.md`.

The gateway smoke records one tiny call per model (a Korean Spark text prompt at `reasoning_effort: minimal`, one SAM box prompt on `spike/samples/table-lifetime-single.jpg`, one Transcribe call on `spike/samples/audio-giver-van-tonight.wav`), about $0.004 in total at the spike's per-call costs. From the repo root:

```bash
node --env-file=.env spike/with-env.mjs backend/gradlew.bat -p backend test --tests com.afterbooth.ai.AiGatewayRecordSmokeTest -PrecordSmoke --rerun
node scripts/secret-scan.mjs fixtures/replay/spark/*.json fixtures/replay/sam/*.json fixtures/replay/transcribe/*.json
```

There is one test method per model (`recordsSpark`, `recordsSam`, `recordsTranscribe`), so after deleting its fixture a human can re-record a single model with `--tests com.afterbooth.ai.AiGatewayRecordSmokeTest.recordsTranscribe`.

`AiGatewaySmokeReplayTest` then replays those three files on every test run, with no key and no network. It is skipped only while `fixtures/replay/` does not exist.

The model IDs in `.env` must match the defaults in `application.properties` (D-027), because tests replay with the defaults and the model ID is part of the key.

Other calls are recorded the same way, through an opt-in test like `AiGatewayRecordSmokeTest` (Gradle forces `AI_MODE=replay` for tests) or the app started with `AI_MODE=record`. Commit the new files under `fixtures/replay/` only after the secret scan passes.

## Live fallback

LIVE prefers the live response. When a fixture exists for the same capability and request key, a failed live call (connection error, timeout, non-2xx or non-JSON) or an expired fallback window returns that fixture. The abandoned call is interrupted on its virtual thread. With no fixture, normal timeouts and errors apply. RECORD and REPLAY never use this fallback.

The windows are `afterbooth.ai.fallback-after.spark=8s`, `afterbooth.ai.fallback-after.sam=5s` and `afterbooth.ai.fallback-after.transcribe=6s`, above the slowest live calls measured so far (Spark minimal image 6.4 s max, tool turn 3.9 s, SAM 3.5 s, Transcribe 3.3 s). After one fallback, calls that have a fixture skip the network for `afterbooth.ai.fallback-cooldown=60s`, so a multi-turn allocation waits one window, not one per turn. The cooldown is shared by Spark, SAM and Transcribe because they use the same host: a Spark fallback also answers recorded SAM and Transcribe calls from fixtures for that minute. Unrecorded calls still go live. Each fallback after a live attempt writes one WARN with the capability, request key and reason; calls answered during the cooldown log only the INFO line.

The fallback covers a network loss before a step starts. Inside a step, later calls carry the live answer of the first call (allocation tool-call IDs, Spark boxes sent to SAM), so they have no fixture. Allocation restarts its conversation once from the first turn when a model call fails before any HOLD, which replays the recorded run, and logs one INFO line with the request ID. Intake and handover fail that attempt (`AI_UNAVAILABLE` or a failed draft) and succeed on the retry, because their first call falls back. A stalled (not refused) connection inside a step waits the normal Spark timeout first. For the stage, prefer replay (the compose default) or keep network drops between steps. Every exchange also writes one INFO with capability, mode, outcome (`live`, `recorded`, `reused`, `replay`, `fallback`, or `failed`) and elapsed milliseconds.

## Demo scenario

The recorded demo replays only when every AI input is byte-identical and every step runs in this order from an empty database, because item IDs and stock are part of the allocation prompts (PR #40 R1, R9). Use these inputs; do not edit the intake draft or the interpreted conditions.

1. Reset: `docker compose down -v`, then `docker compose up --build` (replay) or the record or live variant below. The seed deadline is migration time + 7 days, and `AFTERBOOTH_DEMO_REFERENCE_DATE=2026-10-31` pins the request reference date in every compose variant.
2. Photo intake (home page): upload `spike/samples/bins-orange-stack.jpg`, wait for "수납함 4개", press "재고로 등록" once, without edits. Retaking the photo before confirming is fine; confirming twice or editing adds or changes inventory that the allocation searches can list, so start again from step 1.
3. Voice request (`#/request`): choose "Community Makers", upload `spike/samples/audio-receiver-racks-sedan.wav` with "녹음 파일 올리기 (WAV)", press "픽업 요청 저장" (request #1: display rack 3, sedan), then "배정 제안 받기". The agent finds the 180 cm rack does not fit a sedan and offers alternatives; do not pick one.
4. Two-browser race: in two windows on `#/request`, choose "Seoul Event Collective" and "Campus Festival Crew", type exactly `진열대 한 개가 필요해요. 밴으로 가져갈게요.`, press "입력한 내용 해석하기" and "픽업 요청 저장" (requests #2 and #3: display rack 1, van). Press "배정 제안 받기" in both. Either window may win. Both branches are recorded: clicks within a few milliseconds (the loser's hold is rejected after both searched) and clicks further apart (the loser searches after the winner committed and sees stock 0).
5. In the winner's window, press "진열대 예약 확정하기" under the held reservation (no AI call).
6. Handover (`#/handover`): select the confirmed reservation and upload `spike/samples/rack-gondola-single.jpg`. The recount matches one rack and completes the handover.
7. Statistics on the same screen show one reused display rack.

Recording (key A, approved by a human first): from a worktree, the key file stays in the main checkout, so point a copy of `docker-compose.record.yml` at it. Run steps 1–7 once with the two race clicks together, then step 1 again and steps 2–4 with the second race click after the first window shows "배정 성공". The second run reuses every existing fixture and records only the stock-0 branch. Then run the secret scan and commit the new files.

```bash
docker compose -f docker-compose.yml -f docker-compose.record.yml up --build -d
```

Network-loss check without spending credits: start live mode with a placeholder key and an unroutable base URL (`AI_MODE: live`, `META_API_KEY: not-a-real-key`, `META_BASE_URL: http://10.255.255.1` in an override file) and run steps 1–7. The first Spark call after a quiet minute waits the 8 s window, then every step completes from fixtures.

No automated test replays these demo fixtures yet; #12's replay E2E is the check that a prompt, schema or model-data change has not orphaned them.

Measured on 2026-11-01 (#13): live text structuring 1.4 s, allocation 7–10 s over 3–4 tool turns, handover recount 3.3 s. The loading screens use these values (`frontend/src/features/aiLatency.ts`).
