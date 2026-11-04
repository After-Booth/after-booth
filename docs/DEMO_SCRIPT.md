# English judging demo — readiness and recording outline

The current full authenticated English AI demo is **not verified**. Do not use historical Korean recordings as evidence of the current flow. No new live/record call is authorized by this document.

Use two synthetic verified members in separate HTTPS browser contexts: a giver owns the event and inventory; a receiver owns the pickup request and reservation. All displayed times use Asia/Seoul (KST). Keep credentials, email addresses, codes, cookies and tokens out of the recording.

| Time | Screen and action | English narration |
|---|---|---|
| 0:00–0:15 | Giver: own event, upload the approved inventory photo | “Turn leftover event equipment into usable inventory before teardown.” |
| 0:15–0:45 | Receiver: enter the exact approved request input and save | “Tell AfterBooth what the next event needs and which vehicle will collect it.” |
| 0:45–1:20 | Show allocation, constraints and an alternative | “Meta AI proposes a match; the server checks quantity, vehicle size and pickup time.” |
| 1:20–1:45 | Receiver: confirm the HOLD | “The receiver confirms the temporary hold. AI cannot confirm it for them.” |
| 1:45–2:15 | Giver: upload the approved handover photo; settle any mismatch using giver actions | “A photo recount supports handover. The receiver controls reservation cancellation.” |
| 2:15–2:40 | Show completed handover and reuse statistics | “Completed handovers become measurable reuse.” |

Before recording, prepare approved English AI fixtures for member-owned data, run `npm --prefix e2e test`, and verify the entire path through HANDED_OVER and statistics. A successful confirmation followed by cancellation is insufficient. A two-receiver stock race needs an additional independent receiver context; assert one HOLD winner and an actual alternative for the loser. Do not claim this outline was executed.

The existing WAV sample is Korean. Use it only with its correct language description. An English voice claim requires a verified English audio sample and an approved transcription/structuring/allocation recording. Presenting an unverified outline or an old video does not make submission ready. Human screen review, final video review, links, submission form access and actual external submission remain separate checks.
