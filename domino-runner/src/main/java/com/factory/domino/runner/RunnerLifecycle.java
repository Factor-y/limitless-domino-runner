package com.factory.domino.runner;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Lets hosted programs register cleanup that the launcher runs <em>before</em> it releases the
 * Domino runtime.
 *
 * <p>A hosted server cannot just add its own JVM shutdown hook: hooks run concurrently, so the
 * launcher could call {@code NotesTerm()} while the server is still serving a request against a
 * Domino handle. Registering here instead makes the order deterministic — hosted cleanup first,
 * Domino teardown after.
 *
 * <p>Callbacks run in reverse registration order, so components are torn down in the opposite
 * order from how they were built.
 */
public final class RunnerLifecycle {

  private static final Deque<Runnable> SHUTDOWN_CALLBACKS = new ArrayDeque<>();

  private static volatile Runnable shutdownTrigger;

  private RunnerLifecycle() {
  }

  /** Installed by the launcher when running with {@code --wait}. */
  static void setShutdownTrigger(Runnable trigger) {
    shutdownTrigger = trigger;
  }

  /**
   * Asks the launcher to begin an orderly shutdown, as if Ctrl+C had been pressed.
   *
   * <p>This exists because signals are not a reliable way to stop a process hosting the Notes
   * runtime: initializing Domino installs native signal handlers that swallow SIGINT, SIGTERM
   * and SIGQUIT, leaving SIGKILL as the only thing that works — which skips all cleanup.
   * A hosted server should therefore expose its own way to be stopped and call this.
   *
   * @return {@code false} if the launcher was not started with {@code --wait}, in which case
   *     there is nothing to unblock
   */
  public static boolean requestShutdown() {
    Runnable trigger = shutdownTrigger;
    if (trigger == null) {
      return false;
    }
    trigger.run();
    return true;
  }

  /** Registers cleanup to run before the Domino runtime is released. */
  public static void onShutdown(Runnable callback) {
    if (callback == null) {
      throw new IllegalArgumentException("callback must not be null");
    }
    synchronized (SHUTDOWN_CALLBACKS) {
      SHUTDOWN_CALLBACKS.push(callback);
    }
  }

  /**
   * Runs and clears the registered callbacks. A failing callback is reported and does not stop
   * the others: shutdown must make progress.
   */
  static void runShutdownCallbacks() {
    Runnable callback;
    while (true) {
      synchronized (SHUTDOWN_CALLBACKS) {
        callback = SHUTDOWN_CALLBACKS.poll();
      }
      if (callback == null) {
        return;
      }
      try {
        callback.run();
      } catch (RuntimeException | Error e) {
        System.err.println("domino-runner: shutdown callback failed: " + e);
      }
    }
  }
}
