# AfterBooth Playwright E2E (issue #12)

> **Authentication rollout:** This is the historical, unauthenticated issue #12
> scenario. It assumes HTTP access and seeded organizations, so it does not
> verify issue #70 or the authenticated application. Do not report its prior
> results as evidence for signup, ownership, secure cookies, or current flows.

## Local HTTPS authentication setup (#70)

The steps below are a **manual setup proposal and have not been verified as an
end-to-end run**. They use disposable local data and synthetic authentication
keys. They require PowerShell 7, Node.js, Java 21 (including `keytool`), and a
working Docker engine. Run them from the repository root. Do not reuse these
keys or the test database for production. The existing `dev:mock` server and
the HTTP stack below are not suitable for this check: the app and API must share
the exact HTTPS origin for Secure cookies and Origin checks.

1. Build the frontend and start an isolated PostgreSQL instance. Make sure port
   `15439` and container name `afterbooth-70-local-db` are available first.

   ```powershell
   npm --prefix frontend ci
   npm --prefix frontend run build
   docker run -d --rm --name afterbooth-70-local-db `
     -e POSTGRES_DB=afterbooth -e POSTGRES_USER=afterbooth `
     -e POSTGRES_PASSWORD=local-only -p 127.0.0.1:15439:5432 postgres:17-alpine
   docker exec afterbooth-70-local-db pg_isready -U afterbooth -d afterbooth
   ```

   Wait until `pg_isready` reports that the server accepts connections before
   continuing. This container is disposable and has no persistent volume.

2. Create a short-lived local TLS keystore and generate fresh, synthetic HMAC
   and RSA keys in this PowerShell process. The fixed keystore password below
   is only for this disposable local certificate; do not use it elsewhere.
   Export the public certificate and trust it using your normal local certificate
   trust procedure before opening the browser; remove that trust after the run.
   Do not bypass a browser warning.

   ```powershell
   $taskTlsFile = Join-Path $env:TEMP ('afterbooth-70-' + [guid]::NewGuid() + '.p12')
   keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 2 `
     -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' `
     -storetype PKCS12 -keystore $taskTlsFile -storepass local-test-only `
     -keypass local-test-only -noprompt
   if ($LASTEXITCODE -ne 0) { throw 'Local TLS key generation failed' }
   $taskCertFile = [IO.Path]::ChangeExtension($taskTlsFile, '.cer')
   keytool -exportcert -rfc -alias localhost -keystore $taskTlsFile `
     -storepass local-test-only -file $taskCertFile
   if ($LASTEXITCODE -ne 0) { throw 'Local TLS certificate export failed' }

   $taskHmac = New-Object byte[] 32
   [System.Security.Cryptography.RandomNumberGenerator]::Fill($taskHmac)
   $taskRsa = [System.Security.Cryptography.RSA]::Create()
   $taskRsa.KeySize = 2048
   $taskFrontendUri = ([uri]((Resolve-Path frontend/dist).Path + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
   $taskConfig = @{
     server = @{ port = 5443; ssl = @{
       enabled = $true; 'key-store' = ([uri]$taskTlsFile).AbsoluteUri; 'key-store-type' = 'PKCS12'
       'key-store-password' = 'local-test-only'
     } }
     spring = @{
       profiles = @{ active = 'local' }
       docker = @{ compose = @{ enabled = $false } }
       datasource = @{ url = 'jdbc:postgresql://127.0.0.1:15439/afterbooth'; username = 'afterbooth'; password = 'local-only' }
       web = @{ resources = @{ 'static-locations' = $taskFrontendUri } }
     }
     afterbooth = @{
       mail = @{ 'fake-enabled' = $true }
       auth = @{
         'allowed-origin' = 'https://localhost:5443'
         hmac = @{ 'current-version' = 'synthetic'; keys = @{ synthetic = [Convert]::ToBase64String($taskHmac) } }
         jwt = @{
           issuer = 'synthetic-issuer'; audience = 'synthetic-audience'; 'current-kid' = 'synthetic'
           'private-key' = [Convert]::ToBase64String($taskRsa.ExportPkcs8PrivateKey())
           'public-keys' = @{ synthetic = [Convert]::ToBase64String($taskRsa.ExportSubjectPublicKeyInfo()) }
         }
       }
     }
   }
   $taskRsa.Dispose()
   $env:SPRING_APPLICATION_JSON = $taskConfig | ConvertTo-Json -Depth 10 -Compress
   $env:AI_MODE = 'replay'
   ```

3. In the same private PowerShell console, build the backend and start the jar
   directly from `backend/`. Keeping the server in this private console avoids
   forwarding Fake output to a Gradle daemon or shared log collector:

   ```powershell
   Set-Location backend
   .\gradlew.bat bootJar
   if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
   java -jar build/libs/afterbooth-0.0.1-SNAPSHOT.jar
   ```

   The built frontend is intended to be served by the backend at
   `https://localhost:5443/`; API requests use that same origin. Fake mail is
   disabled by default and enabled here only by both `local` and the explicit
   `fake-enabled` setting. During manual signup or reset, read the short-lived
   purpose/code line only in this private console and enter the code yourself.
   Do not save or share console output, codes, credentials, cookies, or tokens.
   Fake mail simulates delivery and does not establish real mailbox ownership.
   Do not enable Resend or call external AI during this run.

4. Stop the backend, then from the original PowerShell session remove only this
   run's container and TLS file. Clear the process-scoped configuration:

   ```powershell
   docker stop afterbooth-70-local-db
   Remove-Item -LiteralPath $taskTlsFile, $taskCertFile
   Remove-Item Env:SPRING_APPLICATION_JSON, Env:AI_MODE
   Remove-Variable taskConfig, taskHmac, taskRsa
   ```

   Also remove the local certificate from the trust store used above. The
   synthetic keys remain valid only for this disposable run. Automated
   tests must capture Fake codes internally in memory and avoid exposing them
   in logs, traces, screenshots, or a public API.

Replays the full demo scenario against the real stack in `AI_MODE=replay`:
photo intake -> voice pickup request -> allocation proposal -> two-context race
for the last display rack -> winner confirms in the UI -> photo handover ->
reuse stats. Every step drives the real Korean UI; the API is used only for
setup guards and result verification, never to stand in for a user action.

## Stack

A dedicated compose project keeps parallel sessions untouched (own ports,
own volume, own network). Run from the repo root:

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml up --build -d
curl -s http://localhost:18080/api/health   # {"status":"UP","aiMode":"replay"}
```

Reset the demo database between runs (this project only):

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml up -d
```

Tear down everything this suite started:

```bash
docker compose -p afterbooth-e2e-12 -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v
```

## Run

```bash
cd e2e
npm ci
npm run install-browsers   # Chromium (headless shell) only
E2E_RUN_TAG=run1 npx playwright test
```

Inputs come from `../spike/samples/` (byte-identical files) and the exact
race phrase in `tests/demo.spec.ts`; replay fixtures will not match anything
else. `E2E_BASE_URL` overrides the default `http://localhost:18080`.

## Evidence

- `test-results/<test>/latency-<tag>.json`: wall-clock latency per step plus the
  race overlap gaps and the executed race branch (`simultaneous-reject` or
  `stock-0-after-commit`). The console prints the same table. Each run wipes
  `test-results/`, so copy the file aside to keep several runs.
- Traces and screenshots are kept on failure only. Scan them for secrets
  before sharing: replay mode sends no keys, but check anyway.
