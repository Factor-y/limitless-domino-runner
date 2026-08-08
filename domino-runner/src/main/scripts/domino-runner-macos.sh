#!/bin/bash
#
# Launches DominoRunner on macOS with a JVM configured for the local HCL Notes client.
#
# The three things that must line up, and that this script exists to guarantee:
#
#   1. Architecture. The macOS Notes client ships x86_64 binaries. An arm64 JVM cannot
#      load them through JNI; the process dies in native code with no Java stack trace.
#   2. VM flavour. The Notes runtime on macOS requires an OpenJ9 JVM (IBM Semeru).
#      HotSpot JVMs (Temurin, Oracle, Zulu, Corretto) crash on load.
#   3. Library paths. Notes_ExecDirectory plus java.library.path/jna.library.path must
#      point at the client's MacOS directory or the native libraries are never found.
#
# Usage:
#   domino-runner-macos.sh [runner options] <main-class> [program arguments...]
#
# Environment overrides:
#   DOMINO_RUNNER_JAVA_HOME  JVM to use (must be OpenJ9, x86_64, Java 21+)
#   NOTES_APP                Path to HCL Notes.app
#   NOTES_DATA               Notes data directory
#   NOTES_INI                notes.ini ("Notes Preferences" on macOS)
#   DOMINO_ID_PASSWORD       Notes ID password, if the ID is password protected
#   DOMINO_RUNNER_JAVA_OPTS  Extra JVM options
#   DOMINO_RUNNER_DEBUG      Set to 1 to print the resolved configuration and exit

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
  echo "domino-runner: $*" >&2
  exit 1
}

# --- Locate the Notes client -------------------------------------------------------

notes_app="${NOTES_APP:-/Applications/HCL Notes.app}"
[ -d "$notes_app" ] || die "HCL Notes not found at '$notes_app'. Set NOTES_APP to its location."

notes_exec_dir="$notes_app/Contents/MacOS"
[ -f "$notes_exec_dir/libnotes.dylib" ] \
  || die "libnotes.dylib not found in '$notes_exec_dir'; '$notes_app' is not a usable Notes install."

notes_data="${NOTES_DATA:-$HOME/Library/Application Support/HCL Notes Data}"
notes_ini="${NOTES_INI:-$HOME/Library/Preferences/Notes Preferences}"

# Notes.jar must come from the client so it matches the native runtime; it is never bundled.
notes_jar="$notes_app/Contents/Resources/ndext/Notes.jar"
[ -f "$notes_jar" ] || die "Notes.jar not found at '$notes_jar'."

# --- Locate a suitable JVM ---------------------------------------------------------

# Reports the architecture of a JVM's java binary, or nothing if it cannot be read.
jvm_arch() {
  file -b "$1/bin/java" 2>/dev/null | grep -oE 'x86_64|arm64' | head -1
}

# Reports a JVM's major version, or nothing.
jvm_major() {
  "$1/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"'
}

# A JVM is usable when it is OpenJ9-based, matches the client's architecture (x86_64 on
# macOS) and is at least Java 21, the bytecode level Domino JNX is built for.
#
# The OpenJ9 requirement is real, and the JRE bundled with the Notes client does NOT satisfy
# it: that one is HotSpot (Temurin 21). It was tried, and it crashes under real use even
# though short runs appear to work — so it is rejected here rather than picked up as a
# convenient default.
jvm_is_suitable() {
  local home="$1" major
  [ -x "$home/bin/java" ] || return 1
  [ "$(jvm_arch "$home")" = "x86_64" ] || return 1
  major="$(jvm_major "$home")"
  [ -n "$major" ] && [ "$major" -ge 21 ] || return 1
  "$home/bin/java" -version 2>&1 | grep -qi 'openj9'
}

java_home=""

