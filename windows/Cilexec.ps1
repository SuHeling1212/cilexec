<#
.SYNOPSIS
Native Windows host controller for the CilExec Docker Desktop distribution.

.EXAMPLE
.\Cilexec.ps1 install
.\Cilexec.ps1 terminal
.\Cilexec.ps1 headless -Source 'counter = 1'
.\Cilexec.ps1 host-move -HostFile .\editor.db -VfsPath /editor.db -Username alice
.\Cilexec.ps1 shell -Target program
.\Cilexec.ps1 uninstall -Force
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'terminal', 'headless', 'host-move', 'shell', 'uninstall')]
    [string] $Command = 'install',
    [switch] $Force,
    [string] $Source,
    [string] $HostFile,
    [string] $VfsPath,
    [string] $Username,
    [ValidateSet('program', 'data')]
    [string] $Target = 'program'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$ProjectDir = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\', '/')
$ComposeFiles = @(
    '-f', (Join-Path $ProjectDir 'compose.yml'),
    '-f', (Join-Path $ProjectDir 'docker\compose\persistent.yml')
)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Invoke-Docker {
    param([string[]] $Arguments, [switch] $Capture, [switch] $AllowFailure)
    if ($Capture) {
        $result = & docker.exe @Arguments 2>&1
    } else {
        & docker.exe @Arguments
        $result = $null
    }
    $status = $LASTEXITCODE
    if ($status -ne 0 -and -not $AllowFailure) {
        throw "docker.exe failed with exit code ${status}: $($Arguments -join ' ')"
    }
    if ($Capture) {
        return [pscustomobject]@{ Status = $status; Output = (($result | Out-String).Trim()) }
    }
    return $status
}

function Invoke-Compose {
    param([string[]] $Arguments, [switch] $Capture, [switch] $AllowFailure)
    return Invoke-Docker -Arguments (@('compose') + $ComposeFiles + $Arguments) `
        -Capture:$Capture -AllowFailure:$AllowFailure
}

function Invoke-ComposeInteractive {
    param([string[]] $Arguments)
    $nativeArguments = @('compose') + $ComposeFiles + $Arguments
    & docker.exe @nativeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Interactive Docker Compose command failed with exit code $LASTEXITCODE."
    }
}

function Initialize-Environment {
    $normalized = $ProjectDir.ToLowerInvariant() + "`n"
    $bytes = $Utf8NoBom.GetBytes($normalized)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    if (-not $env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME = "cilexec-$($hash.Substring(0, 8))" }
    if (-not $env:CILEXEC_POSTGRES_VOLUME) { $env:CILEXEC_POSTGRES_VOLUME = "cilexec-pgdata-$($hash.Substring(0, 8))" }
    if (-not $env:CILEXEC_CONTAINER_UID) { $env:CILEXEC_CONTAINER_UID = '10001' }
    if (-not $env:CILEXEC_CONTAINER_GID) { $env:CILEXEC_CONTAINER_GID = '10001' }
    if ($env:CILEXEC_CONTAINER_UID -notmatch '^[1-9][0-9]*$' -or
            $env:CILEXEC_CONTAINER_GID -notmatch '^[1-9][0-9]*$') {
        throw 'CILEXEC_CONTAINER_UID/GID must be positive Linux numeric IDs.'
    }
    $script:TerminalPort = $env:CILEXEC_TERMINAL_PORT
    if (-not $script:TerminalPort) { $script:TerminalPort = '8022' }
    if ($script:TerminalPort -notmatch '^[0-9]+$' -or [int]$script:TerminalPort -lt 1 -or
            [int]$script:TerminalPort -gt 65535) {
        throw 'CILEXEC_TERMINAL_PORT must be an integer from 1 to 65535.'
    }
}

function Assert-HostRequirements {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
        throw 'Docker Desktop is required.'
    }
    $mode = Invoke-Docker -Arguments @('info', '--format', '{{.OSType}}') -Capture
    if ($mode.Output -ne 'linux') {
        throw 'Docker Desktop must be running in Linux-container mode.'
    }
    [void](Invoke-Docker -Arguments @('compose', 'version'))
}

function Assert-NotReparsePoint {
    param([string] $Path)
    if (Test-Path -LiteralPath $Path) {
        $item = Get-Item -LiteralPath $Path -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Reparse points are not allowed: $Path"
        }
    }
}

