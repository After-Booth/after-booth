# SPEC — AfterBooth

Product requirements (this is our PRD). The API contract is `contract/openapi.yaml` and the data model is `docs/ERD.md`; both are derived from this file.

A surplus equipment hand-off service for events. Equipment left over after festivals, exhibitions and pop-ups (display racks, storage bins, folding tables) goes to the next event or group **before the teardown deadline**.

## Hackathon constraints

- Meta Global AI Developer Hackathon, Indie track, team of two
- Submission deadline 2026-11-05 11:00 KST, team target the evening of 11/4
- Judging: Impact 30% / Use of Meta AI Tooling 25% / Demo 25% / Technical Depth 20%
- All AI in the product runtime uses the Meta Model API: **Muse Spark, SAM 3.1, Muse Voice Transcribe**. No Muse Image
- Development tools (Claude Code, Codex, Muse Code) are disclosed in the README
- To stay clear of discouraged categories (basic RAG, simple image analyzers), AI output always leads to inventory registration, allocation or handover

## Core principle

**AI proposes, the server decides.** The server validates quantity, time and vehicle constraints. The agent can create at most a HOLD; the user confirms.

## Features

### 1. Photo intake (giver)
- Booth photo → **Muse Spark** finds each item and returns its category, a box, the count per category, approximate size (cm) and condition, with a confidence score
- **SAM 3.1** segments every item from Spark's boxes (box prompts), giving the masks and tight boxes for the overlay. Optionally, a SAM count per category from a noun phrase cross-checks Spark's count
- An inventory draft is created → a person reviews, edits and confirms. Items with low confidence, a box SAM could not segment, or a SAM count that disagrees with Spark are marked "needs review" (D-029)

### 2. Spoken conditions
- "Teardown by 8, need a van" → recorded in the browser as 16 kHz mono WAV, up to 60 s (D-030) → **Voice Transcribe** → **Spark** structures it against a JSON schema (`pickupDeadline`, `vehicleType`, `quantity`, `purpose`)
- Returns both the raw transcript and the interpretation

### 3. Pickup request (receiver)
- By voice or text (e.g. "display racks for a weekend event, I only have a sedan")

### 4. Allocation agent
- Spark calls Spring domain tools through function calling: `searchCandidates`, `checkVehicleFit`, `holdQuantity`
- The server validates. When conditions do not match, Spark explains why and offers an alternative in Korean
  - e.g. "높이 180cm라 승용차 불가, 대신 접이식 테이블 3개 어떠세요?" ("180 cm tall, will not fit a sedan. How about 3 folding tables instead?")

### 5. Handover confirmation
- One photo at pickup → Spark counts the reserved category and SAM 3.1 segments each counted item from Spark's boxes → the count matches only if it equals the reserved quantity and SAM segmented every box; then the handover completes and the reused quantity is recorded, otherwise it is a mismatch for a person to settle (D-032)

## Domain

- Organization (seed), Event (seed), Item (category, size, condition, totalQty, availableQty, pickupDeadline, vehicleRequirement), PickupRequest, Reservation, HandoverLog, ReuseStat (a read model over handovers). Tables in `docs/ERD.md`
- Reservation states: `HELD → CONFIRMED → HANDED_OVER`, `HELD|CONFIRMED → RELEASED`, `HELD → EXPIRED`
- Quantity decrement: conditional UPDATE (`available_qty >= :q`); zero affected rows means failure
- Idempotency key on every request. Cancel restores quantity. A scheduler releases holds that expire or pass the teardown deadline
- Vehicle fit: cargo space constants per vehicle type (sedan, SUV, van, 1-ton truck) vs item size → result plus reason code

## Scope

- Minimum: one region, three categories (display rack, storage bin, folding table), direct pickup only, organization accounts as seed data
- Out of scope: maps, notifications, payments. Registration and authentication are managed separately under the #63 design scope below.

## Required tests (Testcontainers)

1. 100 threads request the last 2 → exactly 2 succeed
2. Quantity is restored after cancel or expiry
3. Repeating the same idempotency key never double-decrements
4. Forbidden state transitions are blocked
5. Agent tools cannot bypass server validation
6. Concurrent holds on different items for one request never exceed the request quantity
7. Confirm racing hold expiry: exactly one wins, quantity restored once

## Screens (Korean UI, mobile first)

1. **Capture and register**: capture with `<input capture>`, box overlay for each detected item, count per category, edit then confirm
2. **Pickup request**: record 16 kHz mono PCM with Web Audio (AudioWorklet) and encode the WAV in the browser (D-030), interpreted condition card, allocation proposal with "why it does not fit + alternative"
3. **Allocation board**: teardown deadline countdown, live remaining quantity (polling), concurrent request results

## Demo scenario

1. Take a photo → "storage bin 4" registered automatically (the recorded demo photo, `fixtures/README.md` §Demo scenario)
2. Request pickup by voice
3. Allocation proposal shown
4. Two browsers request the last display rack at the same time → one succeeds, the agent immediately offers the other an alternative
5. Handover completed
6. Reused quantity totaled

Step 1 must appear within the first 15 seconds of the demo video.

## Member authentication rollout scope (#63)

- Before hackathon submission, define authentication policies, API contracts and data structures only. Introduce the authentication runtime in follow-up work after submission and preserve the existing demo (D-069). OpenAPI 0.3.0 is a target contract, not evidence of current server implementation.
- Email/password registration → signup email verification → password login. Email verification confirms registration; it is not required for each login. Use the Resend API for verification and password-reset emails through the same delivery foundation.
- An ACTIVE member can change their password by confirming the current password (`changePassword`); email-based password reset remains a separate recovery flow. Success revokes all Access/Refresh sessions and outstanding reset challenges, clears member-linked CSRF sessions, and requires a fresh login. See D-075 and AUTH_DESIGN for permissions, request limits and concurrency rules.
- Immediately reject existing JWTs and revoke every Refresh Token when withdrawal commits. Reject withdrawal if either the giver or receiver has a HELD or CONFIRMED reservation. MISMATCH blocks withdrawal while its reservation remains CONFIRMED. HANDED_OVER, RELEASED, EXPIRED and historical mismatch records do not block withdrawal.
- The user selected **immediate personal-data deletion / 30 days of minimal transaction history after withdrawal / 30 days of re-registration blocking identifiers**. Retain only an email HMAC and allow the same email to register again after 30 days. A new account does not recover prior ownership (D-070).
- Each new member owns one personal organization. Do not assign existing unclaimed organizations, items, requests or reservations automatically. See [Member authentication design](AUTH_DESIGN.md) for API permissions, tokens, cookies, CSRF, email limits and deletion scope; contract/openapi.yaml defines the target APIs, and the #63 ERD extension defines their data structures.
- Implementation order: shared member schema → security/email foundation → APIs, domain authorization and frontend integration. Before rollout, use synthetic data to verify withdrawal/transaction races, immediate JWT rejection, 30-day boundaries, code/refresh reuse, authorization bypass attempts, and inventory/statistics after history deletion.