if [ -n "${DOMINO_RUNNER_JAVA_HOME:-}" ]; then
  java_home="$DOMINO_RUNNER_JAVA_HOME"
  jvm_is_suitable "$java_home" \
    || die "DOMINO_RUNNER_JAVA_HOME='$java_home' is not usable: macOS needs an x86_64 OpenJ9 \
JVM (IBM Semeru) of Java 21 or later. The JRE bundled with the Notes client is HotSpot and \
crashes under real use."
else
  # Search, nearest first: a JVM shipped with this distribution, then a .jvm directory
  # anywhere up the tree, then anything registered with the system.
  candidates=("$script_dir/../jvm/Contents/Home")

  probe="$script_dir"
  while [ "$probe" != "/" ]; do
    for jvm_dir in "$probe/.jvm"/jdk-*/Contents/Home; do
      [ -d "$jvm_dir" ] && candidates+=("$jvm_dir")
    done
    probe="$(dirname "$probe")"
  done

  for jvm_dir in "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
                 /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -d "$jvm_dir" ] && candidates+=("$jvm_dir")
  done

  for candidate in "${candidates[@]}"; do
    if [ -d "$candidate" ] && jvm_is_suitable "$candidate"; then
      java_home="$candidate"
      break
    fi
  done
fi

if [ -z "$java_home" ]; then
  die "no suitable JVM found.

The macOS HCL Notes client is x86_64 and requires an OpenJ9 JVM, so the runner needs
IBM Semeru for macOS x64, Java 21 or later (the bytecode level Domino JNX is built for).
On Apple Silicon it runs under Rosetta 2.

The JRE bundled with the Notes client cannot be used: it is HotSpot, and it crashes under
real use even though short runs appear to work.

Install one, for example:

  mkdir -p .jvm && cd .jvm
  curl -LO https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.12.0/ibm-semeru-open-jdk_x64_mac_21.0.12.0.tar.gz
  tar xzf ibm-semeru-open-jdk_x64_mac_21.0.12.0.tar.gz

then point DOMINO_RUNNER_JAVA_HOME at its Contents/Home directory."
fi

java_bin="$java_home/bin/java"

# --- Build the classpath -----------------------------------------------------------

# Supports both the packaged distribution (bin/ next to lib/) and a plain Maven build.
if [ -d "$script_dir/../lib" ]; then
  runner_cp="$script_dir/../lib/*"
elif [ -d "$script_dir/../../domino-runner/target/dist/lib" ]; then
  runner_cp="$script_dir/../../domino-runner/target/dist/lib/*"
else
  die "runner libraries not found. Build the project first with 'mvn package'."
fi

# The client's Notes.jar goes last so it never shadows the runner's own classes.
classpath="$runner_cp:$notes_jar"
if [ -n "${DOMINO_RUNNER_EXTRA_CP:-}" ]; then
  classpath="$classpath:$DOMINO_RUNNER_EXTRA_CP"
fi

# --- Native environment ------------------------------------------------------------

export Notes_ExecDirectory="$notes_exec_dir"
export Directory="$notes_data"
export NotesINI="$notes_ini"

# Set inside the script on purpose: macOS System Integrity Protection strips DYLD_*
# variables when launching protected binaries such as the shell itself, so exporting
# them from a parent process would not survive. The JVM we launch is unprotected.
export DYLD_LIBRARY_PATH="$notes_exec_dir${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
export LD_LIBRARY_PATH="$notes_exec_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$notes_exec_dir:$PATH"

# --- JVM options -------------------------------------------------------------------

java_opts=(
  "-Djava.library.path=$notes_exec_dir"
  "-Djna.library.path=$notes_exec_dir"
  # JNX reaches into JDK internals that are encapsulated from Java 9 onward.
  "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
  "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
  "--add-opens=java.base/java.io=ALL-UNNAMED"
  "--add-opens=java.base/java.nio=ALL-UNNAMED"
)

if [ -n "${DOMINO_RUNNER_JAVA_OPTS:-}" ]; then
  # Deliberate word splitting: the variable carries multiple options.
  # shellcheck disable=SC2206
  java_opts+=(${DOMINO_RUNNER_JAVA_OPTS})
fi

if [ "${DOMINO_RUNNER_DEBUG:-0}" = "1" ]; then
  echo "Notes app        : $notes_app"
  echo "Notes_ExecDirectory: $notes_exec_dir"
  echo "Notes data       : $notes_data"
  echo "notes.ini        : $notes_ini"
  echo "Notes.jar        : $notes_jar"
  echo "JAVA_HOME        : $java_home"
  echo "JVM              : $("$java_bin" -version 2>&1 | head -1)"
  echo "JVM arch         : $(jvm_arch "$java_home")"
  echo "Classpath        : $classpath"
  echo "JVM options      : ${java_opts[*]}"
  exit 0
fi

exec "$java_bin" "${java_opts[@]}" -cp "$classpath" \
  com.factory.domino.runner.DominoRunner "$@"
