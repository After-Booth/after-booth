# AGENTS.md — AfterBooth

The one rulebook for every coding agent (Claude Code, Codex, Muse Code) and for humans. Claude Code 2.1.277+ loads this file on its own. **Do not add a `CLAUDE.md`**: Claude Code would read it instead of this file, and Muse Code would warn that one of the two is ignored.

A service that hands leftover equipment from festivals, exhibitions and pop-ups to the next event or group before the teardown deadline.
Entry for the Meta Global AI Developer Hackathon, Indie track. The product spec of record is `docs/SPEC.md`.

- Submission deadline: **2026-11-05 11:00 KST** (11/4 21:00 ET). Team target: submitted by the evening of 11/4
- Judging weights: Impact 30 / Use of Meta AI Tooling 25 / Demo 25 / Technical Depth 20

## Commands

```bash
docker compose up -d postgres                 # local DB (port 15432)
cd backend && ./gradlew bootRun               # Windows: .\gradlew.bat (needs Docker)
cd backend && ./gradlew compileJava compileTestJava spotlessCheck   # fast gate
cd backend && ./gradlew test --tests "<pattern>"  # targeted tests
cd backend && ./gradlew spotlessApply          # Java format (required before commit)
cd frontend && npm run dev:mock               # frontend against MSW mocks
cd frontend && npm run build && npm test      # frontend gate (runs gen:api first)
```

## Hard rules

1. **All AI inside the product runs on the Meta Model API** (Muse Spark, SAM 3.1, Muse Voice Transcribe). No Muse Image. No Claude, OpenAI or other third-party models in the product runtime.
2. **AI proposes, the server decides.** The server validates quantity, time and vehicle constraints. The agent can create at most a HOLD; the user confirms.
3. **No analysis-only features.** AI output must lead to an action: inventory registration, allocation or handover (the organizers discourage basic RAG and simple image analyzers).
4. **Every AI call goes through the single `AiGateway`.** `AI_MODE=live|record|replay`. The default and all automated tests use replay. Model IDs, base URL and endpoints are configuration and **never guessed** — use only values confirmed in `https://dev.meta.ai/docs` and `GET /v1/models` (`spike/REPORT.md`).
5. **Only humans touch `.env`.** Agents neither read nor write it (blocked by a hook in Claude Code; a rule everywhere else). Apps and scripts read the key only through `--env-file=.env` or docker compose `env_file`.
6. **Credit gate.** Ask a human before any live/record call (`--env-file=.env`, `AI_MODE=live|record`); in Claude Code a hook asks. After the call, log the estimated cost in `docs/BUDGET.md`. STOP and report every $50 of cumulative spend.
7. **Contract first.** `contract/openapi.yaml` is the source of truth. Contract changes go in a separate commit that touches only `contract/`, recorded in `docs/DECISIONS.md`.
8. **No photos or audio with real faces or personal data in `fixtures/` or `spike/samples/`.**
9. **Re-run verification before claiming done, and write down the commands and results.** Keep anything not run under "Not verified". Never present a mock success as a real API check.
10. When a judgment call is needed, do not stop: decide reasonably and record it in one line in `docs/DECISIONS.md`. Ask a human only at STOP gates.
11. **Current product UI, user-facing errors and AI-generated explanations are English.** README and current judging/run guides are English; new repository records are English under the latest #17 instruction. Final chat reports may be Korean. Preserve historical records and original user input. Code comments, commits, PRs and issues retain their English conventions (`docs/CONVENTIONS.md`). The commit-msg hook checks commit subjects.
12. **Code follows `docs/CONVENTIONS.md` and `docs/templates/`.** Rules live in domain objects, services only orchestrate, controllers only do HTTP. No features outside the spec, no unused code, no comments that repeat the code. `ArchitectureTest` enforces the layering rules.

## Human checkpoints (STOP)

Run fully automated, but stop and report at these points only.

| Gate | When | Report |
|---|---|---|
| G0 | End of the Phase 0 spike | `spike/REPORT.md` summary and the list of decisions for humans |
| G$ | Right before a live/record call · every $50 cumulative | Purpose of the call, cumulative cost |
| G3 | End of Phase 3 (before submission) | How to run, demo results, self-score, remaining risks |

