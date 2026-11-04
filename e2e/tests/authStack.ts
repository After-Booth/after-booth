import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { X509Certificate, createHash, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('../../', import.meta.url));
const KEYSTORE_PASSWORD = randomBytes(24).toString('hex');

export const AUTH_ORIGIN = 'https://localhost:5443';

export interface AuthStack {
  /** Base64 SHA-256 of the local certificate's public key, for Chromium's SPKI pin. */
  spki: string;
  /** Resolves with the next Fake mail code. The code is held in memory only and never printed. */
  nextCode(purpose: 'SIGNUP_VERIFY' | 'PASSWORD_RESET'): Promise<string>;
  stop(): Promise<void>;
}

function run(command: string, args: string[]): void {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`);
}

/**
 * Starts the built backend jar over local HTTPS with synthetic keys, the local-only
 * Fake mail gateway and AI replay. Fake codes are parsed from the child's stdout in
 * memory; nothing from that stream is logged or written to disk.
 */
export async function startAuthStack(
  options: { ownedDemo?: boolean; captureDirectory?: string; raceBranch?: string } = {},
): Promise<AuthStack> {
  const jar = path.join(REPO, 'backend', 'build', 'libs', 'afterbooth-0.0.1-SNAPSHOT.jar');
  const dist = path.join(REPO, 'frontend', 'dist');
  if (!fs.existsSync(jar)) throw new Error('Build the backend first: gradlew bootJar');
  if (!fs.existsSync(dist)) throw new Error('Build the frontend first: npm run build');

  const database = `afterbooth-e2e-${randomUUID()}`;
  const databasePassword = randomBytes(24).toString('hex');
  run('docker', [
    'run',
    '--detach',
    '--rm',
    '--name',
    database,
    '-e',
    'POSTGRES_DB=afterbooth',
    '-e',
    'POSTGRES_USER=afterbooth',
    '-e',
    `POSTGRES_PASSWORD=${databasePassword}`,
    '-p',
    '127.0.0.1::5432',
    'postgres:17-alpine',
  ]);
  let dir: string | undefined;
  let child: ChildProcess | undefined;
  const cleanup = async () => {
    if (child?.pid && child.exitCode === null && child.signalCode === null) {
      child.removeAllListeners('exit');
      const stopped = new Promise<void>((resolve) =>
        child!.once('exit', () => resolve()),
      );
      child.kill();
      await Promise.race([
        stopped,
        new Promise<void>((resolve) => setTimeout(resolve, 5000)),
      ]);
      if (child.exitCode === null && child.signalCode === null)
        child.kill('SIGKILL');
    }
    spawnSync('docker', ['stop', database], { stdio: 'ignore' });
    if (dir) fs.rmSync(dir, { recursive: true, force: true });
  };
  try {
    const mapped = spawnSync('docker', ['port', database, '5432/tcp'], { encoding: 'utf8' });
    const port = mapped.stdout.trim().match(/:(\d+)$/)?.[1];
    if (!port) {
      throw new Error('Disposable database port was unavailable');
    }
    const deadline = Date.now() + 60_000;
    while (
      spawnSync(
        'docker',
        ['exec', database, 'pg_isready', '-U', 'afterbooth'],
        { stdio: 'ignore' },
      ).status !== 0
    ) {
      if (Date.now() > deadline) {
        throw new Error('Disposable database did not become ready');
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }

    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'afterbooth-70-'));
    const keystore = path.join(dir, 'tls.p12');
    const certFile = path.join(dir, 'tls.cer');
    run('keytool', [
      '-genkeypair', '-alias', 'localhost', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
      '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-storetype', 'PKCS12',
      '-keystore', keystore, '-storepass', KEYSTORE_PASSWORD, '-keypass', KEYSTORE_PASSWORD,
      '-noprompt',
    ]);
    run('keytool', [
      '-exportcert', '-rfc', '-alias', 'localhost', '-keystore', keystore,
      '-storepass', KEYSTORE_PASSWORD, '-file', certFile,
    ]);
    const spki = createHash('sha256')
      .update(
        new X509Certificate(fs.readFileSync(certFile)).publicKey.export({ type: 'spki', format: 'der' }),
      )
      .digest('base64');

    const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const config = {
      server: {
        port: 5443,
        ssl: {
          enabled: true,
          'key-store': new URL(`file:///${keystore.replaceAll('\\', '/')}`).href,
          'key-store-type': 'PKCS12',
          'key-store-password': KEYSTORE_PASSWORD,
        },
      },
      spring: {
        profiles: { active: 'local' },
        docker: { compose: { enabled: false } },
        datasource: {
          url: `jdbc:postgresql://127.0.0.1:${port}/afterbooth`,
          username: 'afterbooth',
          password: databasePassword,
        },
        web: { resources: { 'static-locations': new URL(`file:///${dist.replaceAll('\\', '/')}/`).href } },
      },
      afterbooth: {
        mail: { 'fake-enabled': true },
        // Pins the request reference date so the recorded replay prompts match (docker-compose.yml).
        demo: { 'reference-date': '2026-10-31' },
        auth: {
          'allowed-origin': AUTH_ORIGIN,
          hmac: { 'current-version': 'synthetic', keys: { synthetic: randomBytes(32).toString('base64') } },
          jwt: {
            issuer: 'synthetic-issuer',
            audience: 'synthetic-audience',
            'current-kid': 'synthetic',
            'private-key': rsa.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
            'public-keys': {
              synthetic: rsa.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
            },
          },
        },
      },
    };

    const preparation = options.captureDirectory;
    if (preparation && (!options.ownedDemo || !path.isAbsolute(preparation)))
      throw new Error('Capture requires the isolated owned demo and a private absolute directory');
    const javaArgs = preparation
      ? ['-cp', fs.readFileSync(path.join(REPO, 'backend/build/replay-preparation.classpath'), 'utf8'), 'com.afterbooth.ai.ReplayPreparationApplication']
      : ['-jar', jar];
    child = spawn('java', javaArgs, {
      cwd: path.join(REPO, 'backend'),
      env: {
        ...process.env, SPRING_APPLICATION_JSON: JSON.stringify(config), AI_MODE: 'replay',
        META_API_KEY: '',
        ...(preparation ? {
          AFTERBOOTH_REPLAY_CAPTURE_DIR: preparation,
          AFTERBOOTH_REPLAY_RACE_BRANCH: options.raceBranch ?? '',
        } : {}),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stderr?.resume();

    const codes: Record<string, string[]> = { SIGNUP_VERIFY: [], PASSWORD_RESET: [] };
    const waiters: Array<{ purpose: string; resolve: (code: string) => void }> = [];
    let ready: () => void = () => {};
    let startupFailed: (error: Error) => void = () => {};
    let starting = true;
    const started = new Promise<void>((resolve, reject) => {
      ready = resolve;
      startupFailed = reject;
    });
    child.on('error', () => {
      if (starting) startupFailed(new Error('Backend process could not start'));
    });
    child.once('exit', (status) => {
      if (starting)
        startupFailed(new Error(`Backend exited early (${status})`));
    });
    let pending = '';
    child.stdout?.on('data', (chunk: Buffer) => {
      pending += chunk.toString('utf8');
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() ?? '';
      for (const line of lines) {
        const mail = /^(SIGNUP_VERIFY|PASSWORD_RESET) (\d{6})$/.exec(line);
        if (mail) {
          const waiter = waiters.findIndex((w) => w.purpose === mail[1]);
          if (waiter >= 0) waiters.splice(waiter, 1)[0].resolve(mail[2]);
          else codes[mail[1]].push(mail[2]);
        } else if ((line.includes('Started AfterboothApplication') || (preparation && /Started [A-Za-z]+ in /.test(line)))) {
          ready();
        }
      }
    });
    let startupTimer: ReturnType<typeof setTimeout>;
    try {
      await Promise.race([
        started,
        new Promise((_, reject) => {
          startupTimer = setTimeout(
            () => reject(new Error('Backend start timed out')),
            180_000,
          );
        }),
      ]);
    } finally {
      starting = false;
      clearTimeout(startupTimer!);
    }

    if (options.ownedDemo) {
      // This container was created above for this run. Never accept an external DB target.
      // Reset before any synthetic signup so IDs and available inventory are reproducible.
      run('docker', [
        'exec', database, 'psql', '-U', 'afterbooth', '-d', 'afterbooth',
        '-v', 'ON_ERROR_STOP=1', '-c',
        `BEGIN;
         DO $$ BEGIN
           IF EXISTS (SELECT 1 FROM member) THEN
             RAISE EXCEPTION 'Owned demo reset requires an unused disposable database';
           END IF;
         END $$;
         TRUNCATE organization RESTART IDENTITY CASCADE;
         COMMIT;`,
      ]);
    }

    return {
      spki,
      nextCode(purpose) {
        const queued = codes[purpose].shift();
        if (queued) return Promise.resolve(queued);
        return new Promise((resolve) => waiters.push({ purpose, resolve }));
      },
      async stop() {
        await cleanup();
      },
    };
  } catch (error) {
    await cleanup();
    throw error;
  }
}

export function uniqueEmail(): string {
  return `e2e-${randomUUID().slice(0, 8)}@example.test`;
}
