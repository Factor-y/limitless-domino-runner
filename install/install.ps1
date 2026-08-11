<#
.SYNOPSIS
    Limitless Domino Runner — installer for Windows.

.DESCRIPTION
    Downloads the distribution, verifies its checksum, and unpacks it into
    %USERPROFILE%\.limitless-domino.

    Run it with:

        irm https://github.com/Factor-y/limitless-domino-runner/releases/latest/download/install.ps1 | iex

    Or, if you would rather read it first — the sensible way to treat any script you pipe
    into a shell:

        irm https://github.com/Factor-y/limitless-domino-runner/releases/latest/download/install.ps1 -OutFile install.ps1
        notepad install.ps1 ; .\install.ps1

    Note that Windows support has not been verified end to end: the launch script is
    provided and follows the same structure as the macOS one, but has not been run against
    a real client. Bitness is the thing to watch — Notes 12 and earlier ship a 32-bit
    client and need a 32-bit JVM, while Notes 14 and Domino server installs are 64-bit.

.PARAMETER Version
    Version to install. Defaults to the latest release.

.PARAMETER Dir
    Install directory. Defaults to $env:USERPROFILE\.limitless-domino

.PARAMETER AddToPath
    Append the bin directory to the user PATH environment variable.

.PARAMETER Uninstall
    Remove the installation and exit.
#>

[CmdletBinding()]
param(
    [string]$Version = 'latest',
    [string]$Dir = (Join-Path $env:USERPROFILE '.limitless-domino'),
    [switch]$AddToPath,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$repo = 'Factor-y/limitless-domino-runner'

function Write-Step { param([string]$Message) Write-Host "==> " -NoNewline -ForegroundColor Cyan; Write-Host $Message }
function Write-Detail { param([string]$Message) Write-Host "    $Message" -ForegroundColor DarkGray }
function Write-Warn { param([string]$Message) Write-Warning $Message }

# --- uninstall ---------------------------------------------------------------------

if ($Uninstall) {
    if (-not (Test-Path $Dir)) { throw "Nothing installed at $Dir" }
    Write-Step "Removing $Dir"
    Remove-Item -Recurse -Force $Dir
    Write-Host "Removed. Any PATH entry added earlier is left in place; remove it by hand."
    return
}

# --- platform ----------------------------------------------------------------------

Write-Step 'Detecting platform'
$arch = $env:PROCESSOR_ARCHITECTURE
Write-Detail "Windows ($arch)"

# The runner binds to a locally installed Notes/Domino client through JNI, so this is
# checked before anything is downloaded.
$clientPaths = @(
    'C:\Program Files\HCL\Notes\nnotes.dll',
    'C:\Program Files\HCL\Domino\nnotes.dll',
    'C:\Program Files (x86)\HCL\Notes\nnotes.dll'
)
$client = $clientPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($client) {
    Write-Detail "Notes/Domino found: $(Split-Path $client -Parent)"
} else {
    Write-Warn 'No Notes/Domino installation found in the usual locations. Install one, or set DOMINO_PROGRAM_DIR before running the tool.'
}

# --- resolve the version -----------------------------------------------------------

Write-Step 'Resolving version'
$baseUrl = if ($Version -eq 'latest') {
    "https://github.com/$repo/releases/latest/download"
} else {
    "https://github.com/$repo/releases/download/v$Version"
}
Write-Detail $Version

# --- download ----------------------------------------------------------------------

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("limitless-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    Write-Step 'Downloading'
    $archive = Join-Path $tmp 'limitless-domino.zip'
    Invoke-WebRequest -Uri "$baseUrl/limitless-domino.zip" -OutFile $archive -UseBasicParsing

    # The checksum is what makes this safe to pipe into a shell: it proves the archive is
    # the one the release published. A missing checksum is reported, never skipped quietly.
    Write-Step 'Verifying checksum'
    $checksumFile = Join-Path $tmp 'checksum.txt'
    try {
        Invoke-WebRequest -Uri "$baseUrl/limitless-domino.zip.sha256" -OutFile $checksumFile -UseBasicParsing
        $expected = ((Get-Content $checksumFile -Raw) -split '\s+')[0].Trim().ToLower()
        $actual = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLower()
        if ($expected -ne $actual) {
            throw "Checksum mismatch.`n  expected $expected`n  got      $actual`nRefusing to install."
        }
        Write-Detail "ok ($actual)"
    } catch [System.Net.WebException] {
        Write-Warn 'No published checksum found; the download could not be verified.'
    }

    # --- install -------------------------------------------------------------------

    Write-Step "Installing into $Dir"
    $unpacked = Join-Path $tmp 'unpacked'
    Expand-Archive -Path $archive -DestinationPath $unpacked -Force

    # The archive holds a single versioned directory; its contents become the install root
    # so the path stays stable across upgrades.
    $inner = Get-ChildItem -Path $unpacked -Directory | Select-Object -First 1
    if (-not $inner) { throw 'Unexpected archive layout.' }

    if (Test-Path $Dir) {
        Write-Detail 'replacing the existing installation (a downloaded JVM is kept)'
        Get-ChildItem -Path $Dir -Force |
            Where-Object { $_.Name -ne '.jvm' } |
            Remove-Item -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    Copy-Item -Path (Join-Path $inner.FullName '*') -Destination $Dir -Recurse -Force

    $installedVersion = if (Test-Path (Join-Path $Dir 'VERSION')) {
        (Get-Content (Join-Path $Dir 'VERSION') -Raw).Trim()
    } else { '?' }
    Write-Detail "version $installedVersion installed"
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# --- JVM ---------------------------------------------------------------------------

Write-Host ''
Write-Host 'No JVM was downloaded. On Windows use a JDK 21 or later whose bitness matches'
Write-Host 'the Notes client (32-bit for Notes 12 and earlier), and point'
Write-Host 'DOMINO_RUNNER_JAVA_HOME at it.'

# --- PATH --------------------------------------------------------------------------

$binDir = Join-Path $Dir 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')

if ($userPath -notlike "*$binDir*") {
    if ($AddToPath) {
        # Only with the explicit switch: changing someone's environment uninvited is not
        # the installer's business.
        Write-Step "Adding $binDir to the user PATH"
        [Environment]::SetEnvironmentVariable('Path', "$binDir;$userPath", 'User')
        Write-Detail 'open a new terminal for it to take effect'
    } else {
        Write-Host ''
        Write-Host 'Add it to your PATH:'
        Write-Host "  `$env:Path = `"$binDir;`$env:Path`"" -ForegroundColor White
        Write-Host '  (or re-run this installer with -AddToPath)' -ForegroundColor DarkGray
    }
}

# --- next steps -------------------------------------------------------------------

Write-Host ''
Write-Step 'Installed'
# Validation is not run automatically here: it needs a JVM, and on Windows one has to be
# chosen by hand because of the bitness question above.
Write-Host 'Once DOMINO_RUNNER_JAVA_HOME points at a suitable JVM, check the setup with:'
Write-Host "  $binDir\domino-runner-windows.cmd com.factory.domino.runner.DominoDoctor" -ForegroundColor White
