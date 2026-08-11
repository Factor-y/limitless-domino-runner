#!/bin/sh
#
# Limitless Domino Runner — installer for macOS and Linux.
#
#   curl -fsSL https://github.com/Factor-y/limitless-domino-runner/releases/latest/download/install.sh | sh
#
# Or, if you would rather read it first — which is the sensible way to treat any script you
# pipe into a shell:
#
#   curl -fsSLO https://github.com/Factor-y/limitless-domino-runner/releases/latest/download/install.sh
#   less install.sh && sh install.sh
#
# Options (also usable as environment variables):
#   --version <v>   LIMITLESS_VERSION   version to install (default: latest release)
#   --dir <path>    LIMITLESS_DIR       install directory (default: ~/.limitless-domino)
#   --with-jvm      LIMITLESS_WITH_JVM  download the JVM as part of the install
#   --add-to-path                       append the bin directory to your shell profile
#   --uninstall                         remove the installation and exit
#
# POSIX sh on purpose: this has to run before anything is installed, on whatever shell
# the machine happens to have.

set -eu

REPO="Factor-y/limitless-domino-runner"
INSTALL_DIR="${LIMITLESS_DIR:-$HOME/.limitless-domino}"
VERSION="${LIMITLESS_VERSION:-latest}"
WITH_JVM="${LIMITLESS_WITH_JVM:-0}"
ADD_TO_PATH=0
UNINSTALL=0

# --- output ------------------------------------------------------------------------

if [ -t 1 ]; then
  BOLD=$(printf '\033[1m'); DIM=$(printf '\033[2m'); RED=$(printf '\033[31m')
  YELLOW=$(printf '\033[33m'); RESET=$(printf '\033[0m')
else
  BOLD=''; DIM=''; RED=''; YELLOW=''; RESET=''
fi

say()  { printf '%s\n' "$*"; }
step() { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$*"; }
warn() { printf '%s warning:%s %s\n' "$YELLOW" "$RESET" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

# --- arguments ---------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:?--version needs a value}"; shift 2 ;;
    --dir)     INSTALL_DIR="${2:?--dir needs a value}"; shift 2 ;;
    --with-jvm) WITH_JVM=1; shift ;;
    --no-jvm)   WITH_JVM=0; shift ;;
    --add-to-path) ADD_TO_PATH=1; shift ;;
    --uninstall)   UNINSTALL=1; shift ;;
    -h|--help) sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option '$1'. Use --help." ;;
  esac
done

# --- uninstall ---------------------------------------------------------------------

if [ "$UNINSTALL" = "1" ]; then
  [ -d "$INSTALL_DIR" ] || die "nothing installed at $INSTALL_DIR"
  step "Removing $INSTALL_DIR"
  rm -rf "$INSTALL_DIR"
  say "Removed. Any PATH line added to your shell profile is left in place; remove it by hand."
  exit 0
fi

# --- platform ----------------------------------------------------------------------

step "Detecting platform"
os="$(uname -s)"
arch="$(uname -m)"

case "$os" in
  Darwin) platform="macOS" ;;
  Linux)  platform="Linux" ;;
  *) die "unsupported platform '$os'. On Windows use install.ps1 instead." ;;
esac
say "    $platform ($arch)"

# The runner binds to a locally installed Notes/Domino client through JNI. Without one there
# is nothing to bind to, so this is checked before anything is downloaded.
if [ "$os" = "Darwin" ]; then
  if [ -d "/Applications/HCL Notes.app" ]; then
    say "    HCL Notes client found"
  else
    warn "no HCL Notes client at /Applications/HCL Notes.app."
    warn "The runner needs one; install it, or set NOTES_APP before running the tool."
  fi
  if [ "$arch" = "arm64" ]; then
    # Not a problem, but worth stating: the client is x86_64, so the JVM runs under Rosetta.
    say "    ${DIM}Apple Silicon: the client is x86_64, so the JVM runs under Rosetta 2${RESET}"
  fi
else
  if [ -d "/opt/hcl/domino" ] || [ -n "${DOMINO_PROGRAM_DIR:-}" ]; then
    say "    Domino installation found"
  else
    warn "no Domino installation found under /opt/hcl/domino."
    warn "Set DOMINO_PROGRAM_DIR to its location before running the tool."
  fi
  warn "Linux support is provided but has not been verified end to end."
fi

for tool in curl unzip; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but not installed."
done

# --- resolve the version -----------------------------------------------------------

step "Resolving version"
if [ "$VERSION" = "latest" ]; then
  base_url="https://github.com/$REPO/releases/latest/download"
else
  base_url="https://github.com/$REPO/releases/download/v$VERSION"
fi
say "    $VERSION"

# --- download ----------------------------------------------------------------------

