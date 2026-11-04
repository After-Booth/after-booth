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
npm run test:demo  # Full-demo readiness gate: currently fails explicitly.
npm test          # Authentication checks, followed by the full-demo gate.
```

The default command cannot report success while silently skipping the full demo. `demo.spec.ts` now defines an authenticated HTTPS scenario with three synthetic verified members in separate contexts: a giver creates its own event and photo inventory; two receivers request and race for the owned rack; the winner confirms; the giver completes photo handover; event-filtered statistics are compared with API results. It raises an explicit prerequisite failure until approved recordings exist for this exact scenario. This rewritten scenario has passed strict TypeScript checking and test discovery, but has not been successfully executed. On a future completed replay run, its latency attachment will contain only photo-result, concurrent-race, giver-handover and total scenario durations; these are replay timings, not live Meta measurements. Do not remove the gate merely because authentication checks pass.

## TypeScript and IDE checks

Run `npm ci` in `e2e/`, then `npm run typecheck`. This package declares its own TypeScript and Node type dependencies. Its `tsconfig.json` covers the tests, stack, reporter and Playwright configuration with modern ECMAScript modules, Node types and DOM iteration types. IDEs can use this committed project configuration instead of inferred defaults or unrelated frontend options. The default `npm test` runs this type check before browser tests. No frontend installation or command-line type-root override is required for E2E type checking.

TypeScript configuration fixes are separate from the missing English replay recordings described below. A successful type check does not establish full-demo runtime success.

## Recording prerequisite

The existing recorded allocation requests contain a seeded display rack (`itemId: 1`, Korean label), its stock and Korean tool feedback. Member-owned inventory has different item identifiers. English system prompts, descriptions and tool feedback also change the exact request hash. `FixtureStore` hashes method, path and canonical request JSON, including tool results. These are concrete request mismatches; changing a lookup key or deleting request fields would invalidate replay evidence.

Original recordings remain unchanged. Preparing English fixtures requires a separately approved Meta recording with the exact synthetic inputs and owned inventory used by the new demo, including the additional rack intake. The proposed English race input is `We need one display rack. We will collect it in a van.` The original Korean WAV remains correctly identified as Korean input, while generated purpose and allocation explanations must be English. Remove `ENGLISH_RECORDINGS_READY = false` only after the recording prerequisites and request hashes have been reviewed. Synthetic unit/integration responses must be identified as synthetic and cannot stand in for that recording. The original voice sample is Korean; it does not validate an English voice demo.

## Historical evidence

Issue #12's unauthenticated, seeded-organization scenario used HTTP and Korean UI. Its past measurements and successful race/alternative assertions concern that historical code only. PR #76's earlier authentication report described three checks, including confirmation followed by cancellation; it did not establish completed handover, current English replay, password recovery, or real mail delivery. The earlier manual PowerShell setup proposal was not a verified end-to-end run.

Do not run the old HTTP Compose recipe as an authenticated demo: Secure cookies and same-origin CSRF checks require HTTPS. Do not rely on seeded organization ownership. Current manual setup and submission status are documented in the root README and `docs/DEMO_SCRIPT.md`.

The current suite also checks wrong-current-password rejection, successful password change and email reset with fresh login, unverified login → resend → verification, and a simulated HTTP refresh outage followed by **real server logout** and recovery. The outage is injected by Playwright; it does not establish real database outage recovery. Desktop/mobile login overflow checks use blank forms. Screenshots are not saved by default. To opt into visual review, set `E2E_VISUAL_DIR` to a temporary directory outside the repository. Only the blank login and credential-free refresh notice views are saved there; no auth traces are retained. Inspect those images before sharing.

Still separate: expired/used reset-code and time-controlled 24-hour/30-day cases belong to the backend integration checks; the browser suite does not claim those. Full AI allocation, photo handover and statistics with current member-owned English recordings remain unverified.
