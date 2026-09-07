# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A launcher (`domino-runner`) that initializes the HCL Notes/Domino native runtime and then runs
an arbitrary Java `main` class inside the same JVM, on a thread that already holds a live Domino
context. Hosted programs use both the classic `lotus.domino` API (Notes.jar) and Domino JNX
without doing any setup of their own.

Target platform: HCL Notes/Domino **14.5.1**, **Java 21**, Domino JNX `1.47.0` release line
`r145`. All code, comments, documentation and commit messages are in **English**.

macOS is the implemented and verified platform. The Linux and Windows launch scripts exist and
mirror the macOS one, but have not been run.

## Commands

Everything routine goes through the `Justfile` (`just` with no arguments lists the recipes):

```bash
just setup-jvm            # download IBM Semeru x86_64 OpenJ9 21 into .jvm/ (macOS, once)
just build                # JAVA_HOME=.jvm/... mvn -B package  (all modules)
just build-quick          # same, -q and skipping tests
just clean
just run-sample [limit]   # DominoInfoSample: Notes.jar session info + JNX database list
just run-designer [args]  # start the DominoWebDesigner sample (--wait)
just run <args...>        # arbitrary: <main-class> or --jar <path> [--wait]
just env                  # print the environment the launch script resolved, run nothing
just stop [port]          # POST /api/shutdown — the only reliable way to stop a hosted server
just package              # target/limitless-domino-1.0.0.zip + .sha256
just verify-package       # unpack the zip into a scratch dir and run `limitless-domino version`
```

Raw Maven works too, but `JAVA_HOME` must point at the Semeru JVM:
`JAVA_HOME=$PWD/.jvm/jdk-21.0.12+8/Contents/Home mvn package`. A single module:
`mvn -pl samples/domino-web-designer package`.

**There is no test suite and no linter configured.** Verification is done by running things:
`limitless-domino validate` (`DominoDoctor`, exit codes 10 environment / 11 dependencies /
12 runtime / 13 credentials, `--json` for machine-readable output), then `just run-sample`, then
the designer in a browser. Anything touching the runtime must be verified against a real,
running Notes client — a compile is not evidence.

## Hard constraints (each one produces a native crash, not a Java exception)

1. **macOS needs an x86_64 OpenJ9 JVM (IBM Semeru).** The client ships x86_64 binaries, so an
   arm64 JVM cannot load them through JNI; and the runtime requires OpenJ9 — HotSpot crashes.
   The JRE bundled with the Notes client is HotSpot: short runs succeed convincingly and it
   fails under real use, so `NotesEnvironment.validate()` and the launch scripts reject it.
   **A run that completes is not evidence that a JVM pairing is sound.**
2. **Exactly one copy of the Domino classes per process.** `IsolatedJarClassLoader` loads the
   `--jar` application child-first but always delegates JDK, `com.hcl.domino.*`, `com.sun.jna.*`,
   `lotus.*` and `com.factory.domino.runner.*` to the parent. A second copy would re-bind the
   native libraries and raise `ClassCastException` between same-named classes. Hosted modules
   therefore declare JNX and `domino-runner` as `provided`.
3. **Every thread touching Domino needs its own initialized context**, and a `DominoClient`
   belongs to its creating thread. `DominoExecutor` owns one single-threaded executor per worker
   precisely so `terminateThread()` runs on the thread that initialized the context.
4. **Signals do not work.** Initializing the runtime installs native handlers that swallow
   SIGINT, SIGTERM and SIGQUIT; only SIGKILL stops the process, and that skips all cleanup.
   Long-running programs must expose an explicit stop that calls
   `RunnerLifecycle.requestShutdown()`.

## Architecture

### `domino-runner` — the launcher

