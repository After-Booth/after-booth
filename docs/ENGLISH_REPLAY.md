# Authenticated English replay

Scope: synthetic owned photo inventory → conditions → allocation and two receivers competing for the last rack → receiver confirmation → giver photo handover → event reuse statistics. Video, submission forms and external submission are excluded.

## Recording boundary

The normal application remains in REPLAY and keeps authentication, ownership, idempotency, inventory constraints and the member-owned RECORD prohibition. Production classes are unchanged. The preparation application exists only on the test runtime classpath; it cannot be selected in the product jar. It uses the same real gateway serialization and fixture lookup. On a missing JSON request it writes a request-only manifest into private temporary storage, then rethrows the failure. It never returns an invented response.

The independent recorder accepts only the reviewed synthetic scenario: two fixed photo digests, the original Korean voice transcript, the exact English van request, fixed reference date, configured Standard models and exact paths. It checks the canonical hash, hydrates only the approved photo bytes, reconstructs typed AiGateway inputs and compares the gateway's serialized request with the reviewed manifest before transport. It neither changes a member scope nor runs inside a member request. Existing fixtures are reused and never overwritten.

The incremental runner starts a fresh HTTPS application and disposable PostgreSQL database for each candidate execution. It repeats only when a captured missing request exists, reviews that manifest without a key, and records it only with an explicit approval flag. Other assertion failures stop the run. A missing recording is a real failure; the candidate waiter simply reports it promptly instead of waiting for a UI timeout. No scenario assertion is removed.

## Synthetic state and assertions

- Three separately verified accounts, giver first then receivers A/B; organization IDs 1/2/3. No credentials, email addresses, codes, signing keys, cookies or TLS material are recorded.
- Empty disposable domain state after migrations, with an explicit no-member check before reset; no external DB target is accepted.
- Owned event #1: Synthetic Reuse Expo / Synthetic Hall, teardown `2030-01-01T18:00` KST; condition reference date `2026-10-31`.
- Register `bins-orange-stack.jpg` then `rack-gondola-single.jpg`, without changing AI output. Four bins and rack #2 with stock one are required.
- Unchanged Korean `audio-receiver-racks-sedan.wav`: request #1, three display racks / SEDAN; no match, vehicle rejection and alternatives. This does not establish English speech recognition.
- Both receivers type exactly `We need one display rack. We will collect it in a van.`; requests #2/#3. Require actual HTTP interval overlap, exactly one HOLD winner, zero loser holds, QTY_UNAVAILABLE, and a visible enabled alternative.
- Winner confirms. Giver uploads the rack photo; require HANDED_OVER, quantity one, remaining stock zero, and event-filtered statistics matching API quantity and volume.

The shared `demoScenario.ts` supplies every assertion to both the default gated demo and the candidate suite. The test-only replay scheduler controls timing without modifying a model response or a database quantity: simultaneous candidates both reach their final recorded answer before either finalizes; stock-zero candidates keep the second server request active until the first commits its real HOLD. The same overlap and outcome assertions apply. A separate key-free natural scheduling run checks the unscripted race as well; the paid runner is restricted to the two approved controlled branches. The product app uses no scheduler.

Native selectors use their accessible combobox name prefix because a wrapping select label includes option text. Navigation waits for the account screen before remounting intake/request screens; this preserves the actual UI lifecycle rather than changing server state.

## Bounded paid session

Separate human approval is mandatory. The 2026-11-04 full-demo approval is limited to **$2 total and 30 new HTTP attempts**, with typical expected cost $0.05–$0.15. The prior single-condition approval is separate.

One shared, locked private ledger persists across recorder processes. Before each call it reserves $1.867776 for Spark (the conservative full-context/full-output maximum at current rates) or $0.0025 for one SAM image. It refuses transport if previous charges/reservations plus the next reservation would exceed $2, or 30 attempts have been used. Successful responses settle to uncached input/output usage; reasoning tokens are already included in output. A failed attempt or missing usage retains its full reservation and is never retried automatically. A process interruption also leaves the reservation intact. No additional token parameter is injected, because that would alter the actual request hash.

Rates checked on 2026-11-04: [Spark input $1.25/M, output $4.25/M; SAM $0.0025/image](https://dev.meta.ai/docs/pricing-rate-limits). [Official Spark configuration limits](https://dev.meta.ai/docs/quickstart): context 1,048,576, output 131,072. This bound depends on the provider's documented rates and limits, not a billing-dashboard guarantee. There are no new Transcribe calls in this plan. Record actual session usage in BUDGET and preserve uncertain reservations.

## Commands and evidence

Build with Java 21 before using candidate tools:

```bash
cd backend
./gradlew spotlessApply compileJava compileTestJava spotlessCheck bootJar prepareReplayTools
cd ../e2e
npm run typecheck
```

Create a private session under `/private/tmp/afterbooth17-full-replay-...`, with `manifests` and `budget` subdirectories. Run `spike/complete-english-replay.mjs <session>` for key-free preparation. After separate approval only, supply credentials through the existing approved environment loader, set `AFTERBOOTH_ENGLISH_REPLAY_APPROVED=true`, and add `--record`. The same session/ledger must be retained for every incremental call; do not create a fresh budget to evade a limit.

After all candidates pass, review the genuine recordings, secret-scan them, verify original digests and run related backend checks. Only then clear ENGLISH_RECORDINGS_READY and run the default `npm --prefix e2e test` against the ordinary product jar. Candidate timings are local replay measurements, not live Meta performance. The current execution results and remaining limits belong in SUBMISSION_READINESS and TASKS.


## 2026-11-04 result

Both controlled full candidates and a natural key-free candidate passed. The ordinary-product default E2E subsequently passed all 17 browser/helper checks plus TypeScript, and 28 focused backend tests passed. New provider use was 12 calls, estimated $0.03397650; [BUDGET](BUDGET.md) and [the fixture index](../fixtures/english-owned-demo.json) provide the breakdown. Original 20 recordings were preserved; no response was manually changed. ENGLISH_RECORDINGS_READY was enabled after actual candidate success. Future new paid calls are not authorized by this result.
