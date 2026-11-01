# AfterBooth Playwright E2E (issue #12)

Replays the full demo scenario against the real stack in `AI_MODE=replay`:
photo intake -> voice pickup request -> allocation proposal -> two-context race
for the last display rack -> winner confirms in the UI -> photo handover ->
reuse stats. Every step drives the real Korean UI; the API is used only for
setup guards and result verification, never to stand in for a user action.

## Stack

A dedicated compose project keeps parallel sessions untouched (own ports,
own volume, own network). Run from the repo root:

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml up --build -d
curl -s http://localhost:18080/api/health   # {"status":"UP","aiMode":"replay"}
```

Reset the demo database between runs (this project only):

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml up -d
```

Tear down everything this suite started:

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v
```

## Run

```bash
cd e2e
npm ci
npm run install-browsers   # Chromium (headless shell) only
E2E_RUN_TAG=run1 npx playwright test
```

Inputs come from `../spike/samples/` (byte-identical files) and the exact
race phrase in `tests/demo.spec.ts`; replay fixtures will not match anything
else. `E2E_BASE_URL` overrides the default `http://localhost:18080`.

## Evidence

- `test-results/<test>/latency-<tag>.json`: wall-clock latency per step plus the
  race overlap gaps and the executed race branch (`simultaneous-reject` or
  `stock-0-after-commit`). The console prints the same table. Each run wipes
  `test-results/`, so copy the file aside to keep several runs.
- Traces and screenshots are kept on failure only. Scan them for secrets
  before sharing: replay mode sends no keys, but check anyway.