function Write-EnvironmentFile {
    $path = Join-Path $ProjectDir '.env'
    Assert-NotReparsePoint $path
    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $path) {
        $lines.AddRange([System.IO.File]::ReadAllLines($path, $Utf8NoBom))
    }
    if (-not ($lines -match '^CILEXEC_IMAGE=')) { $lines.Add('CILEXEC_IMAGE=cilexec:local') }
    if (-not ($lines -match '^CILEXEC_CONTAINER_UID=')) { $lines.Add("CILEXEC_CONTAINER_UID=$env:CILEXEC_CONTAINER_UID") }
    if (-not ($lines -match '^CILEXEC_CONTAINER_GID=')) { $lines.Add("CILEXEC_CONTAINER_GID=$env:CILEXEC_CONTAINER_GID") }
    [System.IO.File]::WriteAllLines($path, $lines, $Utf8NoBom)
    try {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $path '/inheritance:r' "/grant:r" "${identity}:F" 'SYSTEM:F' | Out-Null
    } catch {
        Write-Warning "Could not restrict .env ACL: $($_.Exception.Message)"
    }
}

function Get-FileSha256 {
    param([string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Ensure-BundledImage {
    if ($env:CILEXEC_IMAGE -and $env:CILEXEC_IMAGE -ne 'cilexec:local') {
        [void](Invoke-Docker -Arguments @('pull', $env:CILEXEC_IMAGE))
        return
    }
    $manifestPath = Join-Path $ProjectDir 'package-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        if ((Invoke-Docker -Arguments @('image', 'inspect', 'cilexec:local') -AllowFailure) -ne 0) {
            throw 'Bundled package manifest is missing and cilexec:local is unavailable.'
        }
        return
    }
    $architecture = (Invoke-Docker -Arguments @('info', '--format', '{{.Architecture}}') -Capture).Output
    switch -Regex ($architecture) {
        '^(amd64|x86_64)$' { $architecture = 'amd64'; break }
        '^(arm64|aarch64)$' { $architecture = 'arm64'; break }
        default { throw "Unsupported Docker architecture: $architecture" }
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $entry = $manifest.images.PSObject.Properties[$architecture].Value
    if ($null -eq $entry) { throw "Package does not contain linux/$architecture" }
    $archive = Join-Path $ProjectDir $entry.archive
    if ((Get-FileSha256 $archive) -ne $entry.sha256) { throw "Bundled image checksum mismatch: $archive" }
    [void](Invoke-Docker -Arguments @('load', '--input', $archive))
    $loadedPlatform = (Invoke-Docker -Arguments @('image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', $entry.image) -Capture).Output
    if ($loadedPlatform -ne "linux/$architecture") { throw "Loaded image platform mismatch: $loadedPlatform" }
    if ($entry.image -ne 'cilexec:local') { [void](Invoke-Docker -Arguments @('tag', $entry.image, 'cilexec:local')) }
    $env:CILEXEC_IMAGE = 'cilexec:local'
}

function Ensure-Secrets {
    $secretDir = Join-Path $ProjectDir 'docker\secrets'
    if (-not (Test-Path -LiteralPath $secretDir)) { [void](New-Item -ItemType Directory -Path $secretDir) }
    Assert-NotReparsePoint $secretDir
    $generator = Join-Path $ProjectDir 'docker\create-secrets.sh'
    $postgresImage = 'postgres:17.10-bookworm@sha256:9b18b78397054fce88a9552e9d5a3ad5bb7fd258c5b3cc1c5028e46373d6ea8f'
    $generatorMount = "$generator`:/tool/create-secrets.sh:ro"
    $secretMount = "$secretDir`:/docker/secrets"
    [void](Invoke-Docker -Arguments @('run', '--rm', '--user', '0:0',
        '--env', "CILEXEC_CONTAINER_UID=$env:CILEXEC_CONTAINER_UID",
        '--env', "CILEXEC_CONTAINER_GID=$env:CILEXEC_CONTAINER_GID",
        '--volume', $generatorMount, '--volume', $secretMount,
        '--entrypoint', '/bin/bash', $postgresImage, '/tool/create-secrets.sh'))
    $names = @('postgres-admin-password', 'cilexec-migrator-password',
        'cilexec-runtime-password', 'cilexec-effect-worker-password',
        'cilexec-readonly-password', 'cilexec-exporter-password')
    foreach ($name in $names) {
        $value = [System.IO.File]::ReadAllText((Join-Path $secretDir $name), $Utf8NoBom)
        if ($value -notmatch '^[0-9a-f]{64}$') { throw "Invalid generated secret: $name" }
    }
}

function Test-ServiceRunning {
    param([string] $Service)
    $result = Invoke-Compose -Arguments @('ps', '--status', 'running', '-q', $Service) -Capture -AllowFailure
    return $result.Status -eq 0 -and -not [string]::IsNullOrWhiteSpace($result.Output)
}

function Get-RunningCilexecState {
    $container = Invoke-Compose -Arguments @('ps', '-q', 'cilexec') -Capture -AllowFailure
    if ($container.Status -ne 0 -or [string]::IsNullOrWhiteSpace($container.Output)) {
        return [pscustomobject]@{ Exists = $false; State = ''; ImageId = '' }
    }
    $containerId = $container.Output.Trim()
    $state = (Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Status}}',
        $containerId) -Capture -AllowFailure).Output
    $imageId = (Invoke-Docker -Arguments @('inspect', '--format', '{{.Image}}',
        $containerId) -Capture -AllowFailure).Output
    return [pscustomobject]@{ Exists = $true; State = $state; ImageId = $imageId }
}

