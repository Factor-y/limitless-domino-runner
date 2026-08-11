package com.factory.domino.runner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Checks that the Notes/Domino runtime is actually usable from here, and says what is wrong when
 * it is not.
 *
 * <p>Run through the launcher: {@code limitless-domino validate}.
 *
 * <p>The checks are ordered from the shallowest to the deepest, because that is the order in
 * which things break, and each one exists because it has already cost time in practice: an
 * architecture or VM mismatch kills the process in native code with no Java stack trace; the
 * dependencies JNX declares as {@code provided} are needed the moment design is read; and a
 * missing password-sharing setting makes the runtime block forever waiting for input that has
 * nowhere to be typed.
 *
 * <p>Every check runs even after an earlier one fails — a diagnostic that stops at the first
 * problem hides the rest of the picture. The exit code says which category failed, so the
 * installer and scripts can act on it.
 */
public final class DominoDoctor {

  /** Exit codes, so callers can distinguish what went wrong without parsing text. */
  public static final int EXIT_OK = 0;
  public static final int EXIT_ENVIRONMENT = 10;
  public static final int EXIT_DEPENDENCIES = 11;
  public static final int EXIT_RUNTIME = 12;
  public static final int EXIT_CREDENTIALS = 13;

  private enum Status { PASS, FAIL, WARN, SKIP }

  private record Check(String name, Status status, String detail, int exitCode) {
  }

  private final List<Check> checks = new ArrayList<>();
  private boolean json;

  public static void main(String[] args) {
    DominoDoctor doctor = new DominoDoctor();
    for (String arg : args) {
      if ("--json".equals(arg)) {
        doctor.json = true;
      } else if ("--help".equals(arg) || "-h".equals(arg)) {
        System.out.println("Usage: limitless-domino validate [--json]");
        return;
      }
    }
    System.exit(doctor.run());
  }

  private int run() {
    if (!json) {
      System.out.println();
      System.out.println("Limitless Domino Runner - validation");
      System.out.println("-".repeat(72));
    }
    checkJvm();
    checkClient();
    checkNotesJar();
    checkDependencies();
    // Deliberately before the checks that touch data: without a running client the runtime
    // prompts for the ID password and blocks, so the reason has to be on screen first.
    checkIdentityAndSharing();
    checkDominoRuntime();
    checkClassicSession();
    checkDataAccess();
    checkDesignAccess();

    return report();
  }

  // --- 1. the JVM itself -------------------------------------------------------------

  private void checkJvm() {
    String vmName = System.getProperty("java.vm.name", "?");
    String version = System.getProperty("java.version", "?");
    String arch = System.getProperty("os.arch", "?");
    String os = System.getProperty("os.name", "?");

    add("Operating system", Status.PASS, os + " (" + arch + ")", EXIT_OK);

    int major = majorVersion(version);
    if (major >= 21) {
      add("Java version", Status.PASS, version + " (>= 21 as JNX requires)", EXIT_OK);
    } else {
      add("Java version", Status.FAIL,
          version + " — Domino JNX ships Java 21 bytecode and will not load", EXIT_ENVIRONMENT);
    }

    // On macOS the Notes runtime needs OpenJ9. The client's own bundled JRE is HotSpot and
    // passes short tests before failing in real use, so this is checked rather than assumed.
    if (NotesEnvironment.isMac()) {
      if (vmName.toLowerCase(Locale.ROOT).contains("openj9")) {
        add("JVM flavour", Status.PASS, vmName + " (OpenJ9, required on macOS)", EXIT_OK);
      } else {
        add("JVM flavour", Status.FAIL, vmName
            + " — macOS needs an OpenJ9 JVM (IBM Semeru); HotSpot crashes under real use",
            EXIT_ENVIRONMENT);
      }
    } else {
      add("JVM flavour", Status.PASS, vmName, EXIT_OK);
    }
  }

  // --- 2. the client installation ----------------------------------------------------

