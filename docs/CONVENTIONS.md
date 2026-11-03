# Conventions

How we write code, commits and pull requests in AfterBooth. Agents and humans follow the same rules.

## Language

Everything in the repository is English. Only text that Korean users see is Korean.

| What | Language |
|---|---|
| Docs, harness files (`AGENTS.md`, agents, skills), README, submission text, demo script | English |
| Commit messages, PR titles and descriptions, issues, review comments | English |
| Code identifiers, code comments, test names | English |
| Product UI text and user-facing error messages (`ErrorCode` messages) | Korean |
| Chat between teammates and with Claude | Anything |

Pushed history is never rewritten to change language.

## Writing issues, PRs and reviews

The same text is read by teammates skimming GitHub and by agents parsing it. Write for both.

1. **Conclusion first.** The first line under the title answers "what is true when this is done" (issue), "what changed and why" (PR) or "merge or not, and why" (review). A reader who stops there knows enough.
2. **Title = outcome.** Issue: what someone can do ("Receiver holds an alternative item"). PR: `<type>(<scope>): <outcome>`. Not the activity ("Work on allocation").
3. **Title, summary and link agree.** The PR title, its "In one sentence" and the linked issue's goal describe the same change. If the work grew, split it or retitle; do not let them drift.
4. **Fixed headings, short lines.** Keep the template headings exactly; agents find sections by them. One idea per line, tables over paragraphs.
5. **Everything is traceable.** Issues name their sources (SPEC section, contract operations, ERD tables, decisions). PRs say `Closes #N`, list decisions used, and name the proof for each acceptance criterion. Review findings have IDs (R1, R2) that the author answers by ID.

CI (`pr-description`) checks the structure, and re-runs whenever the title or description is edited: PR title format, `Closes #N`, a filled "In one sentence", filled Trace fields, and that the title's type equals the `Type:` field. Whether the words make sense is the reviewer's first checklist item.

## Git workflow

1. Every piece of work starts from an issue.
2. Branch from `main`: `<type>/<issue>-<short-slug>`, e.g. `feat/12-conditional-hold`, `fix/31-hold-expiry-timezone`.
3. Open a PR early. The PR description says `Closes #<issue>`.
4. `main` is protected: PR required, no force-push. PRs are **squash-merged**, so the PR title becomes the commit on `main`.
5. The branch is deleted after merge.

Parallel work in one clone uses worktrees, never `git switch` in the shared folder:

```bash
git worktree add .claude/worktrees/12-conditional-hold -b feat/12-conditional-hold
```

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/). Checked by `.githooks/commit-msg`.

```
<type>(<scope>): <summary in imperative mood, lower case, no period>

<optional body: what and why, wrapped at 72>

Refs #12
```

- Subject ≤ 72 characters, English only
- `type`: `feat` `fix` `refactor` `perf` `test` `docs` `build` `ci` `chore` `revert`
- `scope` (optional): `inventory` `intake` `request` `allocation` `handover` `stats` `ai` `contract` `frontend` `e2e` `harness` `infra`
- Breaking change: `feat(contract)!: rename reservation status values`

Good:

```
feat(allocation): hold quantity with conditional update
fix(intake): retry spark once when schema validation fails
test(allocation): cover 100 concurrent holds on 2 remaining items
```

Bad: `update code`, `fix bug`, `WIP`, `feat: 수량 차감 추가`

## Pull requests

- One issue, one PR. Keep it reviewable (aim for < 400 changed lines excluding generated files).
- Contract changes (`contract/`) go in their own PR or their own first commit, and are recorded in `docs/DECISIONS.md`.
- Fill in the PR template. Paste the verification commands you actually ran and their results.
- Before requesting merge: Claude `reviewer` agent and Codex review are both done (see [REVIEW_GUIDE.md](REVIEW_GUIDE.md)).
- CI must be green. The PR title is checked like a commit subject, because squash merge makes it the commit on `main`.

### CI checks

| Check | What it runs |
|---|---|
| `backend` | `./gradlew build`: compile, spotlessCheck, every test including Testcontainers |
| `frontend` | `npm ci`, then build (regenerates the client from the contract), test, lint, format:check |
| `hygiene` | secret scan of changed files; no `CLAUDE.md` next to `AGENTS.md` |
| `pr-description` | PR title as an English Conventional Commit; description follows the PR template (`scripts/check-pr-body.mjs`); re-runs on edit |

CI runs with `AI_MODE=replay` and no Meta key.

## Backend code

### Package layout

```
com.afterbooth
├── global/                 # cross-cutting: error handling, config, time
│   └── error/              # ErrorCode, BusinessException, GlobalExceptionHandler
├── ai/                     # AiGateway and Meta Model API clients (ai-pipeline only)
└── <domain>/               # inventory, intake, request, allocation, handover, stats
    ├── controller/         # HTTP only: validate input, call one service, map to DTO
    ├── service/            # use cases and transactions
    ├── domain/             # entities, value objects, enums, domain rules
    ├── repository/         # Spring Data interfaces and custom queries
    └── dto/                # request/response records
```