function Start-Services {
    Ensure-BundledImage
    Write-EnvironmentFile
    Ensure-Secrets
    [void](Invoke-Compose -Arguments @('up', '-d', 'postgres'))
    $runtime = Get-RunningCilexecState
    $runtimeNeedsMigration = -not $runtime.Exists
    if ($runtime.Exists -and $runtime.State -eq 'running') {
        $targetImage = (Invoke-Docker -Arguments @('image', 'inspect', '--format', '{{.Id}}',
            $env:CILEXEC_IMAGE) -Capture).Output
        if ($runtime.ImageId -ne $targetImage) {
            Write-Host 'Stopping the shared Runtime to migrate before activating the new image.'
            [void](Invoke-Compose -Arguments @('stop', 'cilexec'))
            $runtimeNeedsMigration = $true
        }
    } elseif ($runtime.Exists -and $runtime.State -in @('paused', 'restarting')) {
        if ($runtime.State -eq 'paused') {
            [void](Invoke-Docker -Arguments @('unpause', (Invoke-Compose -Arguments @('ps', '-q', 'cilexec') -Capture).Output.Trim()))
        }
        [void](Invoke-Compose -Arguments @('stop', 'cilexec'))
        $runtimeNeedsMigration = $true
    }
    if ($runtimeNeedsMigration) {
        [void](Invoke-Compose -Arguments @('run', '--rm', 'migrate'))
    }
    [void](Invoke-Compose -Arguments @('up', '-d', '--no-deps', 'cilexec'))
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $probe = Invoke-Compose -Arguments @('exec', '-T', 'cilexec',
            '/usr/local/bin/cilexec-terminal-client', '--probe', $script:TerminalPort) -AllowFailure
        if ($probe -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        [void](Invoke-Compose -Arguments @('logs', '--tail=80', 'cilexec') -AllowFailure)
        throw 'CilExec Runtime did not open its terminal port.'
    }
}

function Open-Terminal {
    if (-not (Test-ServiceRunning 'cilexec')) { throw 'Runtime is not running; run install first.' }
    Invoke-ComposeInteractive -Arguments @('exec', 'cilexec',
        '/usr/local/bin/cilexec-terminal-client', $script:TerminalPort)
}

