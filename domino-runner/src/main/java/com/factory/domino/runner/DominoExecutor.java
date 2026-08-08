package com.factory.domino.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoClientBuilder;
import com.hcl.domino.DominoProcess;

/**
 * Runs work against Domino on threads that hold a live Domino context.
 *
 * <p>Domino's threading rules are strict and unforgiving: every thread touching the API must
 * call {@code initializeThread()} first, {@code terminateThread()} when done, and a
 * {@link DominoClient} belongs to the thread that created it. A server that simply handed
 * requests to an arbitrary pool thread would work for the first request and then fail in ways
 * that look random.
 *
 * <p>This executor sidesteps that by owning a fixed set of single-threaded executors. Each has
 * exactly one thread that initializes its Domino context once, keeps a {@link DominoClient} for
 * its lifetime, and tears both down on shutdown. Because each worker is its own executor, the
 * shutdown task is guaranteed to run on the same thread that did the initialization — which
 * {@code terminateThread()} requires, and which a shared pool cannot guarantee.
 *
 * <p>Callers submit work and block for the result, so request handling stays straightforward.
 */
public final class DominoExecutor implements AutoCloseable {

  /** Per-worker state, only ever touched by that worker's own thread. */
  private static final class Worker {
    private final ExecutorService executor;
    private DominoProcess.DominoThreadContext threadContext;
    private DominoClient client;

    Worker(int index) {
      this.executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "domino-worker-" + index);
        thread.setDaemon(true);
        return thread;
      });
    }

    /** Lazily binds the Domino context; runs on the worker thread. */
    DominoClient client() {
      if (client == null) {
        threadContext = DominoProcess.get().initializeThread();
        client = DominoClientBuilder.newDominoClient().asIDUser().build();
      }
      return client;
    }

    /** Releases the context; must run on the worker thread, which is why it is submitted. */
    void releaseOnOwnThread() {
      if (client != null) {
        try {
          client.close();
        } catch (RuntimeException e) {
          System.err.println("domino-executor: error closing client: " + e);
        }
        client = null;
      }
      if (threadContext != null) {
        try {
          threadContext.close();
        } catch (RuntimeException e) {
          System.err.println("domino-executor: error closing thread context: " + e);
        }
        threadContext = null;
      }
    }
  }

  private final List<Worker> workers = new ArrayList<>();
  private final AtomicInteger nextWorker = new AtomicInteger();
  private volatile boolean closed;

  /**
   * @param threads number of Domino-capable worker threads; each holds an open client, so this
   *     bounds concurrency against the Domino runtime
   */
  public DominoExecutor(int threads) {
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be at least 1, got " + threads);
    }
    for (int i = 0; i < threads; i++) {
      workers.add(new Worker(i));
    }
  }

  public int getThreadCount() {
    return workers.size();
  }

  /**
   * Runs {@code work} on a Domino-capable thread and returns its result.
   *
   * <p>Exceptions thrown by the work are rethrown to the caller: unchecked ones as themselves,
   * anything else wrapped in a {@link RuntimeException}.
   */
  public <T> T call(Function<DominoClient, T> work) {
    if (closed) {
      throw new IllegalStateException("DominoExecutor has been closed");
    }
    // Round-robin: work is short and uniform enough that anything smarter would not pay off.
    Worker worker = workers.get(Math.abs(nextWorker.getAndIncrement() % workers.size()));

    Future<T> future = worker.executor.submit(() -> work.apply(worker.client()));
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted while waiting for Domino work", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }

  /** Runs {@code work} on a Domino-capable thread, discarding the result. */
  public void run(java.util.function.Consumer<DominoClient> work) {
    call(client -> {
      work.accept(client);
      return null;
    });
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (Worker worker : workers) {
      try {
        // Submitted rather than called directly: terminateThread() must happen on the
        // thread that initialized the context.
        worker.executor.submit(worker::releaseOnOwnThread).get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        System.err.println("domino-executor: worker shutdown failed: " + e);
      }
      worker.executor.shutdown();
    }
  }
}
