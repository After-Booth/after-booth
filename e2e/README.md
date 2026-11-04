# AfterBooth browser verification

## Current commands

Prerequisites: Docker, Java 21+, Node.js, Chromium, and locally built frontend and backend. Run from the repository root:

```bash
npm --prefix frontend ci
npm --prefix frontend run build
cd backend
./gradlew bootJar
cd ../e2e
npm ci
npm run typecheck
npm run install-browsers
npm run test:auth
```

`test:auth` starts the built jar on `https://localhost:5443`, a fresh disposable PostgreSQL container on a random loopback port, synthetic signing keys and an ephemeral TLS certificate. Reserve port 5443 for this run. Chromium pins only this certificate. The stack enables the local-only Fake mail gateway and `AI_MODE=replay`. Codes and application output are consumed only in process memory. Traces and automatic failure screenshots are disabled because they can contain credentials, codes, cookies or member data. The container and temporary TLS files are removed on shutdown. No real mailbox delivery or live Meta call is tested.

The authentication suite uses independent browser contexts and synthetic members. Its checks cover signup and verification, login/session restoration/logout, member-owned events, cross-member access denial, an inventory/request/HOLD/confirmation path and withdrawal eligibility. A cancellation after confirmation is not handover completion. The exact newly executed scope and failures must be recorded separately; an older run is not current evidence.

```bash
npm run test:demo  # Complete owned-data demo, verified in replay.
npm test          # Authentication checks, followed by the full-demo gate.
```

The default command runs the complete authenticated HTTPS owned-data scenario; no demo is skipped. `demo.spec.ts` and the separate candidate suite share every assertion in `demoScenario.ts`: giver-owned event and photo inventory, two authenticated receivers racing for the last rack, exactly one HOLD, a visible enabled loser alternative, receiver confirmation, giver photo handover, HANDED_OVER and event-filtered statistics matching the API. ENGLISH_RECORDINGS_READY was cleared after actual replay candidate success, then `npm test` passed against the normal product jar.

The default completed run includes TypeScript, six auth browser checks, the full demo and ten overlap/helpers. Candidate runs also completed both controlled losing branches (simultaneous finalization rejection and stock zero after the winner commits), plus a natural replay race. The test-only scheduler controls timing without altering responses, stock or ownership; the default product run uses no scheduler. Replay latency attachments contain only timing numbers, never auth payloads. These are replay timings, not live-model measurements.

## TypeScript and IDE checks

Run `npm ci` in `e2e/`, then `npm run typecheck`. This package declares its own TypeScript and Node type dependencies. Its `tsconfig.json` covers the tests, stack, reporter and Playwright configuration with modern ECMAScript modules, Node types and DOM iteration types. IDEs can use this committed project configuration instead of inferred defaults or unrelated frontend options. The default `npm test` runs this type check before browser tests. No frontend installation or command-line type-root override is required for E2E type checking.

TypeScript configuration is separate from actual replay fixture matching. A successful type check does not establish full-demo runtime success.

## Recording prerequisite

The existing recorded allocation requests contain a seeded display rack (`itemId: 1`, Korean label), its stock and Korean tool feedback. Member-owned inventory has different item identifiers. English system prompts, descriptions and tool feedback also change the exact request hash. `FixtureStore` hashes method, path and canonical request JSON, including tool results. These are concrete request mismatches; changing a lookup key or deleting request fields would invalidate replay evidence.

Original recordings remain unchanged. Separately approved real Meta recordings now cover the owned-data requests; fixture responses were not translated or fabricated. The exact race input is `We need one display rack. We will collect it in a van.` Generated purpose and allocation explanations are checked for English. The original WAV remains Korean, so this does not validate English speech.

For future fixture preparation, `npm run test:demo:candidate` requires a private `AFTERBOOTH_REPLAY_CAPTURE_DIR` and backend `prepareReplayTools`. Select `AFTERBOOTH_REPLAY_RACE_BRANCH=simultaneous-reject|stock-0-after-commit` for deterministic branches, or `natural` for a key-free unscheduled check. The test-only application always runs REPLAY and captures missing requests, never provider responses. See [ENGLISH_REPLAY](../docs/ENGLISH_REPLAY.md) for the exact safe recording procedure and budget gates. The two-branch paid runner does not authorize paid natural-branch extensions.

## Historical evidence

Issue #12's unauthenticated, seeded-organization scenario used HTTP and Korean UI. Its past measurements and successful race/alternative assertions concern that historical code only. PR #76's earlier authentication report described three checks, including confirmation followed by cancellation; it did not establish completed handover, current English replay, password recovery, or real mail delivery. The earlier manual PowerShell setup proposal was not a verified end-to-end run.

Do not run the old HTTP Compose recipe as an authenticated demo: Secure cookies and same-origin CSRF checks require HTTPS. Do not rely on seeded organization ownership. Current manual setup and submission status are documented in the root README and `docs/DEMO_SCRIPT.md`.

The current suite also checks wrong-current-password rejection, successful password change and email reset with fresh login, unverified login → resend → verification, and a simulated HTTP refresh outage followed by **real server logout** and recovery. The outage is injected by Playwright; it does not establish real database outage recovery. Desktop/mobile login overflow checks use blank forms. Screenshots are not saved by default. To opt into visual review, set `E2E_VISUAL_DIR` to a temporary directory outside the repository. Only the blank login and credential-free refresh notice views are saved there; no auth traces are retained. Inspect those images before sharing.

Still separate: expired/used reset-code and time-controlled 24-hour/30-day cases belong to the backend integration checks; the browser suite does not claim those. Full AI allocation, photo handover and statistics with exact member-owned English-output recordings are now verified in replay; live performance and English speech remain unverified.
