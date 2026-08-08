package com.factory.domino.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hcl.domino.DominoProcess;

/**
 * Initializes the Notes/Domino runtime, then runs an arbitrary Java {@code main} class inside it.
 *
 * <p>The target program runs in this same JVM and on a thread that already holds an initialized
 * Domino context, so it can use both the classic {@code lotus.domino} API and Domino JNX without
 * doing any setup of its own.
 *
 * <pre>
 *   domino-runner [options] &lt;main-class&gt; [program arguments...]
 * </pre>
 */
public final class DominoRunner {

  private static final String ENV_ID_PASSWORD = "DOMINO_ID_PASSWORD";

  private String mainClassName;
  private final List<String> programArgs = new ArrayList<>();
  private final List<String> extraClasspath = new ArrayList<>();
  private String applicationJar;
  private final List<String> additionalSharedPrefixes = new ArrayList<>();
  private boolean waitForShutdown;
  private String idFile;
  private String password;
  private boolean passwordFromStdin;
  private boolean promptForPassword;
  private String execDir;
  private String notesIni;
  private boolean verbose;

  /** Released by the shutdown hook so a long-running program can unblock and clean up. */
  private final java.util.concurrent.CountDownLatch shutdownRequested =
      new java.util.concurrent.CountDownLatch(1);
  /** Released once cleanup has finished, so the JVM does not exit mid-teardown. */
  private final java.util.concurrent.CountDownLatch shutdownComplete =
      new java.util.concurrent.CountDownLatch(1);

  public static void main(String[] args) {
    DominoRunner runner = new DominoRunner();
    try {
      if (!runner.parseArguments(args)) {
        System.exit(2);
      }
    } catch (IllegalArgumentException e) {
      System.err.println("domino-runner: " + e.getMessage());
      System.err.println("Try 'domino-runner --help' for usage.");
      System.exit(2);
      return;
    }
    System.exit(runner.run());
  }

  /**
   * @return {@code false} when the process should stop without running anything (help was shown
   *     or arguments were incomplete)
   */
  private boolean parseArguments(String[] args) {
    int i = 0;
    while (i < args.length) {
      String arg = args[i];

      // Everything after the main class name belongs to the target program, so option
      // parsing stops as soon as the first non-option argument is seen.
      if (!arg.startsWith("-")) {
        mainClassName = arg;
        programArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
        return true;
      }

      switch (arg) {
        case "-h":
        case "--help":
          printUsage();
          return false;
        case "--cp":
        case "--classpath":
          extraClasspath.add(requireValue(args, ++i, arg));
          break;
        case "--jar":
          applicationJar = requireValue(args, ++i, arg);
          break;
        case "--share-package":
          additionalSharedPrefixes.add(requireValue(args, ++i, arg));
          break;
        case "--wait":
          waitForShutdown = true;
          break;
        case "--id":
          idFile = requireValue(args, ++i, arg);
          break;
        case "--password":
          password = requireValue(args, ++i, arg);
          break;
        case "--password-stdin":
          passwordFromStdin = true;
          break;
        case "--prompt-password":
          promptForPassword = true;
          break;
        case "--exec-dir":
          execDir = requireValue(args, ++i, arg);
          break;
        case "--notes-ini":
          notesIni = requireValue(args, ++i, arg);
          break;
        case "--verbose":
        case "-v":
          verbose = true;
          break;
        case "--":
          // Explicit end of options. With --jar the main class comes from the manifest, so
          // everything after '--' belongs to the hosted program — without this there is no
          // way to pass it an argument that looks like a runner option.
          if (applicationJar != null) {
            programArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
            return true;
          }
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("no main class given after '--'");
          }
          mainClassName = args[i + 1];
          programArgs.addAll(Arrays.asList(args).subList(i + 2, args.length));
          return true;
        default:
          throw new IllegalArgumentException("unknown option '" + arg + "'");
      }
      i++;
    }

