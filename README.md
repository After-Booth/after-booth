# AfterBooth

Surplus booth equipment from festivals, exhibitions and pop-ups — display racks, storage bins, folding tables — gets thrown away because teardown deadlines are hours away. AfterBooth hands it to the next event before the deadline.

Built for the Meta Global AI Developer Hackathon (Indie track). Full write-up comes in Phase 3. Product spec (Korean): [`docs/SPEC.md`](docs/SPEC.md).

## Meta models in the product

| Model | Role |
|---|---|
| SAM 3.1 | Segments each item Muse Spark found (box prompts) for the overlay, and checks every counted item at handover |
| Muse Spark | Finds and counts items in a booth photo with type, size and condition, and recounts at handover; structures voice requests; allocation agent via function calling |
| Muse Voice Transcribe | Turns spoken pickup conditions into text |

AI only proposes. The server validates quantity, time and vehicle fit, and the agent can only create a HOLD.

## Run

Requirements: Docker only. No JDK, Node or Meta API key is needed.

```bash
docker compose up --build
```

The first build takes several minutes. When the log shows `Started AfterboothApplication`, open http://localhost:8080 for the Korean UI; the API is on the same port under `/api`. `GET http://localhost:8080/api/health` answers `{"status":"UP","aiMode":"replay"}`. Seed data is loaded on first start.

The compose file pins `AI_MODE=replay`, so this command never calls the Meta Model API and spends no credits, even when a local key file sets another mode. Replay answers from the recorded responses in `fixtures/`, which are baked into the image. Stop and delete the database with `docker compose down -v`.

Live mode makes real, paid calls. A human opts in explicitly after the credit check in AGENTS.md (hard rule 6), with the key file filled in from `.env.example`:

```bash
docker compose -f docker-compose.yml -f docker-compose.live.yml up --build
```

### Develop locally

Requirements: Docker Desktop, JDK 21, Node 22+.

```bash
cd backend && ./gradlew bootRun                  # starts the postgres service by itself
cd frontend && npm install && npm run dev        # or npm run dev:mock without backend
```

`AI_MODE=replay` (default) runs without network or credits using `fixtures/`.

### Reservation settings

Holds last `afterbooth.hold-ttl` (default `PT15M`), capped at the item's pickup
deadline. The scheduler checks expired holds and confirmed reservations past
pickup every `afterbooth.reservation-sweep-ms` (default `30000`). All decisions
use the injected `Clock`.

Vehicle checks use these default cargo envelopes in centimeters:

| Vehicle | Width × depth × height | Property prefix |
|---|---|---|
| Sedan | 120 × 80 × 60 | `afterbooth.vehicle-cargo.sedan` |
| SUV | 160 × 100 × 100 | `afterbooth.vehicle-cargo.suv` |
| Van | 240 × 140 × 190 | `afterbooth.vehicle-cargo.van` |
| 1-ton truck | 300 × 160 × 200 | `afterbooth.vehicle-cargo.truck-1t` |

These are application defaults for the demo, not manufacturer measurements or
a guarantee for a particular vehicle. Override `width-cm`, `depth-cm` and
`height-cm` under each prefix for deployment; invalid dimensions fail startup.
Checks allow axis-aligned rotation and enforce the item's minimum vehicle type.
Issue #8 owns the final cargo policy and detailed fit reason output.

## Development tools

Developed with Claude Code and Codex as coding agents, and Muse Code. Agent harness: [`AGENTS.md`](AGENTS.md) (one rulebook for every agent), [`.claude/`](.claude).
