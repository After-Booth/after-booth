# AGENT_MISTAKES

Only agent mistakes that were actually reproduced and confirmed. No speculative prevention rules. Read this before implementing.

## Confirmed on finplay, on this same PC (2026-07 to 08)

1. **Never switch branches in a shared folder.** Files another session was editing disappeared. Work on other branches only in worktrees.
2. **Check for other running builds before starting Gradle.** Overlapping builds in one folder break each other. `:test FAILED` with zero failed tests means a build collision. Do not just re-run: `gradlew --stop` → confirm the java processes are gone → run alone.
3. **Run Testcontainers from the PowerShell tool.** From the Git Bash tool every test failed because the Docker socket could not be found.
4. **Do not habitually run the full test suite.** It has died from running out of memory. Normally run compile + spotlessCheck + affected tests with `--tests`.

## Confirmed on AfterBooth

| Date | Mistake | Fix |
|---|---|---|
| 2026-10-30 | A heredoc in the Bash tool collapsed `\\` into `\`, breaking a JS regex | Write files containing backslashes with the Write tool |
| 2026-10-30 | A commit subject was 74 characters and the commit-msg hook rejected it | Keep subjects ≤ 72 characters before committing |
| 2026-10-30 | A command that only mentioned `.env` (to check whether it exists) was blocked by the guard hook | Do not reference `.env` in shell commands at all; ask the human |
| 2026-10-31 | Spring's `RestClient` multipart writer streamed the Transcribe body chunked; local stub tests passed, the real API answered 400 "Malformed multipart body" (#4) | Send multipart as one buffered `byte[]` with a Content-Length, and make stub tests assert the wire format, not just the parsed parts |
| 2026-10-31 | With both `CLAUDE.md` and `AGENTS.md` at the root, Muse Code ignored `CLAUDE.md` and never loaded the hard rules | Keep one rulebook, `AGENTS.md`. Never add `CLAUDE.md` or `.claude/CLAUDE.md` (CI `hygiene` fails) |
| 2026-10-31 | PR #30 initially mapped saved idempotency responses only as objects and constrained accepted handover quantities to COMPLETED rows | Cover array responses and accepting a MISMATCH row in PostgreSQL integration tests; compare every response shape and resolution rule with the contract |
| 2026-10-31 | PR #34's vehicle tests injected cargo settings, hiding that every default HOLD returned 500; PostgreSQL HTTP tests without those overrides reproduced it | Test the shipped configuration as well as fixture overrides; provide validated demo defaults while retaining vehicle checks |
| 2026-10-31 | New Korean literals in Python source piped through Windows PowerShell became question marks during PR #38 fixes | Use a Unicode-safe patch tool for Korean literals and inspect the resulting file |
| 2026-11-01 | #9 compared first and replayed idempotency responses as strings; PostgreSQL JSONB changed object key order and whitespace, failing an otherwise equivalent response | Compare parsed JSON and response status, while asserting the replay action is never executed |
| 2026-11-01 | #9 excluded every already-held item from alternatives, hiding eligible remaining stock after a partial HOLD and blocking same-page top-up (R5) | Offer eligible remaining stock up to the outstanding request quantity, including the held item; cover model failure, manual top-up and over-cap rejection in PostgreSQL and the mounted screen |
| 2026-11-01 | #9 sent request IDs and complete reservation/timestamp data to Spark; stub-only allocation tests missed that every equivalent run would use different replay keys (PR #40 R1) | Separate model decision inputs from public persistence DTOs and test real gateway fixture lookup across changed request/reservation IDs and dates, with the recording server closed during replay |
| 2026-11-01 | #10 exact-text replacements silently missed controller and test anchors after concurrent formatting; compilation and PostgreSQL rollback assertions exposed the incomplete transaction wiring | Apply precise patches outside active builds, inspect the actual changed call sites and verify test names before reporting them as implemented |
| 2026-11-01 | #48 initially reused resolve-response boxes for the local photo and retained recovery preflight after an ambiguous new-key mutation; mounted regressions reproduced wrong-photo overlays and skipped stored photo responses | Bind overlays to upload responses and replay an already-sent ambiguous action with its retained key |
| 2026-11-01 | #48's final default-worker Vitest run overlapped a Docker image build and workers terminated with native and JavaScript out-of-memory errors | Finish the image build first and run the frontend suite with `--maxWorkers=1` on this PC |
| 2026-11-01 | #13 tests written inside the Codex sandbox never ran; four failed locally because MSW under vitest's jsdom cannot read a jsdom `File` from a multipart body, and a held response made undici abort the upload | Run every delegated test locally before trusting it; inspect file uploads by stubbing `fetch` or assert synchronously after the change instead of reading multipart bodies in MSW handlers |
| 2026-11-02 | #51 initially proposed a 1-second acquisition timeout for a new zero-idle lock pool; the unchanged twelve-preparation PostgreSQL regression timed out creating the twelfth connection with eleven active | Validate cold connection growth as well as maximum pool capacity; agree any timeout adjustment with the user instead of warming the test or weakening its concurrency assertion |
| 2026-11-03 | Adding authentication error responses to existing list APIs in #63 made Orval return types include an error union and broke the frontend build. A global forceSuccessResponse override then conflicted with existing status checks | Generate success-only return types for the four list operations that use the HTTP mutator's thrown errors, and verify contract generation and the frontend build/test suite |
| 2026-11-03 | #63 originally specified email-based password reset but omitted authenticated current-password change from draft issue 5, despite reporting the specification complete | Cross-check draft scope against SPEC, OpenAPI operations and follow-up references before completion; add changePassword and verify its contract/client generation separately from runtime tests |
