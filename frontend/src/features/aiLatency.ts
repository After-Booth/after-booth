// Expected seconds per AI step. Live calls measured in the #13 recording run on 2026-11-01:
// text structuring 1.4 s, allocation 7-10 s (3-4 tool turns of 0.9-3.9 s), handover recount
// 3.3 s (Spark 1.8 s + SAM 1.5 s). Intake and voice replayed earlier recordings, so they use
// spike/REPORT.md section 3 (Spark minimal 3.3 s p50 / 6.4 s max + SAM 1.9 s; Transcribe 2.6 s).
export const AI_EXPECTED_SECONDS = {
  intake: 8,
  voiceRequest: 5,
  textRequest: 2,
  allocation: 10,
  handover: 4,
} as const
