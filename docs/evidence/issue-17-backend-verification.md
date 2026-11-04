# Backend verification evidence

All Gradle commands ran in the issue #17 worktree's `backend/` directory with Java 21, `AI_MODE=replay` from the Gradle test task, `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m`. No live or record calls were enabled. PostgreSQL integration tests used disposable Testcontainers databases. Local HTTPS tests generated temporary keys/certificates and used the local-only Fake mail provider; the new full transaction test uses an explicitly named synthetic primary AiGateway.

Build command (successful):
```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m spotlessApply compileJava compileTestJava bootJar
```

Final HTTPS command (successful, seven tests):
```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m spotlessApply spotlessCheck test --tests 'com.afterbooth.LocalFakePasswordHttpsTest'
```

Other per-class successful executions (do not aggregate these as a single successful command; several parent commands also contained failures subsequently repaired):
- ArchitectureTest: 7
- LocalFakeSignupHttpsTest: 1
- RetentionIntegrationTest: 10
- SignupIntegrationTest: 17
- WithdrawalIntegrationTest: 16 after adding the new re-registration isolation test
- AllocationServiceTest: 7
- ConditionOutputTest: 14
- AllocationOutcomeTest: 3
- IntakeProcessorTest: 13

The orchestrator inspected the backend subagent's commands and per-suite results; Gradle overwrote their XML reports on each later command. Their test status is evidenced by the inspected XML and tool outputs, not by the current report directory.

Known unresolved recording failure:
```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m spotlessApply test --tests 'com.afterbooth.allocation.AllocationOutcomeTest' --tests 'com.afterbooth.intake.service.IntakeProcessorTest' --tests 'com.afterbooth.WithdrawalIntegrationTest' --tests 'com.afterbooth.request.service.ConditionInterpretationReplayTest'
```
This completed with 33 tests, one failure: ConditionInterpretationReplayTest. The other 32 passed. The historical audio still replays transcription; the changed English system prompt has no Spark recording. The exact missing path is `fixtures/replay/spark/4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711.json`. The old canonical request key was verified against `0890f3ea8678a3cf3e08248a08b4e7299843bb93cf909058c3e9600cbc5a3dc0.json`, and the transcript was matched to the unchanged audio/transcription recording. Original recording files were not edited. A new paid recording requires separate user approval.


Additional affected English assertion command:
```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m spotlessApply spotlessCheck test --tests 'com.afterbooth.inventory.domain.CoreDomainTest' --tests 'com.afterbooth.global.error.GlobalExceptionHandlerTest' --tests 'com.afterbooth.HandoverIntegrationTest' --tests 'com.afterbooth.AllocationIntegrationTest' --tests 'com.afterbooth.ReservationIntegrationTest' --tests 'com.afterbooth.ArchitectureTest'
```
91 tests, one failure reproduced a real display bug: the reservation listing projection selected the stored Korean label directly rather than the translated category label. Successful suites in that command: CoreDomainTest 10, GlobalExceptionHandlerTest 11, HandoverIntegrationTest 15, AllocationIntegrationTest 15, ArchitectureTest 7. ReservationIntegrationTest 33 had one failure. The subsequent code fix reads ItemCategory in the same one-query projection and derives its English label without changing stored data.


Final projection fix verification (successful):
```sh
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m spotlessApply spotlessCheck test --tests 'com.afterbooth.ReservationIntegrationTest' --tests 'com.afterbooth.ArchitectureTest' bootJar
```
- com.afterbooth.ArchitectureTest: tests=7, failures=0, errors=0, skipped=0
- com.afterbooth.ReservationIntegrationTest: tests=33, failures=0, errors=0, skipped=0
The XML reports for the final command are in backend/build/test-results/test/ and the HTML report is backend/build/reports/tests/test/index.html. git diff --check passed. Only the seven targeted backend files have supplemental changes; no other agent changes were reverted.
