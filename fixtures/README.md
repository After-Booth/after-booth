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

The windows are `afterbooth.ai.fallback-after.spark=10s`, `afterbooth.ai.fallback-after.sam=5s` and `afterbooth.ai.fallback-after.transcribe=6s`. Each fallback writes one WARN with the capability, request key and reason. Every exchange also writes one INFO with capability, mode, outcome (`live`, `recorded`, `reused`, `replay`, `fallback`, or `failed`) and elapsed milliseconds.
