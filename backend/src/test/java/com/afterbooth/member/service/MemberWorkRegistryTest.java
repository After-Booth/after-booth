package com.afterbooth.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.security.MemberWorkRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MemberWorkRegistryTest {
  @Test
  void queuedPayloadsAndLateSubmissionsAreDiscardedButOtherOwnersSurvive() {
    var registry = new MemberWorkRegistry();
    var queued = registry.register(1, new Object());
    var other = registry.register(2, new Object());
    AtomicBoolean ran = new AtomicBoolean();
    try (var guard = registry.erase(1)) {
      guard.commit();
    }
    queued.run(input -> ran.set(true));
    registry.register(1, new Object()).run(input -> ran.set(true));
    assertThat(ran).isFalse();
    other.run(input -> ran.set(true));
    assertThat(ran).isTrue();
  }

  @Test
  void failedWithdrawalPreservesQueuedPayloadForResumption() {
    var registry = new MemberWorkRegistry();
    byte[] input = new byte[] {7};
    var queued = registry.register(1, input, bytes -> java.util.Arrays.fill(bytes, (byte) 0));
    try (var ignored = registry.erase(1)) {
      assertThat(input[0] == 7).isTrue();
    }
    AtomicBoolean ran = new AtomicBoolean();
    queued.run(bytes -> ran.set(bytes[0] == 7));
    assertThat(ran).isTrue();
    assertThat(input[0] == 0).isTrue();
  }

  @Test
  void registrationsDuringFailedWithdrawalResumeWithoutLosingPayload() {
    var registry = new MemberWorkRegistry();
    MemberWorkRegistry.Work<byte[]> queued;
    byte[] input = new byte[] {7};
    try (var ignored = registry.erase(1)) {
      queued = registry.register(1, input, bytes -> java.util.Arrays.fill(bytes, (byte) 0));
      assertThat(input[0] == 7).isTrue();
    }
    AtomicBoolean ran = new AtomicBoolean();
    queued.run(bytes -> ran.set(bytes[0] == 7));
    assertThat(ran).isTrue();
  }

  @Test
  void detachedChildIsTrackedUntilItsActualExit() throws Exception {
    var registry = new MemberWorkRegistry();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      registry.call(
          1,
          () -> {
            MemberWorkRegistry.startChild(
                () -> {
                  started.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                });
            return null;
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      var withdrawal =
          threads.submit(
              () -> {
                try (var guard = registry.erase(1)) {
                  guard.commit();
                }
              });
      assertThat(withdrawal.isDone()).isFalse();
      release.countDown();
      withdrawal.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void failedChildStartLeavesNoLeakedCount() throws Exception {
    var registry = new MemberWorkRegistry();
    var failure = new IllegalStateException("synthetic");
    assertThatThrownBy(
            () ->
                registry.call(
                    1,
                    () -> {
                      MemberWorkRegistry.startChild(
                          () -> {},
                          task -> {
                            throw failure;
                          });
                      return null;
                    }))
        .isSameAs(failure);
    assertThat(MemberWorkRegistry.memberOwnedWork()).isFalse();
    try (var threads = Executors.newFixedThreadPool(1)) {
      try (var guard = threads.submit(() -> registry.erase(1)).get(5, TimeUnit.SECONDS)) {
        guard.commit();
      }
    }
  }

  @Test
  void errorDuringChildStartLeavesNoLeakedCount() throws Exception {
    var registry = new MemberWorkRegistry();
    var failure = new OutOfMemoryError("synthetic");
    assertThatThrownBy(
            () ->
                registry.call(
                    1,
                    () -> {
                      MemberWorkRegistry.startChild(
                          () -> {},
                          task -> {
                            throw failure;
                          });
                      return null;
                    }))
        .isSameAs(failure);
    assertThat(MemberWorkRegistry.memberOwnedWork()).isFalse();
    try (var threads = Executors.newFixedThreadPool(1)) {
      try (var guard = threads.submit(() -> registry.erase(1)).get(5, TimeUnit.SECONDS)) {
        guard.commit();
      }
    }
  }

  @Test
  void throwingEraserStillReleasesScopeAndDrainCount() throws Exception {
    var registry = new MemberWorkRegistry();
    var failure = new IllegalStateException("synthetic");
    assertThatThrownBy(
            () ->
                registry
                    .register(
                        1,
                        new Object(),
                        ignored -> {
                          throw failure;
                        })
                    .run(input -> {}))
        .isSameAs(failure);
    assertThat(MemberWorkRegistry.memberOwnedWork()).isFalse();
    try (var threads = Executors.newFixedThreadPool(1)) {
      try (var guard = threads.submit(() -> registry.erase(1)).get(5, TimeUnit.SECONDS)) {
        guard.commit();
      }
    }
  }

  @Test
  void failedWithdrawalAllowsFreshWorkAndClearsScopeAfterFailure() {
    var registry = new MemberWorkRegistry();
    try (var ignored = registry.erase(1)) {}
    assertThatThrownBy(
            () ->
                registry
                    .register(1, new Object())
                    .run(
                        input -> {
                          assertThat(MemberWorkRegistry.memberOwnedWork()).isTrue();
                          throw new IllegalStateException("synthetic");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(MemberWorkRegistry.memberOwnedWork()).isFalse();
    try (var ignored = registry.erase(1)) {}
  }

  @Test
  void withdrawalWaitsForActiveWorkBeforeReturningAndBlocksFurtherWork() throws Exception {
    var registry = new MemberWorkRegistry();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean drained = new AtomicBoolean();
    try (var threads = Executors.newFixedThreadPool(2)) {
      var active =
          threads.submit(
              () ->
                  registry
                      .register(1, new Object())
                      .run(
                          input -> {
                            started.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("timeout");
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException("interrupted");
                            }
                          }));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      var withdrawal =
          threads.submit(
              () -> {
                try (var guard = registry.erase(1)) {
                  drained.set(true);
                  guard.commit();
                }
              });
      assertThat(drained).isFalse();
      release.countDown();
      active.get(5, TimeUnit.SECONDS);
      withdrawal.get(5, TimeUnit.SECONDS);
      assertThat(drained).isTrue();
    }
  }
}