  private void checkClient() {
    NotesEnvironment environment = NotesEnvironment.detect(null, null);
    String execDir = environment.getExecDirectory();

    if (execDir == null || execDir.isBlank()) {
      add("Notes program directory", Status.FAIL,
          "Notes_ExecDirectory is not set; the launcher normally sets it", EXIT_ENVIRONMENT);
      return;
    }
    if (!new File(execDir).isDirectory()) {
      add("Notes program directory", Status.FAIL, execDir + " does not exist", EXIT_ENVIRONMENT);
      return;
    }
    add("Notes program directory", Status.PASS, execDir, EXIT_OK);

    String libraryName = NotesEnvironment.isMac() ? "libnotes.dylib"
        : NotesEnvironment.isWindows() ? "nnotes.dll" : "libnotes.so";
    File library = new File(execDir, libraryName);
    if (library.isFile()) {
      add("Native library", Status.PASS, libraryName + " ("
          + (library.length() / (1024 * 1024)) + " MB)", EXIT_OK);
    } else {
      add("Native library", Status.FAIL, libraryName + " not found in " + execDir,
          EXIT_ENVIRONMENT);
    }

    // The environment object performs the architecture comparison; a mismatch here is the
    // single most common cause of a silent native death.
    try {
      environment.validate();
      add("Architecture match", Status.PASS,
          "JVM and client binaries agree (" + System.getProperty("os.arch") + ")", EXIT_OK);
    } catch (IllegalStateException e) {
      add("Architecture match", Status.FAIL, firstLine(e.getMessage()), EXIT_ENVIRONMENT);
    }

    String libraryPath = System.getProperty("java.library.path", "");
    add("java.library.path", libraryPath.contains(execDir) ? Status.PASS : Status.FAIL,
        libraryPath.contains(execDir) ? "includes the program directory"
            : "does not include " + execDir + "; JNI will not find the libraries",
        EXIT_ENVIRONMENT);
  }

  // --- 3. Notes.jar ------------------------------------------------------------------

  private void checkNotesJar() {
    Optional<String> location = locationOf("lotus.domino.Session");
    if (location.isPresent()) {
      add("Notes.jar", Status.PASS, location.get(), EXIT_OK);
    } else {
      add("Notes.jar", Status.FAIL,
          "lotus.domino.Session not on the classpath — it must come from the client install "
              + "and is never bundled", EXIT_DEPENDENCIES);
    }
  }

  // --- 4. the dependencies that are easy to miss -------------------------------------

  private void checkDependencies() {
    // Each of these has failed in practice, and none of them is obvious from JNX's POM:
    // they are declared provided or optional, so Maven does not bring them along.
    requireClass("Domino JNX", "com.hcl.domino.DominoClient", null);
    requireClass("JNA", "com.sun.jna.Native", null);
    requireClass("CORBA (for Notes.jar)", "org.omg.CORBA.UserException",
        "lotus.domino.NotesException extends it, and the JDK dropped CORBA in Java 11");
    requireClass("Jakarta Mail", "jakarta.mail.MessagingException",
        "JNX declares it provided but needs it to read design elements");
    requireClass("Jakarta Activation", "jakarta.activation.DataSource", null);
  }

  private void requireClass(String label, String className, String why) {
    if (locationOf(className).isPresent()) {
      add(label, Status.PASS, className, EXIT_OK);
    } else {
      add(label, Status.FAIL, className + " not found"
          + (why == null ? "" : " — " + why), EXIT_DEPENDENCIES);
    }
  }

  // --- 5. the Domino runtime ---------------------------------------------------------

  private void checkDominoRuntime() {
    starting("Domino process");
    // The launcher initialized the process and this thread before handing over, so reaching
    // this point at all already proves both worked.
    try {
      Object process = com.hcl.domino.DominoProcess.get();
      add("Domino process", Status.PASS,
          "initialized by the launcher (" + process.getClass().getSimpleName() + ")", EXIT_OK);
    } catch (Throwable e) {
      add("Domino process", Status.FAIL, describe(e), EXIT_RUNTIME);
      return;
    }

    try (com.hcl.domino.DominoClient client =
        com.hcl.domino.DominoClientBuilder.newDominoClient().asIDUser().build()) {
      add("DominoClient", Status.PASS, "effective user " + client.getEffectiveUserName(),
          EXIT_OK);
      add("Server build", Status.PASS, String.valueOf(client.getBuildVersion("")), EXIT_OK);
    } catch (Throwable e) {
      add("DominoClient", Status.FAIL, describe(e), EXIT_RUNTIME);
    }
  }

  // --- 6. the classic API ------------------------------------------------------------

  private void checkClassicSession() {
    starting("Notes.jar session");
    try {
      lotus.domino.NotesThread.sinitThread();
      try {
        lotus.domino.Session session = lotus.domino.NotesFactory.createSession();
        try {
          add("Notes.jar session", Status.PASS,
              session.getNotesVersion() + " as " + session.getUserName(), EXIT_OK);
        } finally {
          session.recycle();
        }
      } finally {
        lotus.domino.NotesThread.stermThread();
      }
    } catch (Throwable e) {
      add("Notes.jar session", Status.FAIL, describe(e), EXIT_RUNTIME);
    }
  }

  // --- 7. data access ----------------------------------------------------------------