    // With --jar the main class is optional: it can come from the jar manifest.
    if (applicationJar != null) {
      return true;
    }
    throw new IllegalArgumentException("no main class specified");
  }

  private static String requireValue(String[] args, int index, String option) {
    if (index >= args.length) {
      throw new IllegalArgumentException("option '" + option + "' requires a value");
    }
    return args[index];
  }

  private int run() {
    NotesEnvironment environment = NotesEnvironment.detect(execDir, notesIni);
    try {
      environment.validate();
    } catch (IllegalStateException e) {
      System.err.println(e.getMessage());
      return 3;
    }

    if (verbose) {
      System.out.println("[domino-runner] environment:");
      System.out.println(environment.describe());
    }

    String effectivePassword = resolvePassword();

    DominoProcess process = DominoProcess.get();
    boolean processInitialized = false;
    try {
      process.initializeProcess(environment.toInitArgs());
      processInitialized = true;
      if (verbose) {
        System.out.println("[domino-runner] Domino process initialized.");
      }

      // Only needed when the ID is password protected and password sharing with other
      // Notes-based programs is disabled in User Security.
      if (idFile != null) {
        Path id = Paths.get(idFile);
        if (!id.toFile().isFile()) {
          System.err.println("domino-runner: ID file '" + idFile + "' does not exist.");
          return 3;
        }
        String switchedTo = process.switchToId(id, effectivePassword, true);
        if (verbose) {
          System.out.println("[domino-runner] switched to ID: " + switchedTo);
        }
      }

      try (DominoProcess.DominoThreadContext ignored = process.initializeThread()) {
        if (verbose) {
          // The main class may still be unresolved here: with --jar it comes from the
          // manifest, which is only read once the class loader exists.
          System.out.println("[domino-runner] thread context initialized, launching "
              + (mainClassName != null ? mainClassName : "Main-Class from " + applicationJar));
          System.out.println();
        }
        return invokeTarget();
      }
    } catch (Exception e) {
      System.err.println("domino-runner: failed to run '" + mainClassName + "': " + e);
      e.printStackTrace(System.err);
      return 1;
    } finally {
      if (processInitialized) {
        try {
          // NotesTerm(): releases this process's use of the Notes runtime. It is the
          // counterpart of NotesInit() and does not affect a running Notes client.
          process.terminateProcess();
          if (verbose) {
            System.out.println("[domino-runner] Domino runtime released for this process.");
          }
        } catch (RuntimeException e) {
          System.err.println("domino-runner: error during process termination: " + e);
        }
      }
      // Lets the shutdown hook, if any, stop holding the JVM open.
      shutdownComplete.countDown();
    }
  }

  /**
   * Loads and invokes the target {@code main} method.
   *
   * <p>When {@code --cp} entries are given the class is loaded from a child class loader whose
   * parent is the runner's own loader, so the target program sees the Domino classes already
   * loaded rather than a second, incompatible copy.
   */
  private int invokeTarget() throws Exception {
    ClassLoader loader = buildClassLoader();
    Thread.currentThread().setContextClassLoader(loader);

    if (mainClassName == null) {
      // --jar without an explicit class: take Main-Class from the manifest.
      if (loader instanceof IsolatedJarClassLoader isolated) {
        mainClassName = isolated.getManifestMainClass();
      }
      if (mainClassName == null) {
        System.err.println("domino-runner: no main class given and '" + applicationJar
            + "' has no Main-Class in its manifest.");
        return 4;
      }
      if (verbose) {
        System.out.println("[domino-runner] Main-Class from manifest: " + mainClassName);
      }
    }

    Class<?> mainClass;
    try {
      mainClass = Class.forName(mainClassName, true, loader);
    } catch (ClassNotFoundException e) {
      System.err.println("domino-runner: class '" + mainClassName + "' not found."
          + (extraClasspath.isEmpty() ? " Use --cp to add it to the classpath." : ""));
      return 4;
    }

    Method main;
    try {
      main = mainClass.getMethod("main", String[].class);
    } catch (NoSuchMethodException e) {
      System.err.println("domino-runner: class '" + mainClassName
          + "' has no 'public static void main(String[])' method.");
      return 4;
    }
    if (!Modifier.isStatic(main.getModifiers())) {
      System.err.println("domino-runner: 'main' in '" + mainClassName + "' is not static.");
      return 4;
    }

    try {
      main.invoke(null, (Object) programArgs.toArray(new String[0]));
      if (waitForShutdown) {
        awaitShutdown();
      }
      return 0;
    } catch (InvocationTargetException e) {
      // Report the target program's own failure, not the reflection wrapper.
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      System.err.println("domino-runner: '" + mainClassName + "' terminated with an exception:");
      cause.printStackTrace(System.err);
      return 1;
    }
  }

  /**
   * Blocks until the JVM is asked to shut down, then lets the caller's cleanup finish before
   * the JVM actually exits.
   *
   * <p>Needed for servers: their {@code main} returns as soon as the listener is up, and without
   * this the launcher would tear the Domino runtime down underneath a running server.
   */
  private void awaitShutdown() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      shutdownRequested.countDown();
      try {
        // Hold the JVM open until the Domino teardown below has completed.
        shutdownComplete.await(30, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "domino-runner-shutdown"));

    installSignalHandlers();
    RunnerLifecycle.setShutdownTrigger(shutdownRequested::countDown);

    System.out.println("[domino-runner] running. Note that the Notes runtime installs native "
        + "signal handlers that swallow Ctrl+C;");
    System.out.println("[domino-runner] use the hosted program's own stop mechanism, "
        + "or 'kill -9' as a last resort.");
    try {
      shutdownRequested.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    System.out.println("[domino-runner] shutdown requested, stopping.");

    // Hosted cleanup runs here, on this thread, before the caller releases the Domino
    // runtime. Leaving it to the program's own shutdown hook would race that teardown.
    RunnerLifecycle.runShutdownCallbacks();
  }

  /**
   * Re-installs SIGINT/SIGTERM handlers on top of the ones the Notes runtime put in place.
   *
   * <p>Initializing the Notes runtime replaces the JVM's own signal handling, and the result is
   * a process that ignores Ctrl+C entirely — verified here: after {@code initializeProcess()},
   * SIGINT, SIGTERM and even SIGQUIT produce no reaction at all, and only SIGKILL stops it,
   * while an identical JVM without Domino runs its shutdown hooks normally.
   *
   * <p>On macOS this attempt is <strong>not</strong> sufficient — the native handlers still win,
   * which is why {@link RunnerLifecycle#requestShutdown()} exists as the reliable path. It is
   * kept because it is harmless and may work on platforms where Domino behaves differently.
   *
   * <p>Uses reflection against {@code sun.misc.Signal} so the launcher still compiles and runs
   * on a JVM where that package is unavailable — in which case Ctrl+C simply will not work, and
   * the message says so rather than leaving it a mystery.
   */
  private void installSignalHandlers() {
    try {
      Class<?> signalClass = Class.forName("sun.misc.Signal");
      Class<?> handlerInterface = Class.forName("sun.misc.SignalHandler");

      Object handler = java.lang.reflect.Proxy.newProxyInstance(
          getClass().getClassLoader(),
          new Class<?>[] {handlerInterface},
          (proxy, method, methodArgs) -> {
            if ("handle".equals(method.getName())) {
              shutdownRequested.countDown();
              return null;
            }
            // Keep Object methods (equals/hashCode/toString) working on the proxy.
            return method.invoke(this, methodArgs);
          });

      java.lang.reflect.Method handle =
          signalClass.getMethod("handle", signalClass, handlerInterface);
      for (String signalName : new String[] {"INT", "TERM"}) {
        try {
          Object signal = signalClass.getConstructor(String.class).newInstance(signalName);
          handle.invoke(null, signal, handler);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
          System.err.println("domino-runner: could not install handler for SIG" + signalName
              + ": " + e.getMessage());
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      System.err.println("domino-runner: signal handlers unavailable (" + e
          + "); Ctrl+C may not stop this process cleanly.");
    }
  }

  private ClassLoader buildClassLoader() throws MalformedURLException {
    ClassLoader parent = DominoRunner.class.getClassLoader();

    // --jar takes precedence: the jar is loaded child-first so it can carry its own
    // dependencies without colliding with the launcher's.
    if (applicationJar != null) {
      IsolatedJarClassLoader loader = IsolatedJarClassLoader.forJar(
          new File(applicationJar), parent, additionalSharedPrefixes);
      if (verbose) {
        System.out.println("[domino-runner] application jar (parent-last): " + applicationJar);
      }
      return loader;
    }

    if (extraClasspath.isEmpty()) {
      return parent;
    }

    List<URL> urls = new ArrayList<>();
    for (String entry : extraClasspath) {
      // Each --cp value may itself be a path-separator separated list, as with 'java -cp'.
      for (String element : entry.split(File.pathSeparator)) {
        if (element.trim().isEmpty()) {
          continue;
        }
        File file = new File(element);
        if (!file.exists()) {
          System.err.println("domino-runner: warning: classpath entry does not exist: " + element);
        }
        urls.add(file.toURI().toURL());
      }
    }
    if (verbose) {
      System.out.println("[domino-runner] target classpath: " + urls);
    }
    return new URLClassLoader(urls.toArray(new URL[0]), parent);
  }

  /**
   * Resolves the ID password from, in order: {@code --password}, stdin, the
   * {@code DOMINO_ID_PASSWORD} environment variable, or an interactive prompt.
   *
   * @return the password, or {@code null} when none was supplied
   */
  private String resolvePassword() {
    if (password != null) {
      return password;
    }
    if (passwordFromStdin) {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        if (line != null) {
          return line;
        }
      } catch (IOException e) {
        System.err.println("domino-runner: could not read password from stdin: " + e.getMessage());
      }
    }
    String fromEnv = System.getenv(ENV_ID_PASSWORD);
    if (fromEnv != null && !fromEnv.isEmpty()) {
      return fromEnv;
    }
    if (promptForPassword) {
      java.io.Console console = System.console();
      if (console != null) {
        char[] entered = console.readPassword("Notes ID password: ");
        if (entered != null) {
          return new String(entered);
        }
      } else {
        System.err.println("domino-runner: no console available for --prompt-password.");
      }
    }
    return null;
  }

  private static void printUsage() {
    System.out.println(String.join(System.lineSeparator(),
        "Usage: domino-runner [options] <main-class> [program arguments...]",
        "",
        "Initializes the Notes/Domino runtime, then runs <main-class> inside it. The target",
        "program runs on a thread with an active Domino context and can use both the classic",
        "lotus.domino API and Domino JNX without initializing anything itself.",
        "",
        "Options:",
        "  --jar <path>              Application jar, loaded child-first (parent-last) so it can",
        "                            carry its own dependencies. The main class may be omitted,",
        "                            in which case Main-Class from the jar manifest is used.",
        "                            JDK, Domino and launcher packages always stay shared with",
        "                            the parent loader: a second copy of the Domino classes",
        "                            would break the native runtime.",
        "  --share-package <prefix>  Additional package prefix to delegate to the parent loader.",
        "  --wait                    After main() returns, keep running until Ctrl+C. Required",
        "                            for servers, whose main() returns as soon as they start.",
        "  --cp, --classpath <path>  Additional classpath for the target program. May be",
        "                            repeated, and each value may be a separator-separated list.",
        "  --id <path>               Notes ID file to switch to before running.",
        "  --password <password>     Password for the ID file. Prefer the alternatives below,",
        "                            as command lines are visible to other processes.",
        "  --password-stdin          Read the ID password from the first line of stdin.",
        "  --prompt-password         Prompt for the ID password on the terminal.",
        "  --exec-dir <path>         Override Notes_ExecDirectory.",
        "  --notes-ini <path>        Override the notes.ini location.",
        "  -v, --verbose             Print environment and lifecycle diagnostics.",
        "  -h, --help                Show this help.",
        "",
        "The ID password may also be supplied through the " + ENV_ID_PASSWORD + " environment",
        "variable. If the ID is password protected, an alternative to passing a password is to",
        "enable 'Don't prompt for a password from other Notes-based programs' in the Notes",
        "client under File > Security > User Security, and leave the client running."));
  }
}