tmp="$(mktemp -d)"
# shellcheck disable=SC2064
trap "rm -rf '$tmp'" EXIT INT TERM

step "Downloading"
archive="$tmp/limitless-domino.zip"
curl -fL --progress-bar -o "$archive" "$base_url/limitless-domino.zip" \
  || die "download failed from $base_url/limitless-domino.zip"

# The checksum is the whole reason this is safe to pipe into a shell: it proves the archive
# is the one the release published. A missing checksum is reported, never silently skipped.
step "Verifying checksum"
if curl -fsSL -o "$tmp/checksum" "$base_url/limitless-domino.zip.sha256" 2>/dev/null; then
  expected="$(cut -d' ' -f1 < "$tmp/checksum")"
  if command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$archive" | cut -d' ' -f1)"
  elif command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$archive" | cut -d' ' -f1)"
  else
    actual=''
    warn "no shasum or sha256sum available; cannot verify the download."
  fi
  if [ -n "$actual" ]; then
    [ "$expected" = "$actual" ] || die "checksum mismatch.
  expected $expected
  got      $actual
Refusing to install. Try again, and report it if it persists."
    say "    ok ($actual)"
  fi
else
  warn "no published checksum found; the download could not be verified."
fi

# --- install -----------------------------------------------------------------------

step "Installing into $INSTALL_DIR"
unzip -q "$archive" -d "$tmp/unpacked"

# The archive contains a single versioned directory; its contents become the install root
# so that the path stays stable across upgrades.
inner="$(find "$tmp/unpacked" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$inner" ] || die "unexpected archive layout."

if [ -d "$INSTALL_DIR" ]; then
  say "    replacing the existing installation (a downloaded JVM is kept)"
  # Everything except .jvm, so an upgrade does not throw away a 600 MB download.
  find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 ! -name '.jvm' -exec rm -rf {} +
fi
mkdir -p "$INSTALL_DIR"
cp -R "$inner"/. "$INSTALL_DIR"/
chmod +x "$INSTALL_DIR/bin/limitless-domino" "$INSTALL_DIR"/bin/*.sh 2>/dev/null || true
say "    version $(cat "$INSTALL_DIR/VERSION" 2>/dev/null || echo '?') installed"

# --- JVM ---------------------------------------------------------------------------

if [ "$WITH_JVM" = "1" ]; then
  step "Downloading the JVM"
  "$INSTALL_DIR/bin/limitless-domino" jvm
else
  say ""
  say "No JVM was downloaded. macOS needs an x86_64 OpenJ9 JVM (IBM Semeru):"
  say "  ${BOLD}$INSTALL_DIR/bin/limitless-domino jvm${RESET}"
  say "or point DOMINO_RUNNER_JAVA_HOME at one you already have."
fi

# --- PATH --------------------------------------------------------------------------

bin_dir="$INSTALL_DIR/bin"
case ":$PATH:" in
  *":$bin_dir:"*) on_path=1 ;;
  *) on_path=0 ;;
esac

if [ "$on_path" = "0" ]; then
  if [ "$ADD_TO_PATH" = "1" ]; then
    # Only ever with an explicit flag: editing someone's shell profile uninvited is not
    # the installer's business.
    case "${SHELL:-}" in
      */zsh)  profile="$HOME/.zshrc" ;;
      */bash) profile="$HOME/.bashrc" ;;
      *)      profile="$HOME/.profile" ;;
    esac
    step "Adding $bin_dir to $profile"
    printf '\n# Limitless Domino Runner\nexport PATH="%s:$PATH"\n' "$bin_dir" >> "$profile"
    say "    open a new shell, or run: . $profile"
  else
    say ""
    say "Add it to your PATH:"
    say "  ${BOLD}export PATH=\"$bin_dir:\$PATH\"${RESET}"
    say "${DIM}  (or re-run this installer with --add-to-path)${RESET}"
  fi
fi

# --- validate ---------------------------------------------------------------------

say ""
step "Validating the installation"
# Run last, and on purpose: an installer that finishes without saying whether the thing
# works has only moved the discovery of a problem to later.
if [ -x "$bin_dir/limitless-domino" ]; then
  set +e
  "$bin_dir/limitless-domino" validate
  status=$?
  set -e
  case "$status" in
    0)  say "" ; say "${BOLD}Ready.${RESET} Try: limitless-domino designer" ;;
    10) warn "the environment is not ready — see the failed check above (JVM or client)." ;;
    11) warn "a required library is missing; the installation may be incomplete." ;;
    12) warn "the Domino runtime could not be used; see above." ;;
    13) warn "credentials are needed: start the Notes client with password sharing enabled." ;;
    *)  warn "validation exited with status $status." ;;
  esac
else
  die "the launcher is missing from $bin_dir; the installation did not complete."
fi