  private void checkDataAccess() {
    starting("Local databases");
    try {
      Optional<Long> count = withTimeout("databases", 25, () -> {
        try (com.hcl.domino.DominoClient client =
            com.hcl.domino.DominoClientBuilder.newDominoClient().asIDUser().build()) {
          return client.openDbDirectory()
              .query()
              .withServer("")
              .withFileTypes(java.util.EnumSet.of(
                  com.hcl.domino.dbdirectory.FileType.DBANY,
                  com.hcl.domino.dbdirectory.FileType.RECURSE))
              .stream()
              .count();
        }
      });
      if (count.isEmpty()) {
        add("Local databases", Status.FAIL, TIMED_OUT, EXIT_CREDENTIALS);
      } else {
        add("Local databases", count.get() > 0 ? Status.PASS : Status.WARN,
            count.get() + " found in the data directory", EXIT_OK);
      }
    } catch (Throwable e) {
      add("Local databases", Status.FAIL, describe(e), EXIT_RUNTIME);
    }
  }

  // --- 8. design access --------------------------------------------------------------

  private void checkDesignAccess() {
    starting("Design access");
    // Reading design is what surfaced the missing Jakarta Mail dependency: everything else
    // worked, and only this failed. Worth its own check for that reason.
    try {
      Optional<String> result = withTimeout("design", 25, () -> {
        try (com.hcl.domino.DominoClient client =
            com.hcl.domino.DominoClientBuilder.newDominoClient().asIDUser().build()) {
          Optional<String> candidate = client.openDbDirectory()
              .query()
              .withServer("")
              .withFileTypes(java.util.EnumSet.of(com.hcl.domino.dbdirectory.FileType.DBANY))
              .stream()
              .map(entry -> entry.getFilePath())
              .findFirst();
          if (candidate.isEmpty()) {
            return "";
          }
          long views = client.openDatabase("", candidate.get()).getDesign().getViews().count();
          return views + " views readable in " + candidate.get();
        }
      });
      if (result.isEmpty()) {
        add("Design access", Status.FAIL, TIMED_OUT, EXIT_CREDENTIALS);
      } else if (result.get().isEmpty()) {
        add("Design access", Status.SKIP, "no local database to test with", EXIT_OK);
      } else {
        add("Design access", Status.PASS, result.get(), EXIT_OK);
      }
    } catch (Throwable e) {
      add("Design access", Status.FAIL, describe(e)
          + " — a NoClassDefFoundError here usually means a missing provided dependency",
          EXIT_DEPENDENCIES);
    }
  }

  // --- 9. identity, and whether a password will be needed ----------------------------

  private void checkIdentityAndSharing() {
    starting("notes.ini");
    String notesIni = System.getenv(NotesEnvironment.ENV_NOTES_INI);
    String keyFile = null;
    if (notesIni != null && !notesIni.isBlank()) {
      keyFile = readIniValue(Paths.get(notesIni), "KeyFileName");
      add("notes.ini", keyFile != null ? Status.PASS : Status.WARN,
          notesIni + (keyFile != null ? " (ID: " + keyFile + ")" : " — KeyFileName not found"),
          EXIT_OK);
    } else {
      add("notes.ini", Status.WARN, "NotesINI is not set", EXIT_OK);
    }

    // Without a running client the runtime prompts for the ID password on a console that a
    // background process does not have, and simply hangs. This is the check that explains
    // an otherwise silent stall.
    boolean clientRunning = isNotesClientRunning();
    add("Notes client running", clientRunning ? Status.PASS : Status.WARN,
        clientRunning
            ? "yes — password sharing can serve the ID password"
            : "no — if the ID is password protected the runtime will block waiting for it. "
                + "Either start Notes with 'Don't prompt for a password from other Notes-based "
                + "programs' enabled, or pass --id with a password",
        EXIT_OK);
  }

