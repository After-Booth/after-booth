import { defineConfig } from "@playwright/test";

// Candidate verification shares every assertion with the gated full demo.
export default defineConfig({
  testDir: "./candidate",
  fullyParallel: false,
  workers: 1,
  timeout: 600_000,
  expect: { timeout: 30_000 },
  use: { baseURL: "https://localhost:5443", trace: "off", screenshot: "off" },
  reporter: [["./privacyReporter.ts"]],
});