function Quote-NativeArgument {
    param([string] $Value)
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Invoke-Headless {
    if ([string]::IsNullOrEmpty($Source)) { throw 'headless requires -Source.' }
    if (-not (Test-ServiceRunning 'cilexec')) { throw 'Runtime is not running; run install first.' }
    if (-not $Username) { $Username = if ($env:CILEXEC_TERMINAL_USERNAME) { $env:CILEXEC_TERMINAL_USERNAME } else { 'local' } }
    $context = $env:CILEXEC_HEADLESS_CONTEXT
    if (-not $context) {
        $seed = if ($env:WT_SESSION) { $env:WT_SESSION } else { "pid-$PID" }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $digest = ([BitConverter]::ToString($sha.ComputeHash($Utf8NoBom.GetBytes($seed)))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        $context = "windows-$($digest.Substring(0, 32))"
    }
    $secure = Read-Host "$Username password" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $plain = $null
    $payload = $null
    try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $arguments = @('compose') + $ComposeFiles + @('exec', '-T', 'cilexec',
        '/usr/local/bin/cilexec-terminal-client', '--headless', $script:TerminalPort, $context, $Username)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = 'docker.exe'
    $start.Arguments = (($arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Could not start docker.exe.' }
        $payload = $Utf8NoBom.GetBytes($plain + "`n" + $Source)
        $process.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(900000)) {
            [void]$process.Kill()
            throw 'Headless terminal client timed out and was terminated.'
        }
        try {
            $exitCode = $process.ExitCode
        } catch {
            throw 'Headless terminal client exited without a status code.'
        }
        if ($exitCode -ne 0) { exit $exitCode }
    } finally {
        if ($plain) { $plain = $null }
        if ($payload) { [Array]::Clear($payload, 0, $payload.Length) }
        $process.Dispose()
    }
}

function Invoke-HostMove {
    if (-not $HostFile -or -not $VfsPath -or -not $Username) {
        throw 'host-move requires -HostFile, -VfsPath, and -Username.'
    }
    if (-not $VfsPath.StartsWith('/') -or $VfsPath -eq '/') { throw 'VFS path must be absolute and non-root.' }
    $resolved = [System.IO.Path]::GetFullPath($HostFile)
    Assert-NotReparsePoint $resolved
    $item = Get-Item -LiteralPath $resolved
    if ($item.PSIsContainer) { throw 'Host source must be a regular file.' }
    Ensure-BundledImage
    Ensure-Secrets
    [void](Invoke-Compose -Arguments @('up', '-d', 'postgres'))
    [void](Invoke-Compose -Arguments @('run', '--rm', 'migrate'))
    [void](Invoke-Compose -Arguments @('run', '--rm', '--no-deps', '--user',
        "$env:CILEXEC_CONTAINER_UID`:$env:CILEXEC_CONTAINER_GID", '--volume',
        "$resolved`:/tmp/cilexec-host-import:ro", 'cilexec', 'host', 'move',
        '/tmp/cilexec-host-import', $VfsPath, $Username))
    Write-Host "Copied host file into CilExec VFS; source retained: $resolved"
}

function Remove-Installation {
    if (-not $Force) {
        $confirmation = Read-Host 'Type yes to remove this CilExec instance and its database volume'
        if ($confirmation -ne 'yes') { Write-Host 'Cancelled.'; return }
    }
    [void](Invoke-Compose -Arguments @('down', '--volumes', '--remove-orphans') -AllowFailure)
    $secretDir = Join-Path $ProjectDir 'docker\secrets'
    $generated = @('postgres-admin-password', 'cilexec-migrator-password',
        'cilexec-runtime-password', 'cilexec-effect-worker-password',
        'cilexec-readonly-password', 'cilexec-exporter-password',
        'postgres-ca.crt', 'postgres-server.crt', 'postgres-server.key')
    foreach ($name in $generated) { Remove-Item -LiteralPath (Join-Path $secretDir $name) -Force -ErrorAction SilentlyContinue }
    $exports = Join-Path $ProjectDir 'exports'
    Assert-NotReparsePoint $exports
    Remove-Item -LiteralPath $exports -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host 'CilExec installation resources were removed.'
}

Push-Location $ProjectDir
try {
    Initialize-Environment
    Assert-HostRequirements
    switch ($Command) {
        'install' { Start-Services; Open-Terminal }
        'terminal' { Open-Terminal }
        'headless' { Invoke-Headless }
        'host-move' { Invoke-HostMove }
        'shell' {
            if ($Target -eq 'data') { Invoke-ComposeInteractive -Arguments @('exec', '-it', 'postgres', '/bin/bash') }
            else { Invoke-ComposeInteractive -Arguments @('exec', '--user', 'root', '-it', 'cilexec', '/bin/bash') }
        }
        'uninstall' { Remove-Installation }
    }
} finally {
    Pop-Location
}