| Class | Role |
|---|---|
| `DominoRunner` | Entry point: parses options, validates the environment, `initializeProcess()` → `initializeThread()` → reflective `main()` invocation, then `terminateProcess()` in a `finally`. `--wait` blocks on a latch instead of returning. |
| `NotesEnvironment` | Detects `Notes_ExecDirectory` / `NotesINI` / `Directory`, and validates arch, VM flavour and library paths *before* anything native is loaded. Reads the Mach-O/ELF header of `libnotes` to learn the client's architecture. |
| `IsolatedJarClassLoader` | Parent-last for the application jar, with a fixed shared-prefix list (`--share-package` adds to it). Separation, not a sandbox: `SecurityManager` is gone, hosted code has launcher privileges. |
| `DominoExecutor` | Public API for hosted programs: `call(Function<DominoClient,T>)` / `run(Consumer<DominoClient>)` on Domino-capable worker threads. |
| `RunnerLifecycle` | Public API: `onShutdown()` registers cleanup the launcher runs *before* releasing the runtime (a JVM hook would race the teardown); `requestShutdown()` triggers an orderly stop. |
| `DominoDoctor` | `limitless-domino validate`. Runs every check even after one fails, ordered shallowest-first; identity/password-sharing is checked before anything touches data, because without a running client the runtime blocks on a password prompt. |

The launch scripts (`src/main/scripts/`) are not incidental: they set the native environment
(`Notes_ExecDirectory`, `DYLD_LIBRARY_PATH`, `PATH`, `NotesINI`, `Directory`), pick a suitable
JVM, build the classpath with the client's `Notes.jar` last, and add the `--add-exports` /
`--add-opens` JNX needs. `DYLD_*` is exported inside the script because macOS SIP strips it from
a parent process. `limitless-domino` is the user-facing entry point that dispatches to the
per-platform script. Maven resource filtering is off for these files on purpose — they use
`${VAR}` shell syntax.

### `samples/domino-web-designer` — the worked example

A Javalin app packaged as a fat jar and loaded with `--jar`: REST + UI over databases, views,
forms, documents and DXL. It demonstrates every rule above (provided-scope Domino deps,
`DominoExecutor`, ordered `RunnerLifecycle` teardown, `POST /api/shutdown`), plus reaching the
classic API from a hosted jar (`NoteSigner`, which works only because `lotus.domino` is a shared
prefix). Writing is opt-in behind `--allow-write`, dry-run by default, with a DXL backup taken
before every import.

When building anything new that runs under the runner, use the **`limitless-runner-builder`**
skill (`.claude/skills/`) — it carries the module template, the shade configuration, and a
symptom→cause table.

## Dependency and build details worth knowing

- **`Notes.jar` is `system`-scoped** (`notes.jar.path`, set per-OS by profiles in
  `domino-runner/pom.xml`). It is not redistributable and must match the installed client, so it
  is never bundled; the scripts add the client's own copy at runtime.
- **`ServicesResourceTransformer` is mandatory** in every shade configuration: JNX and Jetty both
  resolve implementations through `META-INF/services`.
- **Dependencies that are `provided`/`optional` upstream but required at runtime**, and so are
  declared explicitly: `angus-mail` (else `getDesign()` fails with `jakarta.mail.MessagingException`),
  `glassfish-corba-omgapi` (`lotus.domino.NotesException` extends `org.omg.CORBA.UserException`,
  removed from the JDK in Java 11), and on the Javalin side `jackson-databind` and an SLF4J provider.
- **`jnx.release` in the parent POM** (`r145` / `r14` / `r12`) selects the JNX artifact line and
  must match the installed client.
- Two version couplings that are easy to miss: the Justfile's `project_version` (dist zip name and
  the `VERSION` file the installer reads) is independent of the Maven version, and
  `DominoWebDesigner.SWAGGER_UI_VERSION` must track the `org.webjars:swagger-ui` version because
  it is part of the classpath resource path.

## Conventions

Comments explain *why*, not what, and specifically record what was tried and failed — the
codebase is documentation of a set of native-runtime traps, and the class-level Javadoc carries
that weight. Keep that when editing: if a constraint was discovered empirically, say so and say
how it manifests. The same material is recorded for users in `README.md` ("Notes on the
implementation"); a change that invalidates one of those entries should update it.

Two-space indentation, Google-style Java layout, `final` classes with private constructors for
static-only holders.
