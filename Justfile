# Limitless Domino Runner — project tasks
#
# Run `just` to see the available recipes.

# --- JVM configuration -------------------------------------------------------------
#
# macOS needs an x86_64 OpenJ9 JVM (IBM Semeru): the Notes client ships x86_64 binaries,
# and its runtime requires OpenJ9. The HotSpot JRE bundled with the client looks like it
# works — short runs succeed — but crashes under real use, so it is not an option.

semeru_version := "21.0.12.0"
semeru_dir_name := "jdk-21.0.12+8"
semeru_archive := "ibm-semeru-open-jdk_x64_mac_" + semeru_version + ".tar.gz"
semeru_url := "https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-" + semeru_version + "/" + semeru_archive

jvm_root := justfile_directory() / ".jvm"
java_home := jvm_root / semeru_dir_name / "Contents/Home"

runner_script := justfile_directory() / "domino-runner/target/dist/bin/domino-runner-macos.sh"
designer_jar := justfile_directory() / "samples/domino-web-designer/target/domino-web-designer.jar"

# List the available recipes
default:
    @just --list

# Download and unpack the IBM Semeru JVM used to build and run on macOS
setup-jvm:
    #!/usr/bin/env bash
    set -euo pipefail

    if [ "$(uname)" != "Darwin" ]; then
        echo "setup-jvm targets macOS; on Linux use any JDK 21+ matching the Domino architecture." >&2
        exit 1
    fi

    if [ -x "{{ java_home }}/bin/java" ]; then
        echo "JVM already present at {{ java_home }}"
        "{{ java_home }}/bin/java" -version
        exit 0
    fi

    mkdir -p "{{ jvm_root }}"
    cd "{{ jvm_root }}"

    echo "Downloading IBM Semeru {{ semeru_version }} for macOS x64 (about 230 MB)..."
    curl -fL --progress-bar -o "{{ semeru_archive }}" "{{ semeru_url }}"

    echo "Unpacking..."
    tar xzf "{{ semeru_archive }}"
    rm -f "{{ semeru_archive }}"

    if [ ! -x "{{ java_home }}/bin/java" ]; then
        echo "Unpacked, but {{ java_home }} does not look like a JVM." >&2
        echo "The archive layout may have changed; check semeru_dir_name in the Justfile." >&2
        exit 1
    fi

    # Verify the three things that decide whether Domino can be loaded at all, rather
    # than discovering a mismatch later as a native crash.
    arch="$(file -b "{{ java_home }}/bin/java" | grep -oE 'x86_64|arm64' | head -1)"
    [ "$arch" = "x86_64" ] || { echo "Expected an x86_64 JVM, got '$arch'." >&2; exit 1; }
    "{{ java_home }}/bin/java" -version 2>&1 | grep -qi openj9 \
        || { echo "Expected an OpenJ9 JVM; this one is not." >&2; exit 1; }

    echo
    "{{ java_home }}/bin/java" -version
    echo
    echo "Ready. JAVA_HOME = {{ java_home }}"
    echo "The launch scripts discover it automatically; 'just build' and 'just run' use it too."

# Remove the downloaded JVM (about 600 MB)
clean-jvm:
    rm -rf "{{ jvm_root }}"
    @echo "Removed {{ jvm_root }}"

# --- Build -------------------------------------------------------------------------

# Build all modules
build: _require-jvm
    JAVA_HOME="{{ java_home }}" mvn -B package

# Build without running the checks, for a fast iteration
build-quick: _require-jvm
    JAVA_HOME="{{ java_home }}" mvn -B -q package -DskipTests

# Remove build output
clean:
    JAVA_HOME="{{ java_home }}" mvn -B -q clean

# --- Run ---------------------------------------------------------------------------

# Run the built-in sample: session details via Notes.jar, local databases via JNX
run-sample limit="10":
    "{{ runner_script }}" com.factory.domino.samples.DominoInfoSample {{ limit }}

# Start the DominoWebDesigner web application (Ctrl+C cannot stop it — use `just stop`)
run-designer *args:
    "{{ runner_script }}" --jar "{{ designer_jar }}" --wait {{ args }}

# Run any main class or application jar under the runner
run *args:
    "{{ runner_script }}" {{ args }}

# Print the environment the launcher resolved, without running anything
env:
    DOMINO_RUNNER_DEBUG=1 "{{ runner_script }}"

# Stop a running DominoWebDesigner through its shutdown endpoint
stop port="8080":
    #!/usr/bin/env bash
    set -euo pipefail
    # An explicit endpoint is the only reliable way: the Notes runtime installs native
    # signal handlers that swallow SIGINT and SIGTERM.
    if curl -fsS -X POST --max-time 15 "http://127.0.0.1:{{ port }}/api/shutdown" >/dev/null 2>&1; then
        echo "Shutdown requested; the Domino teardown takes a few seconds."
    else
        echo "No server responding on port {{ port }}." >&2
        exit 1
    fi

# --- Internal ----------------------------------------------------------------------

_require-jvm:
    #!/usr/bin/env bash
    if [ ! -x "{{ java_home }}/bin/java" ]; then
        echo "JVM not found at {{ java_home }}. Run 'just setup-jvm' first." >&2
        exit 1
    fi
