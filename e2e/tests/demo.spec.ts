import { registerOwnedDemo } from "./demoScenario";

// Clear only after both candidate race branches complete using actual recordings.
const ENGLISH_RECORDINGS_READY = true;

registerOwnedDemo({
  readinessCheck() {
    if (!ENGLISH_RECORDINGS_READY) {
      process.stdout.write(
        "[demo readiness] BLOCKED: approved English recordings are missing; no live calls made.\n",
      );
      throw new Error(
        "Full authenticated English demo is BLOCKED: approved recordings for the exact owned-data scenario must pass candidate verification. No live call is authorized.",
      );
    }
  },
});
