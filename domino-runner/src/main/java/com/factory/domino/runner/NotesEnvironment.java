package com.factory.domino.runner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes the Notes/Domino runtime this JVM is about to bind to, and checks the
 * things that make the difference between "works" and an unexplained JVM crash.
 *
 * <p>The Notes runtime is loaded through JNI, so the JVM and the native client must agree on
 * platform, architecture and (on macOS) VM flavour. Getting that wrong does not raise a Java
 * exception: the process dies inside native code. Validating up front turns those crashes into
 * readable messages.
 */
public final class NotesEnvironment {

  /** Environment variable naming the directory holding the Notes binaries. */
  public static final String ENV_EXEC_DIR = "Notes_ExecDirectory";
  /** Environment variable naming the notes.ini to use. */
  public static final String ENV_NOTES_INI = "NotesINI";
  /** Environment variable naming the Notes data directory. */
  public static final String ENV_DIRECTORY = "Directory";

  private final String execDirectory;
  private final String notesIni;
  private final String dataDirectory;

  private NotesEnvironment(String execDirectory, String notesIni, String dataDirectory) {
    this.execDirectory = execDirectory;
    this.notesIni = notesIni;
    this.dataDirectory = dataDirectory;
  }

  /**
   * Builds the environment description from explicit values, falling back to the process
   * environment for anything not supplied.
   */
  public static NotesEnvironment detect(String execDirOverride, String notesIniOverride) {
    String execDir = firstNonBlank(execDirOverride, System.getenv(ENV_EXEC_DIR));
    String ini = firstNonBlank(notesIniOverride, System.getenv(ENV_NOTES_INI));
    String data = System.getenv(ENV_DIRECTORY);
    return new NotesEnvironment(execDir, ini, data);
  }

  public String getExecDirectory() {
    return execDirectory;
  }

  public String getNotesIni() {
    return notesIni;
  }

  public String getDataDirectory() {
    return dataDirectory;
  }