`git push`, merges into main and PR merges are always approved by a human (Claude Code: the permissions `ask` list).

## Workflow (GitHub)

1. All work starts from a GitHub issue. The owner is the issue assignee (namdongyeob / WookJaes). Both do backend and frontend.
2. Create a branch `<type>/<issue>-<slug>` (e.g. `feat/12-conditional-hold`) in a worktree.
3. Commit → `git push -u origin <branch>` → `gh pr create` (body says `Closes #N`, fill in the PR template).
4. Three reviews: Claude `reviewer` agent → Codex (`/codex:review --base main`, plus `/codex:adversarial-review` for risky changes) → teammate (optional, often Muse Code). Criteria in `docs/REVIEW_GUIDE.md`.
5. CI must be green: `backend`, `frontend`, `hygiene` (`ci.yml`) and `pr-description` (`pr-description.yml`) are required checks. Issues, PRs and reviews follow the templates and writing rules in `docs/CONVENTIONS.md` §Writing issues, PRs and reviews. main is protected (PR required, 0 approvals, squash merge only). A human merges.

## How we work

- The main session is the **orchestrator**. Feature work goes through the `feature` skill (`.claude/skills/feature/SKILL.md`) and is delegated to subagents. The main session only edits config and docs directly when it is 1–2 files.
- Five subagents (`.claude/agents/`): `backend-domain` / `ai-pipeline` / `frontend` / `reviewer` (read-only) / `demo-qa`. Follow the file ownership table below; one writer per file.
- Parallel streams are isolated in worktrees: `git worktree add .claude/worktrees/<name> -b feat/<name>`. Never switch branches in a shared folder.
- Do not trust a subagent's report alone. The main session checks the diff and test results itself.
- Start the reviewer fresh every time, so it does not inherit the implementer's assumptions.
- Codex is the second pair of eyes: `/codex:review` on every PR, `/codex:rescue` when stuck, `/codex:transfer` to hand a session over. In a teammate's session, `/codex:setup --enable-review-gate` turns on an automatic review before the session stops.
- If a problem blocks you for more than 30 minutes, hand it to `codex:rescue` once. If it is still stuck, mark it `BLOCKED` in `docs/TASKS.md` and move on.
- Update `docs/TASKS.md` at the end of every phase.
- When a mistake is reproduced and confirmed, add one line to `docs/AGENT_MISTAKES.md` (no speculative rules).

### File ownership

| Agent | Write scope |
|---|---|
| backend-domain | `backend/**` except the `com.afterbooth.ai` package, `backend/src/main/resources/db/migration/**` |
| ai-pipeline | `backend/src/main/java/com/afterbooth/ai/**` and its tests, `fixtures/**`, `spike/**` |
| frontend | `frontend/**` |
| demo-qa | `e2e/**`, `docs/DEMO_SCRIPT.md` |
| reviewer | none (read-only) |
| main (orchestrator) | `docs/**`, `contract/**`, root config, merges |

## Tool notes

- **Claude Code** (2.1.277 or later, so it reads this file): hooks in `.claude/hooks/` block `.env`, gate live calls and scan for secrets; subagents in `.claude/agents/`; the `feature` skill. Check with `/memory` that `AGENTS.md` is listed.
- **Codex**: role files in `.codex/agents/*.toml` only point at `.claude/agents/*.md`, which are the role definitions of record. Follow `.claude/skills/feature/SKILL.md` for feature work.
- **Muse Code**: log every task done with Muse Code in `docs/DECISIONS.md` with a `[Muse Code]` tag and keep a screenshot (judging: Use of Meta AI Tooling). Post reviews with `gh pr review <n> --approve|--request-changes --body-file <file>` in the `docs/REVIEW_GUIDE.md` §Review format, without editing files.
- **Without Claude hooks** (Codex, Muse Code, a plain terminal): the git hooks in `.githooks/` still run the secret scan and the commit-msg check; run `git config core.hooksPath .githooks` once per clone.

## Architecture

