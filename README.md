# Limitless Domino Runner

A launcher that initializes the HCL Notes/Domino runtime and then runs arbitrary Java programs
inside it. The target program runs in the same JVM, on a thread that already holds an active
Domino context, so it can use both the classic `lotus.domino` API (Notes.jar) and
[Domino JNX](https://opensource.hcltechsw.com/domino-jnx/) without doing any setup of its own.

## Modules

| Module | What it is |
|---|---|
| `domino-runner` | The launcher, plus `DominoInfoSample` as a built-in example |
| `samples/domino-web-designer` | **DominoWebDesigner** — a Javalin web app with REST APIs and a UI for browsing Domino databases, packaged as a fat jar and loaded with `--jar` |

## Status

macOS is implemented and verified end to end against a local HCL Notes 14.5 client, including
DominoWebDesigner in Chrome. Linux and Windows scripts are provided and follow the same structure,
but have not been run.

## Requirements

| | |
|---|---|
| Notes/Domino client | Installed locally; the runner binds to its native libraries |
| Java | 21 or later — Domino JNX 1.47.0 ships Java 21 bytecode |
| Maven | 3.8+ |

### macOS: the three constraints that actually matter

The macOS client is the most constrained platform, and getting any of these wrong produces a
native crash rather than a Java exception:

1. **Architecture.** The macOS Notes client ships **x86_64** binaries. On Apple Silicon an
   arm64 JVM cannot load them through JNI. You need an x86_64 JVM, which runs under Rosetta 2.
2. **VM flavour.** The Notes runtime on macOS requires an **OpenJ9** JVM (IBM Semeru).
   HotSpot-based JVMs — Temurin, Oracle, Zulu, Corretto — crash.
3. **Library paths.** `Notes_ExecDirectory`, `java.library.path` and `jna.library.path` must all
   point at `HCL Notes.app/Contents/MacOS`.

The launch script enforces all three and refuses to start with a readable message rather than
letting the process die in native code.

**Do not use the JRE bundled with the Notes client.** Notes 14.5 ships its own JRE at
`Contents/jre/Contents/Home`, and it is tempting: already installed, x86_64, Java 21. It is,
however, **HotSpot** (Temurin 21), and it was tried here — short runs succeed convincingly
(process and thread init, `DominoClient`, database listing, design access, even the web app
under concurrent load), which makes the combination look supported. Under real use it crashes.
The launch script therefore rejects non-OpenJ9 JVMs outright, the bundled one included.

The lesson generalises: with a JNI-loaded native runtime, a run that completes is not evidence
that the pairing is sound.

## Setup

### 1. Install an x86_64 OpenJ9 JVM (macOS)

```bash
mkdir -p .jvm && cd .jvm
curl -LO https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.12.0/ibm-semeru-open-jdk_x64_mac_21.0.12.0.tar.gz
tar xzf ibm-semeru-open-jdk_x64_mac_21.0.12.0.tar.gz
```

A JVM placed under `.jvm/` anywhere up the directory tree is found automatically. To use one
from elsewhere, set `DOMINO_RUNNER_JAVA_HOME` to its `Contents/Home` directory.

### 2. Build

```bash
JAVA_HOME=$PWD/.jvm/jdk-21.0.12+8/Contents/Home mvn package
```

This produces two distribution layouts:

- `target/dist/` — exploded, with `bin/` scripts and `lib/` dependencies
- `target/limitless-domino-runner-1.0.0-SNAPSHOT-all.jar` — a self-contained shaded jar

`Notes.jar` is deliberately excluded from both: it is not redistributable and must match the
installed client exactly, so the scripts pick it up from the installation at runtime.

### 3. Run the sample

```bash
./target/dist/bin/domino-runner-macos.sh com.factory.domino.samples.DominoInfoSample
```

Expected output:

```
=== Notes.jar (lotus.domino) ===
  Product version : Build V1451FP1_06262026|June 26, 2026
  User name       : CN=Daniele Vistalli/O=Factor-y/C=IT
  Platform        : Macintosh/64
  Common user name: Daniele Vistalli

=== Domino JNX (local databases) ===
  Databases found : 10

  names.nsf                                Vistalli's Contacts
  bookmark.nsf                             Bookmarks (12.0.1)
  ...
```

### 4. Run DominoWebDesigner

```bash
./domino-runner/target/dist/bin/domino-runner-macos.sh \
  --jar samples/domino-web-designer/target/domino-web-designer.jar --wait
```

Then open <http://127.0.0.1:8080/>. The UI lists the databases on a server (empty = local) and,
for the selected one, shows details, views, forms, and the items of a document, named document
or profile document.

REST APIs, all read-only:

```
GET /api/databases?server=
GET /api/database?server=&db=              db = file path or replica ID
GET /api/database/views?server=&db=
GET /api/database/forms?server=&db=
GET /api/document?server=&db=&noteid=|&unid=
GET /api/document/named?server=&db=&name=&username=
GET /api/documents/named?server=&db=       lists the available named documents
GET  /api/database/form?server=&db=&form=   full design of one form: fields, formulas, events
GET  /api/document/profile?server=&db=&profile=&username=
POST /api/shutdown                          stops the server and the runner cleanly
```

**Interactive documentation** is at `/swagger/`, served from a bundled Swagger UI webjar (no CDN,
so it works offline) against the OpenAPI 3 spec at `/openapi.json`. "Try it out" is enabled.

**Form design** (`/api/database/form`) returns each field with its data type, kind
(editable/computed/…), display type, and its default-value, input-translation, validation and
keyword formulas, plus choices, HTML attributes and per-field LotusScript — alongside the form's
own properties, formulas, events, subform references and LotusScript. In the UI, click a form to
inspect it and a field name to expand its full definition inline.

**mDNS/Bonjour**: the app announces itself on the network at startup. One detail decides what
actually works here: mDNS resolves names only in the `.local` domain (RFC 6762), so a name like
`domino.designer` can never be resolved by mDNS however it is registered. The announcer therefore
does both — it registers the *service instance* under the requested name (what Bonjour service
browsers show) and a resolvable *host name* derived from it, `domino.designer` →
**`domino-designer.local`**, which is what you can type into a browser. Verified:
`http://domino-designer.local:8080/` resolves and serves.

Because the server binds to loopback by default, that name resolves to 127.0.0.1 and is only
reachable from this machine; `--host 0.0.0.0` makes discovery meaningful across the network.
Options: `--mdns-name <name>` to change the advertised name, `--no-mdns` to disable.

Database listing is **recursive by default** — Domino's directory search is flat unless
`FileType.RECURSE` is set, so without it everything in subdirectories is silently missing (10
databases instead of 27 on this machine). Pass `recursive=false` for the flat listing. The UI
also filters the loaded list client-side by title or file name.

Server and database are query parameters rather than path segments because Domino paths contain
`/` and `\` for subdirectories, which as path segments would need fragile double-encoding.

Options: `--port` (default 8080), `--host` (default 127.0.0.1), `--domino-threads` (default 4),
`--mdns-name` (default `domino.designer`), `--no-mdns`.
Everything is served with the runner's Notes identity, for local and remote servers alike, and
the server binds to loopback only — there is no authentication in front of it.

## Usage

```
domino-runner-macos.sh [options] <main-class> [program arguments...]
domino-runner-macos.sh [options] --jar <app.jar> [--wait] [program arguments...]
```

| Option | Purpose |
|---|---|
| `--jar <path>` | Application jar loaded child-first; main class may come from its manifest |
| `--share-package <prefix>` | Extra package prefix to delegate to the parent loader |
| `--wait` | Keep running after `main` returns, until Ctrl+C (required for servers) |
| `--cp`, `--classpath <path>` | Additional classpath for the target program; repeatable |
| `--id <path>` | Notes ID file to switch to before running |
| `--password <password>` | ID password (visible in the process list — prefer the options below) |
| `--password-stdin` | Read the ID password from the first line of stdin |
| `--prompt-password` | Prompt for the ID password on the terminal |
| `--exec-dir <path>` | Override `Notes_ExecDirectory` |
| `--notes-ini <path>` | Override the notes.ini location |
| `-v`, `--verbose` | Print environment and lifecycle diagnostics |
| `-h`, `--help` | Show usage |

Running your own program off the runner's classpath:

```bash
./target/dist/bin/domino-runner-macos.sh --cp /path/to/my-program.jar com.example.MyProgram arg1
```

### Using the shaded jar directly

The shaded jar is self-contained except for `Notes.jar`, but it does **not** carry the native
environment: launching it yourself means reproducing what the script does. All of these matter —
omitting `PATH` in particular makes the Notes runtime log repeated
*"Cannot write or create file (file or disk is read-only)"* warnings.

```bash
NOTES="/Applications/HCL Notes.app/Contents/MacOS"
export Notes_ExecDirectory="$NOTES"
export DYLD_LIBRARY_PATH="$NOTES"
export PATH="$NOTES:$PATH"
export NotesINI="$HOME/Library/Preferences/Notes Preferences"
export Directory="$HOME/Library/Application Support/HCL Notes Data"

.jvm/jdk-21.0.12+8/Contents/Home/bin/java \
  -Djava.library.path="$NOTES" -Djna.library.path="$NOTES" \
  -cp "target/limitless-domino-runner-1.0.0-SNAPSHOT-all.jar:$NOTES/../Resources/ndext/Notes.jar" \
  com.factory.domino.runner.DominoRunner com.factory.domino.samples.DominoInfoSample
```

Using the script is preferable; it validates the environment before anything native is loaded.

### Environment variables

| Variable | Meaning |
|---|---|
| `DOMINO_RUNNER_JAVA_HOME` | JVM to use (macOS: must be x86_64 OpenJ9, Java 21+) |
| `NOTES_APP` | Path to `HCL Notes.app` (macOS) |
| `DOMINO_PROGRAM_DIR` | Program directory (Linux/Windows) |
| `NOTES_DATA` | Notes data directory |
| `NOTES_INI` | notes.ini location (`Notes Preferences` on macOS) |
| `DOMINO_ID_PASSWORD` | ID password, if the ID is password protected |
| `DOMINO_RUNNER_EXTRA_CP` | Extra classpath entries added to the JVM |
| `DOMINO_RUNNER_JAVA_OPTS` | Extra JVM options |
| `DOMINO_RUNNER_DEBUG=1` | Print the resolved configuration and exit without running |

## ID passwords

If the Notes ID is password protected, the runtime needs the password. Two options:

- **Password sharing (simplest).** In the Notes client, File → Security → User Security, tick
  *"Don't prompt for a password from other Notes-based programs"*, and leave the client running.
  Nothing else is needed.
- **Explicit switch.** Pass `--id <path-to-id>` together with `--password-stdin`,
  `--prompt-password`, or the `DOMINO_ID_PASSWORD` environment variable. The runner then calls
  `DominoProcess.switchToId()` before starting the target program.

Avoid `--password` outside of throwaway testing: command lines are readable by other processes.

## Writing programs for the runner

Compile against the same dependencies as the runner and provide a normal `main` method. Do not
call `DominoProcess.initializeProcess()` or `NotesThread.sinitThread()` for the process itself —
the runner owns that lifecycle. `src/main/java/com/factory/domino/samples/DominoInfoSample.java`
is a working example that uses both APIs.

## Notes on the implementation

Things that were not obvious and are worth recording:

- **The OpenJ9 requirement still holds, and short tests will tell you otherwise.** The client's
  own bundled JRE is HotSpot and passes every quick check; it fails in real use. See above.

- **`initializeProcess()` argument order.** The HCL documentation shows
  `initializeProcess("=" + ini, execDir)`. The actual JNX implementation validates the *first*
  argument as the program directory and only then accepts a second `=notes.ini` argument. The
  documented order fails with *"program dir path does not exist"*.
- **CORBA.** `lotus.domino.NotesException` extends `org.omg.CORBA.UserException`, which was
  removed from the JDK in Java 11 and is not bundled inside `Notes.jar`. The project therefore
  depends on `org.glassfish.corba:glassfish-corba-omgapi`; without it, the first Notes.jar call
  fails with `NoClassDefFoundError: org/omg/CORBA/UserException`.
- **`ServicesResourceTransformer`.** JNX resolves its implementation through `META-INF/services`
  entries. The shade plugin must merge them, or the shaded jar cannot find the JNA backend.
- **Jakarta Mail is not optional.** JNX declares `jakarta.mail-api`, `jakarta.activation-api` and
  `angus-mail` as `provided`, so they are not inherited transitively — yet reading design
  elements goes through code that references them. Without `angus-mail` on the runner's
  classpath, the first `getDesign()` call dies with
  `NoClassDefFoundError: jakarta.mail.MessagingException`. Same story on the Javalin side:
  `jackson-databind` and an SLF4J provider are optional in Javalin's POM but required at runtime.
- **Parent-last must be selective.** `IsolatedJarClassLoader` loads the application jar
  child-first but always delegates JDK, `com.hcl.domino.*`, `com.sun.jna.*`, `lotus.*` and the
  launcher's own packages to the parent. A second copy of the Domino classes would bind the
  native libraries twice, and objects crossing the two loaders would raise `ClassCastException`
  between same-named classes — both surfacing as native crashes rather than Java errors.
  `domino-web-designer` also declares those dependencies `provided`, so the fat jar cannot contain
  them in the first place. This gives *isolation*, not a sandbox: since `SecurityManager` was
  deprecated (JEP 411) and disabled in recent JDKs, hosted code runs with full launcher
  privileges.
- **Ctrl+C does not work, and that is not fixable from Java.** Initializing the Notes runtime
  installs native signal handlers that swallow SIGINT, SIGTERM *and* SIGQUIT — the process
  ignores all three and only SIGKILL stops it. Verified by contrast: an identical JVM launched
  the same way, without Domino, runs its shutdown hooks normally. Re-installing handlers through
  `sun.misc.Signal` after Domino starts does not reclaim them either. The reliable path is an
  explicit stop: `RunnerLifecycle.requestShutdown()`, which DominoWebDesigner exposes as
  `POST /api/shutdown`. Plan for this in any long-running program you host.
- **Shutdown ordering.** A hosted server cannot use its own JVM shutdown hook: hooks run
  concurrently, so the launcher could release the Domino runtime while a request is still in
  flight. `RunnerLifecycle.onShutdown()` registers cleanup that `--wait` runs *before* the
  teardown.
- **`terminateProcess()` does not stop the Notes client.** The name suggests otherwise, but it
  calls `NotesTerm()`, the counterpart of `NotesInit()`: it releases *this process's* use of the
  runtime. Verified with the client running — same PID before and after.
- **Threading.** Every thread touching Domino needs its own initialized context, and a
  `DominoClient` belongs to its creating thread. `DominoExecutor` owns single-threaded executors
  whose threads initialize once and keep a client; work is submitted to them rather than run on
  Jetty threads. Each worker being its own executor is what guarantees `terminateThread()` runs
  on the thread that initialized the context.
- **`getNamedDocument()` creates rather than fails.** For a missing name JNX returns a new,
  unsaved document (note ID 0) instead of an empty `Optional`. DominoWebDesigner treats note ID 0 as
  a 404, which is the right reading for a read-only tool.

### Known cosmetic issue

On macOS the client prints `objc[...]: Class ... is implemented in both ...libpmc_data.dylib and
...libpmc.dylib` warnings on stderr. These come from the Notes installation itself, not from the
runner, and are harmless. Redirect stderr if they get in the way.

### Targeting a different Domino release

JNX artifacts are published per release line. The `jnx.release` property in `pom.xml` selects it:
`r145` for Notes/Domino 14.5 (the default), `r14` for 14.0, `r12` for 12.x. It must match the
installed client.