  /**
   * Looks for a running Notes client among the current user's processes.
   *
   * <p>Uses {@link ProcessHandle} rather than shelling out, so it behaves the same on every
   * platform and needs no external command.
   */
  private static boolean isNotesClientRunning() {
    try {
      return ProcessHandle.allProcesses()
          .map(handle -> handle.info().command().orElse(""))
          .anyMatch(command -> command.contains("HCL Notes.app")
              || command.endsWith("/notes") || command.endsWith("nlnotes.exe")
              || command.endsWith("notes2.exe"));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String readIniValue(Path iniFile, String key) {
    try {
      for (String line : Files.readAllLines(iniFile, java.nio.charset.StandardCharsets.ISO_8859_1)) {
        if (line.regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
          return line.substring(key.length() + 1).trim();
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  // --- reporting ---------------------------------------------------------------------

  private void add(String name, Status status, String detail, int exitCode) {
    Check check = new Check(name, status, detail, status == Status.FAIL ? exitCode : EXIT_OK);
    checks.add(check);
    // Printed as it happens, not collected for the end: a check that hangs — and against a
    // native runtime some can — must leave the last line it reached on screen, otherwise the
    // diagnostic tells you nothing precisely when you need it most.
    if (!json) {
      System.out.printf("  %s  %-24s %s%n", symbol(status), name, detail);
      System.out.flush();
    }
  }

  /**
   * Runs work on a Domino-capable thread and gives up after a while.
   *
   * <p>Needed because a blocked Domino call does not fail: with no running client the runtime
   * prints "Enter password" and waits forever on a console a background process does not have.
   * A diagnostic that hangs is worse than one that reports a timeout, so the wait is bounded
   * and the thread is a daemon — it may stay stuck in native code, but it will not keep the
   * process alive.
   */
  private <T> Optional<T> withTimeout(String label, int seconds,
      java.util.function.Supplier<T> work) {
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
          Thread thread = new Thread(runnable, "doctor-" + label);
          thread.setDaemon(true);
          return thread;
        });
    try {
      java.util.concurrent.Future<T> future = executor.submit(() -> {
        // Its own thread, so its own Domino context (see DominoExecutor for why).
        try (com.hcl.domino.DominoProcess.DominoThreadContext ignored =
            com.hcl.domino.DominoProcess.get().initializeThread()) {
          return work.get();
        }
      });
      return Optional.ofNullable(future.get(seconds, java.util.concurrent.TimeUnit.SECONDS));
    } catch (java.util.concurrent.TimeoutException e) {
      return Optional.empty();
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
    } finally {
      executor.shutdownNow();
    }
  }

  /** The message shown when a Domino call does not come back. */
  private static final String TIMED_OUT =
      "timed out — the runtime is most likely waiting for the ID password. Start the Notes "
          + "client with \"Don't prompt for a password from other Notes-based programs\" "
          + "enabled, or pass --id with a password";

  /** Announces a check that is about to run, so a hang is attributable. */
  private void starting(String name) {
    if (!json) {
      System.out.print("  [ .. ]  " + name + "\r");
      System.out.flush();
    }
  }

  private int report() {
    int worstExit = EXIT_OK;
    int failures = 0;
    int warnings = 0;
    for (Check check : checks) {
      if (check.status() == Status.FAIL) {
        failures++;
        // The first failure decides the category: the shallowest problem is the real cause.
        if (worstExit == EXIT_OK) {
          worstExit = check.exitCode();
        }
      } else if (check.status() == Status.WARN) {
        warnings++;
      }
    }

    if (json) {
      printJson(failures, warnings, worstExit);
      return worstExit;
    }

    System.out.println("-".repeat(72));
    if (failures == 0 && warnings == 0) {
      System.out.println("  All checks passed. The Domino runtime is ready to use.");
    } else if (failures == 0) {
      System.out.println("  Usable, with " + warnings + " warning(s) worth reading above.");
    } else {
      System.out.println("  " + failures + " check(s) failed"
          + (warnings > 0 ? " and " + warnings + " warning(s)" : "")
          + ". The first failure is normally the one to fix.");
    }
    System.out.println();
    return worstExit;
  }

  private void printJson(int failures, int warnings, int exitCode) {
    StringBuilder out = new StringBuilder("{\n");
    out.append("  \"ok\": ").append(failures == 0).append(",\n");
    out.append("  \"failures\": ").append(failures).append(",\n");
    out.append("  \"warnings\": ").append(warnings).append(",\n");
    out.append("  \"exitCode\": ").append(exitCode).append(",\n");
    out.append("  \"checks\": [\n");
    for (int i = 0; i < checks.size(); i++) {
      Check check = checks.get(i);
      out.append("    {\"name\": \"").append(escape(check.name()))
          .append("\", \"status\": \"").append(check.status())
          .append("\", \"detail\": \"").append(escape(check.detail())).append("\"}")
          .append(i < checks.size() - 1 ? "," : "").append('\n');
    }
    out.append("  ]\n}");
    System.out.println(out);
  }

  private static String symbol(Status status) {
    return switch (status) {
      case PASS -> "[ ok ]";
      case FAIL -> "[FAIL]";
      case WARN -> "[warn]";
      case SKIP -> "[skip]";
    };
  }

  /** Where a class was loaded from, or empty when it cannot be loaded at all. */
  private static Optional<String> locationOf(String className) {
    try {
      Class<?> type = Class.forName(className, false,
          DominoDoctor.class.getClassLoader());
      java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
      if (source != null && source.getLocation() != null) {
        String path = source.getLocation().getPath();
        return Optional.of(path.substring(path.lastIndexOf('/') + 1));
      }
      return Optional.of("present");
    } catch (Throwable e) {
      return Optional.empty();
    }
  }

  private static int majorVersion(String version) {
    try {
      String major = version.split("[.\\-+]")[0];
      return Integer.parseInt(major);
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static String describe(Throwable e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName() + (message == null ? "" : ": " + firstLine(message));
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "";
    }
    int newline = text.indexOf('\n');
    String line = newline < 0 ? text : text.substring(0, newline);
    return line.length() > 160 ? line.substring(0, 157) + "..." : line.trim();
  }

  private static String escape(String text) {
    return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
