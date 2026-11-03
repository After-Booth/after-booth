import { defineConfig } from '@playwright/test';

// Authentication flows run against the jar over local HTTPS (issue #70). The spec starts the
// backend itself, so Chromium pins only that run's certificate instead of ignoring TLS errors.
export default defineConfig({
  testDir: './tests',
  testMatch: 'auth.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 300_000,
  expect: { timeout: 30_000 },
  use: {
    baseURL: 'https://localhost:5443',
    trace: 'off',
    screenshot: 'off',
  },
  reporter: [['list']],
});
