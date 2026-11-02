# ERD — AfterBooth data model

The database shape of record. It is derived from `contract/openapi.yaml` and `docs/SPEC.md`; if they disagree, fix this file in the same PR as the contract change. Entities in code follow `docs/CONVENTIONS.md` (behavior on entities, other aggregates by ID).

```mermaid
erDiagram
    organization ||--o{ event : runs
    event ||--o{ intake_draft : "photographed at"
    intake_draft ||--o{ intake_draft_line : has
    event ||--o{ item : offers
    organization ||--o{ item : gives
    intake_draft |o--o{ item : "confirmed into"
    organization ||--o{ pickup_request : requests
    pickup_request ||--o{ reservation : "held by"
    item ||--o{ reservation : reserves
    reservation ||--o{ reservation_transition : logs
    reservation ||--o{ handover_log : "counted in"

    organization {
        bigint id PK
        text name
    }
    event {
        bigint id PK
        bigint organization_id FK "giver"
        text name
        text venue
        timestamptz teardown_deadline
    }
    intake_draft {
        bigint id PK
        bigint event_id FK
        text status "PROCESSING|READY|FAILED|CONFIRMED"
        text stage "ESTIMATE|SEGMENT (runs in that order), nullable"
        text failure_message "nullable"
        timestamptz created_at
    }
    intake_draft_line {
        bigint id PK
        bigint draft_id FK
        text category "DraftCategory"
        text label
        int count
        jsonb boxes "BoundingBox[]"
        int width_cm "nullable"
        int depth_cm "nullable"
        int height_cm "nullable"
        text condition
        numeric confidence
        boolean needs_review
    }
    item {
        bigint id PK
        bigint event_id FK
        bigint organization_id FK "giver"
        bigint intake_draft_id FK "nullable, null for seed items"
        text category "ItemCategory"
        text label
        int width_cm
        int depth_cm
        int height_cm
        text condition
        int total_qty
        int available_qty "CHECK 0 <= available_qty <= total_qty"
        timestamptz pickup_deadline
        text vehicle_requirement "nullable"
        text conditions_transcript "nullable"
    }
    pickup_request {
        bigint id PK
        bigint organization_id FK "receiver"
        text category "ItemCategory"
        int quantity
        text vehicle_type "VehicleType"
        timestamptz pickup_deadline "nullable"
        text purpose "nullable"
        text transcript "nullable"
        timestamptz created_at
    }
    reservation {
        bigint id PK
        bigint request_id FK
        bigint item_id FK
        bigint organization_id FK "receiver, copied from request"
        int quantity
        int handed_over_qty "nullable, set when HANDED_OVER"
        text status "HELD|CONFIRMED|HANDED_OVER|RELEASED|EXPIRED"
        timestamptz hold_expires_at
        timestamptz pickup_deadline "copied from item"
        timestamptz created_at
    }
    reservation_transition {
        bigint id PK
        bigint reservation_id FK
        text from_status "nullable for the first HELD"
        text to_status
        text actor "AGENT|USER|SCHEDULER|HANDOVER"
        timestamptz at
    }
    handover_log {
        bigint id PK
        bigint reservation_id FK
        int expected_qty
        int counted_qty "Spark count, SAM-segmented (D-032), always present"
        int accepted_qty "set at submit on a match, or by resolve on the newest MISMATCH row"
        text status "COMPLETED|MISMATCH"
        jsonb boxes "one per counted item: SAM box when segmented, else Spark box"
        timestamptz created_at
    }
    idempotency_record {
        uuid idempotency_key PK
        text fingerprint "method + path + body hash"
        text state "IN_PROGRESS|DONE"
        int response_status "nullable"
        jsonb response_body "nullable"
        timestamptz created_at
    }
```

## Rules the schema must keep

| Rule | Where it is enforced |
|---|---|
| `available_qty` never goes below 0 or above `total_qty` | `CHECK` constraint + conditional `UPDATE ... WHERE available_qty >= :q AND :q > 0` |
| `available_qty = total_qty − Σ(HELD, CONFIRMED quantity) − Σ(HANDED_OVER handed_over_qty)` | Every status change and its quantity effect happen in one transaction |
| A reservation's status changes only along the table in `ReservationStatus` (contract) | `Reservation` entity methods + a conditional `UPDATE ... WHERE status = :expected` so confirm and expiry cannot both win |
| Each request's active quantity ≤ `pickup_request.quantity` | `SELECT ... FROM pickup_request WHERE id = :id FOR UPDATE` first, then sum active reservations and insert, all in one transaction. Both `holdItem` and the agent's `holdQuantity` take this lock, so concurrent holds on different items for one request are serialized |
| One allocation run per request while it has an active reservation | Checked inside the same `pickup_request` row lock (REQUEST_ALREADY_HELD), so a concurrent second run for the same request gets 409 |
| A handover completes only after a completed photo recount: Spark completed, and SAM 3.1 completed unless Spark counted zero (a failed call writes no row) | `resolveHandover` requires the newest `handover_log` row for the reservation to be MISMATCH; `counted_qty` is NOT NULL |
| Same Idempotency-Key never runs twice | `idempotency_record` row inserted first (`IN_PROGRESS`), response stored on `DONE` |

## Notes

- **Reuse stats are a query, not a table.** `getReuseStats` sums `reservation.handed_over_qty` for HANDED_OVER reservations, grouped by item category and event, and `handed_over_qty × width_cm × depth_cm × height_cm` of their items as liters, rounded down (D-064). SPEC's "ReuseStat" is this read model.
- **Allocation runs are not stored.** A same-key retry replays the response kept in `idempotency_record`; holds are re-read from `reservation`.
- **Labels are not stored for lookups.** `item.label` is written from the category table in the contract (`ItemCategory`) at confirm time, and stats labels use the same table.
- **Times are `timestamptz`.** The API returns them with the +09:00 offset (`docs/DECISIONS.md` D-019).
- **Enums are `text` columns** mapped with `@Enumerated(EnumType.STRING)`, so values match the contract strings exactly.
- **Seed data** (organizations, one event, demo items) lives in a Flyway migration so every environment, including Testcontainers, starts the same.

## Migrations

- Version with a timestamp so parallel branches never collide: `V202610311200__create_core_tables.sql` (`V` + `yyyyMMddHHmm`).
- #3 creates every table above in one migration. Later changes add new migrations; merged migrations are never edited.
