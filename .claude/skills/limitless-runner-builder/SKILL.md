---
name: limitless-runner-builder
description: Build a Java program that runs under the Limitless Domino Runner — a Maven module packaged as a fat jar, loaded with --jar, using Domino JNX and/or the classic lotus.domino API. Use when creating a new runner application or sample, adding APIs to one, or diagnosing why a hosted program crashes, hangs, or fails to load.
---

# Building a program for the Limitless Domino Runner

The runner initializes the HCL Notes/Domino runtime and then hands control to your `main`.
Your program runs in the same JVM on a thread that already holds a live Domino context, so it
can use both Domino JNX and the classic `lotus.domino` API without initializing anything.

That convenience comes with rules. Most of them exist because Domino is a **native runtime
loaded through JNI**: break one and the process dies in native code with no Java stack trace,
or — worse — appears to work and fails later. Read "The five rules" before writing code.

## The five rules

### 1. Never bundle the Domino classes

Declare JNX and the runner as `provided`. The runner loads your jar **child-first**, so a copy
of `com.hcl.domino.*` or `com.sun.jna.*` inside it would be loaded a second time: JNA would
re-bind native libraries already bound in this process, and objects crossing the two loaders
would raise `ClassCastException` between same-named classes. Both surface as native crashes.

The class loader already delegates these packages to the parent unconditionally, so a bundled
copy would be dead weight at best — but `provided` keeps it out of the jar entirely, which is
the honest way to express the dependency.

### 2. Never touch Domino from an arbitrary thread

Every thread that calls Domino must have its own initialized context, and a `DominoClient`
belongs to the thread that created it. A server that handed requests to a pool thread would
work for the first request and then fail in ways that look random.

Use `DominoExecutor`: it owns single-threaded executors whose threads initialize once and keep
a client. Submit work to it and block for the result.

```java
DominoExecutor executor = new DominoExecutor(4);
String user = executor.call(client -> client.getEffectiveUserName());
```

### 3. Do not initialize or terminate the runtime

The runner owns that lifecycle. Do not call `DominoProcess.initializeProcess()`,
`terminateProcess()`, or `NotesThread.sinitThread()` for the process. If you use the classic
API on your own thread, pair `sinitThread()`/`stermThread()` within that thread only — the
Notes C API reference-counts thread initialization, so this nests safely.

### 4. Register cleanup with `RunnerLifecycle`, not a JVM shutdown hook

Shutdown hooks run concurrently, so the runner could release the Domino runtime while your code
is still using a handle. `RunnerLifecycle.onShutdown()` runs your cleanup **before** the
teardown, deterministically.

### 5. Ctrl+C will not stop a long-running program

Initializing the Notes runtime installs native signal handlers that swallow SIGINT, SIGTERM and
SIGQUIT. The process ignores all three; only SIGKILL stops it, and that skips every bit of
cleanup — and has been observed to take the running Notes client down with it. Re-installing
handlers via `sun.misc.Signal` does not reclaim them.

So a server must expose its own way to be stopped, calling `RunnerLifecycle.requestShutdown()`.

## Module setup

Add the module to the parent `pom.xml` `<modules>`, then:

