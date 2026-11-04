import { defineConfig } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'https://localhost:5443';

export default defineConfig({
  testDir: './tests',
  testIgnore: 'auth.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 600_000,
  expect: {
    timeout: 30_000,
  },
  use: {
    baseURL,
    trace: 'off',
    screenshot: 'off',
  },
  reporter: [['./privacyReporter.ts']],
});
