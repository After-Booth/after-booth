// Incremental synthetic-only capture -> review -> approved record -> replay verification.
// The product app always runs REPLAY; this script never removes a member work scope.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const repo = fileURLToPath(new URL('../', import.meta.url));
const session = process.argv[2];
const record = process.argv[3] === '--record';
if (!session || !path.isAbsolute(session) || !session.startsWith('/private/tmp/afterbooth17-full-replay-')) throw new Error('Use the private prepared synthetic session directory');
if (record && process.env.AFTERBOOTH_ENGLISH_REPLAY_APPROVED !== 'true') throw new Error('Separate paid approval required');
const capture = path.join(session, 'manifests');
const budget = path.join(session, 'budget');
const classpath = fs.readFileSync(path.join(repo, 'backend/build/replay-preparation.classpath'), 'utf8');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
const run = (command, args, cwd, env) => spawnSync(command, args, { cwd, env, stdio: 'inherit', timeout: 660000 });
const appEnv = { ...process.env, META_API_KEY: '', AFTERBOOTH_REPLAY_CAPTURE_DIR: capture };
const recorderEnv = { ...process.env, AFTERBOOTH_REPLAY_BUDGET_DIR: budget };
for (const branch of ['simultaneous-reject', 'stock-0-after-commit']) {
  let passed = false;
  for (let round = 1; round <= 40; round++) {
    process.stdout.write(`Synthetic candidate ${branch}, round ${round}\n`);
    const result = run('npm', ['run', 'test:demo:candidate'], path.join(repo, 'e2e'), { ...appEnv, AFTERBOOTH_REPLAY_RACE_BRANCH: branch });
    if (result.signal || result.status === 130) throw new Error('Candidate interrupted; no recording continuation');
    if (result.status === 0) { passed = true; break; }
    const missing = fs.readdirSync(capture).filter(name => name.endsWith('.json')).map(name => {
      const file = path.join(capture, name);
      const manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
      return { file, manifest };
    }).filter(({manifest}) => !fs.existsSync(path.join(repo, 'fixtures/replay', manifest.capability.toLowerCase(), `${manifest.key}.json`)));
    if (!missing.length) throw new Error('Candidate assertion failed without a missing fixture; inspect safe diagnostics before continuing');
    for (const { file } of missing) {
      const reviewed = run(java, ['-cp', classpath, 'com.afterbooth.ai.EnglishReplayRecorder', file], path.join(repo, 'backend'), { ...recorderEnv, META_API_KEY: '' });
      if (reviewed.status !== 0) throw new Error('Synthetic manifest review failed; no call authorized for it');
      if (!record) { process.stdout.write('Preparation only: missing manifest reviewed, no paid call\n'); process.exit(2); }
      const recorded = run(java, ['-cp', classpath, 'com.afterbooth.ai.EnglishReplayRecorder', file, '--record'], path.join(repo, 'backend'), recorderEnv);
      if (recorded.status !== 0) throw new Error('Recording failed or budget stopped; do not retry automatically');
    }
  }
  if (!passed) throw new Error('Candidate did not complete within the bounded preparation rounds');
}
fs.writeFileSync(path.join(session, 'candidate-success.json'), JSON.stringify({ mode:'replay', branches:['simultaneous-reject','stock-0-after-commit'] }));
process.stdout.write('Both controlled complete owned-data replay candidates passed. Default readiness gate was not modified.\n');