- `backend/`: Java 21, Spring Boot 4.1, `RestClient` to the Meta Model API behind `AiGateway` (D-037), PostgreSQL 17, Flyway, Testcontainers
  - Packages: `com.afterbooth.<domain>` (inventory, intake, request, allocation, handover, stats), shared code in `com.afterbooth.global`, AI in `com.afterbooth.ai`
  - Tables follow `docs/ERD.md`. Schema changes only through Flyway, versioned by timestamp (`V202610311200__create_core_tables.sql`) so parallel branches never collide. `ddl-auto=validate`
- `frontend/`: React 19 + Vite + TypeScript + Tailwind v4, English UI, mobile first. orval generates the API client and MSW mocks from the contract (`src/api/generated/`, not committed). Use the `frontend-design` skill for UI work.
- `contract/openapi.yaml`: API contract of record
- `fixtures/`: AI responses for replay. `spike/`: throwaway experiments and `REPORT.md`
- Development tools used (Claude Code, Codex, Muse Code) are disclosed in the README.

## Known pitfalls on this PC

Read `docs/AGENT_MISTAKES.md` first. In particular, run Testcontainers from the PowerShell tool (Git Bash has failed to find the Docker socket).

## Doc routing — what to read per task

Do not read every doc. Read only the row for your task. Every task starts with `AGENTS.md` + `docs/TASKS.md` + `docs/AGENT_MISTAKES.md` + the GitHub issue (`gh issue view <n>`).

Sources of truth, in order: `docs/SPEC.md` (what to build) → `contract/openapi.yaml` (API) → `docs/ERD.md` (tables) → the issue (scope and acceptance criteria). When they disagree, stop and fix the higher document first (contract and ERD changes go through `docs/DECISIONS.md`); never let code drift silently.

| Task | Also read |
|---|---|
| Backend domain | `docs/SPEC.md` §Domain and §Required tests, `contract/openapi.yaml`, `docs/ERD.md`, `docs/DECISIONS.md` D-018–D-020, `docs/CONVENTIONS.md` §Backend code, `docs/templates/backend/` |
| AI pipeline | `docs/SPEC.md` §Features, `contract/openapi.yaml` (intake, conditions, allocation, handover), `docs/ERD.md`, `spike/REPORT.md`, `docs/BUDGET.md`, `docs/DECISIONS.md` D-010, D-018 |
| Frontend screen | `docs/SPEC.md` §Screens and §Demo scenario, `contract/openapi.yaml`, `docs/DECISIONS.md` D-019, D-021, `docs/CONVENTIONS.md` §Frontend code, `docs/templates/frontend/` |
| Contract or ERD change | `contract/openapi.yaml`, `docs/ERD.md`, `docs/DECISIONS.md` |
| Review | `docs/REVIEW_GUIDE.md` (§Review format, §Re-review format, §Checklist), the PR description and its issue, `docs/SPEC.md`, `contract/openapi.yaml`, `docs/ERD.md` |
| E2E and demo | `docs/SPEC.md` §Demo scenario, `docs/BUDGET.md` |
| Commits, PRs, issues | `docs/CONVENTIONS.md` §Writing issues, PRs and reviews, §Git workflow, §Commit messages, §Pull requests; `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/` |
| Submission material | `README.md`, `docs/SPEC.md` §Hackathon constraints, `docs/REVIEW_GUIDE.md` §Checklist |

## Doc map

| Doc | Purpose |
|---|---|
| `docs/SPEC.md` | Product requirements (our PRD) and demo scenario (source of truth) |
| `contract/openapi.yaml` | API contract: endpoints, schemas, error codes, state rules |
| `docs/ERD.md` | Data model: tables, columns, invariants, migration naming |
| `docs/TASKS.md` | Checklist per phase, issue numbers, owners, BLOCKED |
| `docs/DECISIONS.md` | Decision log (including contract changes) |
| `docs/CONVENTIONS.md` | Language, writing rules, git workflow, commit rules, code design and comment rules |
| `docs/REVIEW_GUIDE.md` | Three-step review, review and re-review formats, checklist |
| `docs/templates/` | Backend and frontend code templates |
| `docs/BUDGET.md` | Meta API credit plan and ledger |
| `docs/AGENT_MISTAKES.md` | Reproduced agent mistakes |
| `spike/REPORT.md` | Phase 0 spike results (created in Phase 0) |