  public static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
  }

  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * Builds the argument array for {@code DominoProcess.initializeProcess(String[])}.
   *
   * <p>Order matters and is not what the HCL documentation suggests: JNX validates the first
   * argument as the program directory and only then accepts a second {@code =notes.ini}
   * argument. Passing the ini first fails with "program dir path does not exist".
   */
  public String[] toInitArgs() {
    List<String> args = new ArrayList<>();
    if (isNotBlank(execDirectory)) {
      args.add(execDirectory);
    }
    if (isNotBlank(notesIni)) {
      args.add("=" + notesIni);
    }
    return args.toArray(new String[0]);
  }

  /**
   * Verifies everything that must hold before the native library is touched.
   *
   * @throws IllegalStateException if the environment would crash or fail at load time
   */
  public void validate() {
    List<String> problems = new ArrayList<>();

    if (!isNotBlank(execDirectory)) {
      problems.add(ENV_EXEC_DIR + " is not set and no --exec-dir was given; "
          + "the Notes binaries cannot be located.");
    } else if (!new File(execDirectory).isDirectory()) {
      problems.add(ENV_EXEC_DIR + " points to '" + execDirectory + "', which is not a directory.");
    }

    if (isNotBlank(notesIni) && !new File(notesIni).isFile()) {
      problems.add("notes.ini '" + notesIni + "' does not exist.");
    }

    String libraryPath = System.getProperty("java.library.path", "");
    if (isNotBlank(execDirectory) && !libraryPath.contains(execDirectory)) {
      problems.add("java.library.path does not contain '" + execDirectory
          + "'; the JNI layer will not find the Notes native libraries. "
          + "Add -Djava.library.path and -Djna.library.path.");
    }

    // On macOS the Notes runtime requires an OpenJ9 JVM (IBM Semeru), as HCL documents.
    //
    // This was tested against the HotSpot JRE that the Notes 14.5 client ships (Temurin 21,
    // x86_64) on the theory that the requirement had lapsed. It had not: short runs succeed,
    // which makes the combination look supported, but real use crashes the process. Short
    // success is not evidence here — refuse up front instead.
    if (isMac()) {
      String vmName = System.getProperty("java.vm.name", "");
      if (!vmName.toLowerCase(Locale.ROOT).contains("openj9")) {
        problems.add("On macOS the Notes runtime requires an OpenJ9 JVM (IBM Semeru), "
            + "but this JVM is '" + vmName + "'. HotSpot JVMs — including the JRE bundled with "
            + "the Notes client — crash under real use.");
      }
    }

    // Architecture mismatch is the other silent killer: an arm64 JVM cannot load an
    // x86_64 libnotes.dylib, and vice versa.
    String jvmArch = System.getProperty("os.arch", "");
    String clientArch = detectClientArch();
    if (clientArch != null && !architecturesMatch(jvmArch, clientArch)) {
      problems.add("Architecture mismatch: this JVM is '" + jvmArch
          + "' but the Notes client binaries are '" + clientArch
          + "'. Run the launcher under a JVM matching the client.");
    }

    if (!problems.isEmpty()) {
      StringBuilder sb = new StringBuilder("Notes/Domino environment is not usable:");
      for (String p : problems) {
        sb.append(System.lineSeparator()).append("  - ").append(p);
      }
      throw new IllegalStateException(sb.toString());
    }
  }

  /**
   * Reads the Mach-O/ELF header of the main Notes library to determine what the client was
   * built for. Returns {@code null} when the architecture cannot be determined, in which case
   * the caller simply skips the check.
   */
  private String detectClientArch() {
    if (!isNotBlank(execDirectory)) {
      return null;
    }
    String libName = isMac() ? "libnotes.dylib" : isWindows() ? "nnotes.dll" : "libnotes.so";
    File lib = new File(execDirectory, libName);
    if (!lib.isFile()) {
      return null;
    }
    try (java.io.InputStream in = new java.io.FileInputStream(lib)) {
      byte[] header = in.readNBytes(20);
      if (header.length < 20) {
        return null;
      }
      if (isMac()) {
        // Mach-O 64-bit: magic 0xFEEDFACF (little endian on disk), cputype at offset 4.
        int cpuType = (header[4] & 0xFF) | ((header[5] & 0xFF) << 8)
            | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 24);
        // CPU_TYPE_X86_64 = 0x01000007, CPU_TYPE_ARM64 = 0x0100000C
        if (cpuType == 0x01000007) {
          return "x86_64";
        }
        if (cpuType == 0x0100000C) {
          return "aarch64";
        }
        return null;
      }
      if (!isWindows()) {
        // ELF: e_machine at offset 18. 0x3E = x86-64, 0xB7 = aarch64.
        int machine = (header[18] & 0xFF) | ((header[19] & 0xFF) << 8);
        if (machine == 0x3E) {
          return "x86_64";
        }
        if (machine == 0xB7) {
          return "aarch64";
        }
      }
      return null;
    } catch (java.io.IOException e) {
      return null;
    }
  }

  /** Normalises the many spellings of the same architecture across JVMs and toolchains. */
  private static boolean architecturesMatch(String jvmArch, String clientArch) {
    return normalizeArch(jvmArch).equals(normalizeArch(clientArch));
  }

  private static String normalizeArch(String arch) {
    String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
    if (a.contains("aarch64") || a.contains("arm64")) {
      return "aarch64";
    }
    if (a.contains("x86_64") || a.contains("amd64") || a.contains("x64")) {
      return "x86_64";
    }
    return a;
  }

  /** Renders the environment for {@code --verbose} diagnostics. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("  JVM             : ").append(System.getProperty("java.vm.name"))
        .append(' ').append(System.getProperty("java.version"))
        .append(" (").append(System.getProperty("os.arch")).append(')')
        .append(System.lineSeparator());
    sb.append("  ").append(ENV_EXEC_DIR).append(" : ").append(execDirectory)
        .append(System.lineSeparator());
    sb.append("  notes.ini       : ").append(isNotBlank(notesIni) ? notesIni : "<default>")
        .append(System.lineSeparator());
    sb.append("  data directory  : ").append(isNotBlank(dataDirectory) ? dataDirectory : "<default>")
        .append(System.lineSeparator());
    sb.append("  java.library.path: ").append(System.getProperty("java.library.path"));
    return sb.toString();
  }

  private static boolean isNotBlank(String s) {
    return s != null && !s.trim().isEmpty();
  }

  private static String firstNonBlank(String a, String b) {
    return isNotBlank(a) ? a : b;
  }
}
