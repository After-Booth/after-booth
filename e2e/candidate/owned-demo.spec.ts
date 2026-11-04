import { registerOwnedDemo } from "../tests/demoScenario";
const captureDirectory = process.env.AFTERBOOTH_REPLAY_CAPTURE_DIR;
if (!captureDirectory)
  throw new Error("Candidate requires a private synthetic request directory");
const branch =
  process.env.AFTERBOOTH_REPLAY_RACE_BRANCH ?? "simultaneous-reject";
if (
  branch !== "simultaneous-reject" &&
  branch !== "stock-0-after-commit" &&
  branch !== "natural"
)
  throw new Error("Invalid candidate race branch");
registerOwnedDemo({
  stackOptions: { captureDirectory, raceBranch: branch },
  requiredRaceBranch: branch === "natural" ? undefined : branch,
});
