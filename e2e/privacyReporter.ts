import type {
  FullResult,
  Reporter,
  TestCase,
  TestResult,
} from '@playwright/test/reporter';

// Browser errors may contain form values. Never serialize errors or attachments.
export default class PrivacyReporter implements Reporter {
  onStdOut(chunk: string | Buffer) {
    const diagnostic =
      '[demo readiness] BLOCKED: approved English recordings are missing; no live calls made.';
    if (chunk.toString().trim() === diagnostic)
      process.stdout.write(`${diagnostic}\n`);
  }
  onTestEnd(test: TestCase, result: TestResult) {
    process.stdout.write(`${result.status}: ${test.title}\n`);
    if (result.status === 'failed') {
      const safeStatus = result.error?.message?.match(
        /Registration status \d{3}/,
      )?.[0];
      if (safeStatus) process.stdout.write(`${safeStatus}\n`);
      if (
        result.error?.message?.includes(
          'Full authenticated English demo is BLOCKED:',
        )
      )
        process.stdout.write(
          'Full authenticated English demo is BLOCKED: approved owned-data English recordings are missing.\n',
        );
      const location = result.error?.stack?.match(
        /(?:auth|demo)\.spec\.ts:\d+:\d+/,
      )?.[0];
      if (location) process.stdout.write(`Failure location: ${location}\n`);
    }
  }
  onEnd(result: FullResult) {
    process.stdout.write(`Browser checks: ${result.status}\n`);
  }
}
