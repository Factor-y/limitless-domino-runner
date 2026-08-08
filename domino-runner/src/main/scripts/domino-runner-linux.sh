#!/bin/bash
#
# Launches DominoRunner on Linux with a JVM configured for the local Notes/Domino install.
#
# Unlike macOS, Linux has no OpenJ9 requirement: any Java 21+ JVM works, as long as its
# architecture matches the Domino binaries (x86_64 for all supported Domino builds).
#
# Usage:
#   domino-runner-linux.sh [runner options] <main-class> [program arguments...]
#
# Environment overrides:
#   DOMINO_RUNNER_JAVA_HOME  JVM to use (Java 21+, architecture matching Domino)
#   DOMINO_PROGRAM_DIR       Domino program directory (default /opt/hcl/domino/notes/latest/linux)
#   NOTES_DATA               Notes data directory (default /local/notesdata)
#   NOTES_INI                notes.ini (default $NOTES_DATA/notes.ini)
#   DOMINO_ID_PASSWORD       Notes ID password, if the ID is password protected
#   DOMINO_RUNNER_JAVA_OPTS  Extra JVM options
#   DOMINO_RUNNER_DEBUG      Set to 1 to print the resolved configuration and exit

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
  echo "domino-runner: $*" >&2
  exit 1
}

# --- Locate the Domino installation ------------------------------------------------

notes_exec_dir="${DOMINO_PROGRAM_DIR:-/opt/hcl/domino/notes/latest/linux}"
[ -d "$notes_exec_dir" ] \
  || die "Domino program directory not found at '$notes_exec_dir'. Set DOMINO_PROGRAM_DIR."
[ -f "$notes_exec_dir/libnotes.so" ] \
  || die "libnotes.so not found in '$notes_exec_dir'; not a usable Domino install."

notes_data="${NOTES_DATA:-/local/notesdata}"
notes_ini="${NOTES_INI:-$notes_data/notes.ini}"
[ -f "$notes_ini" ] || die "notes.ini not found at '$notes_ini'. Set NOTES_INI."

# Notes.jar must match the installed runtime, so it comes from the installation.
notes_jar=""
for candidate in \
    "$notes_exec_dir/ndext/Notes.jar" \
    "$notes_exec_dir/Notes.jar" \
    "$notes_exec_dir/jvm/lib/ext/Notes.jar"; do
  if [ -f "$candidate" ]; then
    notes_jar="$candidate"
    break
  fi
done
[ -n "$notes_jar" ] || die "Notes.jar not found under '$notes_exec_dir'."

# --- Locate a suitable JVM ---------------------------------------------------------

# True when the JVM is at least Java 21, the bytecode level Domino JNX is built for.
jvm_is_suitable() {
  local home="$1"
  [ -x "$home/bin/java" ] || return 1
  local version
  version="$("$home/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
  [ -n "$version" ] && [ "$version" -ge 21 ]
}

java_home=""

if [ -n "${DOMINO_RUNNER_JAVA_HOME:-}" ]; then
  java_home="$DOMINO_RUNNER_JAVA_HOME"
  jvm_is_suitable "$java_home" \
    || die "DOMINO_RUNNER_JAVA_HOME='$java_home' is not a usable Java 21+ JVM."
elif [ -n "${JAVA_HOME:-}" ] && jvm_is_suitable "$JAVA_HOME"; then
  java_home="$JAVA_HOME"
else
  for candidate in "$script_dir/../jvm" /usr/lib/jvm/*; do
    if [ -d "$candidate" ] && jvm_is_suitable "$candidate"; then
      java_home="$candidate"
      break
    fi
  done
fi

[ -n "$java_home" ] || die "no Java 21+ JVM found. Set DOMINO_RUNNER_JAVA_HOME."

java_bin="$java_home/bin/java"

# --- Build the classpath -----------------------------------------------------------

if [ -d "$script_dir/../lib" ]; then
  runner_cp="$script_dir/../lib/*"
elif [ -d "$script_dir/../../target/dist/lib" ]; then
  runner_cp="$script_dir/../../target/dist/lib/*"
else
  die "runner libraries not found. Build the project first with 'mvn package'."
fi

classpath="$runner_cp:$notes_jar"
if [ -n "${DOMINO_RUNNER_EXTRA_CP:-}" ]; then
  classpath="$classpath:$DOMINO_RUNNER_EXTRA_CP"
fi

# --- Native environment ------------------------------------------------------------

export Notes_ExecDirectory="$notes_exec_dir"
export Directory="$notes_data"
export NotesINI="$notes_ini"
export LD_LIBRARY_PATH="$notes_exec_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$notes_exec_dir:$notes_exec_dir/res/C:$PATH"

# --- JVM options -------------------------------------------------------------------

java_opts=(
  "-Djava.library.path=$notes_exec_dir"
  "-Djna.library.path=$notes_exec_dir"
  "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
  "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
  "--add-opens=java.base/java.io=ALL-UNNAMED"
  "--add-opens=java.base/java.nio=ALL-UNNAMED"
)

if [ -n "${DOMINO_RUNNER_JAVA_OPTS:-}" ]; then
  # shellcheck disable=SC2206
  java_opts+=(${DOMINO_RUNNER_JAVA_OPTS})
fi

if [ "${DOMINO_RUNNER_DEBUG:-0}" = "1" ]; then
  echo "Notes_ExecDirectory: $notes_exec_dir"
  echo "Notes data       : $notes_data"
  echo "notes.ini        : $notes_ini"
  echo "Notes.jar        : $notes_jar"
  echo "JAVA_HOME        : $java_home"
  echo "JVM              : $("$java_bin" -version 2>&1 | head -1)"
  echo "Classpath        : $classpath"
  echo "JVM options      : ${java_opts[*]}"
  exit 0
fi

exec "$java_bin" "${java_opts[@]}" -cp "$classpath" \
  com.factory.domino.runner.DominoRunner "$@"
