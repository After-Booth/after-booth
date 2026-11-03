package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns process-local queued payloads and drains active work before withdrawal acquires DB locks.
 */
@Service
public class MemberWorkRegistry {
  private static final ThreadLocal<Scope> OWNED = new InheritableThreadLocal<>();

  public static boolean memberOwnedWork() {
    return OWNED.get() != null;
  }

  private record Scope(MemberWorkRegistry registry, Owner owner) {}

  public static Thread startChild(Runnable action) {
    return startChild(action, Thread::startVirtualThread);
  }

  /** A starter that throws before running the wrapper leaks no drain count. */
  public static Thread startChild(Runnable action, Function<Runnable, Thread> starter) {
    Scope scope = OWNED.get();
    if (scope == null) return starter.apply(action);
    synchronized (scope.registry) {
      scope.owner.active++;
    }
    try {
      return starter.apply(
          () -> {
            try {
              action.run();
            } finally {
              synchronized (scope.registry) {
                scope.owner.active--;
                scope.registry.notifyAll();
              }
            }
          });
    } catch (RuntimeException | Error failure) {
      synchronized (scope.registry) {
        scope.owner.active--;
        scope.registry.notifyAll();
      }
      throw failure;
    }
  }

  private final Map<Long, Owner> owners = new HashMap<>();

  public <T> T call(long memberId, Supplier<T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicBoolean executed = new AtomicBoolean();
    register(memberId, action)
        .run(
            input -> {
              result.set(input.get());
              executed.set(true);
            });
    if (!executed.get()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    return result.get();
  }

  public <T> Work<T> register(long memberId, T payload) {
    return register(memberId, payload, ignored -> {});
  }

  public synchronized <T> Work<T> register(long memberId, T payload, Consumer<T> erase) {
    Owner owner = owners.computeIfAbsent(memberId, ignored -> new Owner());
    boolean blocked = owner.withdrawn;
    Work<T> work = new Work<>(owner, blocked ? null : payload, erase);
    if (blocked) erase.accept(payload);
    if (!owner.withdrawn) owner.queued.add(work);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) work.discard();
            }
          });
    return work;
  }

  /** Call outside any database transaction or member lock. */
  public synchronized Guard erase(long memberId) {
    Owner owner = owners.computeIfAbsent(memberId, ignored -> new Owner());
    while (owner.paused) await();
    owner.paused = true;
    try {
      while (owner.active != 0) await();
      return new Guard(owner);
    } catch (RuntimeException failure) {
      owner.paused = false;
      notifyAll();
      throw failure;
    }
  }

  /** Called only after committed physical deletion has confirmed the member rows absent. */
  public synchronized void forgetPurged(java.util.Collection<Long> memberIds) {
    for (Long memberId : memberIds) {
      Owner owner = owners.get(memberId);
      if (owner != null
          && owner.withdrawn
          && !owner.paused
          && owner.active == 0
          && owner.queued.isEmpty()) owners.remove(memberId, owner);
    }
  }

  private void await() {
    try {
      wait();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Member work drain interrupted");
    }
  }

  private static final class Owner {
    boolean paused;
    boolean withdrawn;
    int active;
    final Set<Work<?>> queued = new HashSet<>();
  }

  public final class Work<T> {
    private final Owner owner;
    private T payload;
    private final Consumer<T> erase;

    private Work(Owner owner, T payload, Consumer<T> erase) {
      this.owner = owner;
      this.payload = payload;
      this.erase = erase;
    }

    public void run(Consumer<T> action) {
      T input;
      synchronized (MemberWorkRegistry.this) {
        try {
          while (owner.paused) await();
        } catch (RuntimeException failure) {
          clear();
          owner.queued.remove(this);
          throw failure;
        }
        input = payload;
        payload = null;
        owner.queued.remove(this);
        if (input == null || owner.withdrawn) return;
        owner.active++;
      }
      Scope previous = OWNED.get();
      OWNED.set(new Scope(MemberWorkRegistry.this, owner));
      try {
        action.accept(input);
      } finally {
        if (previous == null) OWNED.remove();
        else OWNED.set(previous);
        synchronized (MemberWorkRegistry.this) {
          owner.active--;
          MemberWorkRegistry.this.notifyAll();
        }
        erase.accept(input);
      }
    }

    public void discard() {
      synchronized (MemberWorkRegistry.this) {
        clear();
        owner.queued.remove(this);
      }
    }

    private void clear() {
      if (payload != null) erase.accept(payload);
      payload = null;
    }
  }

  public final class Guard implements AutoCloseable {
    private final Owner owner;
    private boolean committed;

    private Guard(Owner owner) {
      this.owner = owner;
    }

    public void commit() {
      synchronized (MemberWorkRegistry.this) {
        owner.withdrawn = true;
        owner.queued.forEach(Work::clear);
        owner.queued.clear();
        committed = true;
      }
    }

    @Override
    public void close() {
      synchronized (MemberWorkRegistry.this) {
        if (committed) owner.withdrawn = true;
        owner.paused = false;
        MemberWorkRegistry.this.notifyAll();
      }
    }
  }
}