Tables follow [`ERD.md`](ERD.md). Migrations are named `V<yyyyMMddHHmm>__<what>.sql` so two branches never pick the same version.

These rules are enforced by `backend/src/test/java/com/afterbooth/ArchitectureTest.java`:

- Controllers do not touch repositories or entities.
- A domain never touches another domain's `repository` or `controller`. Call its `service`, and reference other aggregates by ID (`Long itemId`), not by entity field.
- Domain packages have no dependency cycles.
- HTTP clients are restricted to the AI package and the explicit `member.mail` delivery adapter. Only AiGateway calls Meta.
- Code does not call `now()` on `java.time` types. Inject `Clock` and use `now(clock)`.
- No field injection. Use constructor injection with `final` fields.
- Entities have no public setters.
- Only `com.afterbooth.ai` talks to the Meta Model API.

### Object-oriented design

- **Behavior lives with data.** Rules about an entity belong in the entity: `item.hold(qty)` and `reservation.confirm()`, not `if (status == HELD) reservation.setStatus(CONFIRMED)` in a service.
- **State transitions are methods.** Each transition checks that it is allowed and throws `BusinessException` otherwise.
- **Value objects for concepts with rules**, e.g. `Dimensions`, `VehicleType`. Prefer records and enums.
- **Services orchestrate. They do not decide.** A service loads, calls domain methods, saves and publishes. If a service method has branching business logic, move it into the domain.
- **Small public surface.** Package-private by default. Make a class or method `public` only when another package needs it.
- **No speculative abstractions.** Add an interface only with two implementations or a test seam that needs it. `AiGateway` is one such seam.
- **Immutable DTOs.** Requests and responses are records. Never expose entities in responses.
- **Errors are typed.** Throw `BusinessException(ErrorCode.X)`. The handler maps it to `{code, message}`. No `RuntimeException("...")` strings.
- **Time comes from a `Clock` bean**, never from `LocalDateTime.now()` directly, so tests can control it.

Templates to copy: [`docs/templates/backend/`](templates/backend/).

## Frontend code

```
frontend/src
├── api/generated/          # orval output, never edited or committed
├── components/             # shared presentational components
├── features/<feature>/     # one folder per screen: intake, request, board
│   ├── <Feature>Screen.tsx
│   ├── use<Feature>.ts     # data fetching and state for the screen
│   └── <Feature>Screen.test.tsx
└── lib/                    # small framework-free helpers
```

- The generated client throws `ApiError` (`src/api/http.ts`) for any non-2xx response, with the server's `code` and Korean `message`. Catch it in the hook and show `message`.
- Screens call the generated client through a hook. Components receive props and render. No `fetch` in components.
- TypeScript strict, no `any`. Types come from `api/generated/model`.
- Tailwind utility classes only. No CSS files per component.
- Every screen handles loading, error and empty states.

Templates to copy: [`docs/templates/frontend/`](templates/frontend/).

## Comments

Write a comment only when the code cannot say it:

- **Why** a non-obvious choice was made, e.g. `// conditional UPDATE: row lock is cheaper than SELECT FOR UPDATE under 100 concurrent holds`
- A constraint from outside the code, e.g. a Meta API limit or a hackathon rule

Do not write:

- Comments that repeat the code (`// save item`), section banners, or empty Javadoc
- Commented-out code
- TODOs without an issue number (`// TODO(#42): ...` is fine)

## What not to write

- Do not implement features outside `docs/SPEC.md`. Maps, general notifications and payments remain out of scope. D-076 explicitly authorizes #65 signup/common security/mail implementation now; the original #63 restriction applied to design only. Login/session, password management, withdrawal and existing-domain authorization remain later work.
- Unused parameters, unused config, "just in case" utilities.
- Generic base classes (`BaseService<T>`) or deep inheritance. Prefer composition.
- Defensive null checks for values that validation already guarantees.

## Tests

- Domain rules: plain JUnit tests on entities and value objects, without Spring.
- Services with real persistence and concurrency: `@SpringBootTest` + Testcontainers. No H2.
- Controllers: `@WebMvcTest` for validation and error mapping.
- Test names say the behavior in English: `holdFailsWhenQuantityIsNotAvailable`, `'shows counts per category after upload'`.
- Tests never call the Meta API. `AI_MODE=replay` with fixtures.

## Issue #63 documentation and contract scope

The user's latest instruction requires the documentation and comments written for #63 to be in English, superseding the earlier request for Korean operating documents. Preserve OpenAPI identifiers, code conventions and Korean product UI/error messages. This task covers local changes and verification; the user subsequently authorized local commits after a sensitive-data check. Pushes, pull requests and issue closure remain out of scope. For #63 only, the user explicitly requested one combined local commit after canceling the earlier two commits and adding the omitted password-change specification. This overrides the contract-only split rule for this task; retain Conventional Commit formatting.