```xml
<parent>
  <groupId>com.factory.domino</groupId>
  <artifactId>limitless-domino-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <relativePath>../../pom.xml</relativePath>
</parent>
<artifactId>my-app</artifactId>

<dependencies>
  <!-- Bundled: your own dependencies -->
  <dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
  </dependency>

  <!-- PROVIDED: shared with the parent loader, never bundled (rule 1) -->
  <dependency>
    <groupId>com.hcl.domino</groupId>
    <artifactId>domino-jnx-jna-${jnx.release}</artifactId>
    <scope>provided</scope>
  </dependency>
  <dependency>
    <groupId>com.factory.domino</groupId>
    <artifactId>domino-runner</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

Shade into a fat jar. **`ServicesResourceTransformer` is mandatory**: JNX and Jetty both resolve
parts of themselves through `META-INF/services`, and without merging, those entries overwrite
each other.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.example.MyApp</mainClass>
          </transformer>
        </transformers>
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
              <exclude>module-info.class</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Verify the jar afterwards — this catches rule 1 violations before they become crashes:

```bash
unzip -l target/my-app.jar | grep -c 'com/hcl/domino'   # must be 0
unzip -l target/my-app.jar | grep -c 'com/sun/jna'      # must be 0
```

## Program shapes

### One-shot program

```java
public final class MyReport {
  public static void main(String[] args) {
    // The runner already initialized the process and this thread.
    try (DominoClient client = DominoClientBuilder.newDominoClient().asIDUser().build()) {
      client.openDbDirectory().query().withServer("").stream()
          .forEach(entry -> System.out.println(entry.getFilePath()));
    }
  }
}
```

Run it: `just run --jar target/my-app.jar` (or `--cp` plus a class name for a plain classpath).

### Long-running server

```java
public final class MyServer {
  public static void main(String[] args) {
    DominoExecutor executor = new DominoExecutor(4);          // rule 2
    Javalin app = Javalin.create(/* ... */);

    app.get("/api/user", ctx ->
        ctx.json(executor.call(DominoClient::getEffectiveUserName)));

    // rule 5: an explicit stop, because signals do not work
    app.post("/api/shutdown", ctx -> {
      boolean accepted = RunnerLifecycle.requestShutdown();
      ctx.status(accepted ? 202 : 409);
    });

    app.start("127.0.0.1", 8080);

    // rule 4: ordered teardown before the runtime is released
    RunnerLifecycle.onShutdown(() -> {
      app.stop();
      executor.close();
    });
  }
}
```

Run it with `--wait`, which keeps the process alive after `main` returns:
`just run-designer` or `just run --jar target/my-app.jar --wait`.

Bind to loopback unless you have a reason not to: whatever you expose is readable with the
runner's Notes identity, and there is no authentication in front of it.

## Optional dependencies that are not optional

Several libraries declare dependencies as `provided` or `optional`, so Maven will not inherit
them, yet they are required at runtime. The failures are all `NoClassDefFoundError` at the first
call that needs them:

| Missing | Symptom | Where it belongs |
|---|---|---|
| `org.eclipse.angus:angus-mail` | `jakarta.mail.MessagingException` on the first `getDesign()` | already in `domino-runner` |
| `org.glassfish.corba:glassfish-corba-omgapi` | `org/omg/CORBA/UserException` on the first Notes.jar call | already in `domino-runner` |
| `com.fasterxml.jackson.core:jackson-databind` | Javalin returns 500 with "no object mapper configured" | your module |
| an SLF4J provider (e.g. `slf4j-simple`) | "No SLF4J providers were found"; Jetty logs nothing | your module |

## Diagnosing failures

| Symptom | Cause |
|---|---|
| Process dies with no Java stack trace, NSD runs | Architecture or VM mismatch. macOS needs **x86_64 OpenJ9** (IBM Semeru). The JRE bundled with the Notes client is HotSpot: short runs succeed, real use crashes. Run `just env` to see what was resolved. |
| `ClassCastException` between identically named classes | Domino classes bundled in the fat jar (rule 1) |
| First request works, later ones fail oddly | Domino called from a thread without a context (rule 2) |
| `UnsatisfiedLinkError`, library not found | `java.library.path`/`jna.library.path` not pointing at the client's `MacOS` directory — the launch script sets these |
| Server exits immediately after starting | `--wait` missing |
| Ctrl+C does nothing | Expected (rule 5). Use the program's stop endpoint. |
| Class in the jar not found | `--jar` missing, or no `Main-Class` in the manifest |
| `ServiceConfigurationError`, JNX backend not found | `ServicesResourceTransformer` missing from the shade config |

## Commands

```bash
just setup-jvm                          # download the Semeru JVM (once)
just build                              # build every module
just run --jar path/to/app.jar --wait   # run under the runner
just env                                # show the resolved environment
just stop                               # stop a running server
```

Runner options worth knowing: `--cp` (extra classpath), `--share-package` (delegate another
package prefix to the parent loader), `--id` with `--password-stdin` or `--prompt-password`
(password-protected ID), `--verbose`.

If the ID is password protected, the simplest route is to enable *"Don't prompt for a password
from other Notes-based programs"* in the Notes client under File → Security → User Security, and
leave the client running. Without a running, unlocked client the runtime blocks waiting for a
password that has nowhere to be typed.

## Reference

`README.md` in the project root records the environment obstacles and why each decision was
made. `samples/domino-web-designer` is a complete worked example: fat jar, parent-last loading,
`DominoExecutor`, ordered shutdown, REST APIs, OpenAPI/Swagger UI, mDNS, and DXL export/import
with a write gate. It also shows how to reach the classic `lotus.domino` API from a hosted jar
(`NoteSigner`), which works because that package is one of the shared prefixes.
