# Shared settings for StockFlow local scripts. Dot-source: . "$PSScriptRoot\_common.ps1"
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$script:Root     = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:Runtime  = Join-Path $Root '.runtime'
$script:PgData   = Join-Path $Runtime 'pgdata'
$script:PgLog    = Join-Path $Runtime 'postgres.log'
$script:AppLog   = Join-Path $Runtime 'app.log'
$script:AppErr   = Join-Path $Runtime 'app.err.log'
$script:AppPid   = Join-Path $Runtime 'app.pid'
$script:SecretsFile = Join-Path $Runtime 'secrets.json'
$script:PgPort   = if ($env:STOCKFLOW_PG_PORT) { [int]$env:STOCKFLOW_PG_PORT } else { 55432 }
$script:AppPort  = if ($env:STOCKFLOW_APP_PORT) { [int]$env:STOCKFLOW_APP_PORT } else { 8085 }
$script:JarPath  = Join-Path $Root 'backend\target\stockflow-backend.jar'

function Write-Step([string]$msg) { Write-Host "[stockflow] $msg" -ForegroundColor Cyan }
function Write-Warn2([string]$msg) { Write-Host "[stockflow] WARNING: $msg" -ForegroundColor Yellow }

function Get-PgBin {
    $candidates = @()
    if ($env:STOCKFLOW_PG_BIN) { $candidates += $env:STOCKFLOW_PG_BIN }
    $candidates += Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [int]($_.Name -replace '\D','') } -Descending | ForEach-Object { Join-Path $_.FullName 'bin' }
    $onPath = Get-Command initdb.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path $onPath.Source) }
    foreach ($c in $candidates) { if (Test-Path (Join-Path $c 'pg_ctl.exe')) { return $c } }
    throw "PostgreSQL binaries (initdb/pg_ctl) not found. Install PostgreSQL 16+ or set STOCKFLOW_PG_BIN to its bin folder."
}

function Get-JavaHome {
    $local = Get-ChildItem (Join-Path $Root '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($local) { return $local.FullName }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $v = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Out-String
        if ($v -match 'version "2[1-9]') { return $env:JAVA_HOME }
    }
    throw "Java 21 not found. Run scripts\setup.ps1 (downloads a portable Temurin 21 into .tools) or set JAVA_HOME to a JDK 21+."
}

function New-RandomSecret([int]$len = 24) {
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] $len
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Get-LocalSecrets {
    if (-not (Test-Path $Runtime)) { New-Item -ItemType Directory -Path $Runtime | Out-Null }
    if (-not (Test-Path $SecretsFile)) {
        $s = [ordered]@{ pgSuperPassword = (New-RandomSecret); appDbPassword = (New-RandomSecret) }
        ($s | ConvertTo-Json) | Set-Content -Path $SecretsFile -Encoding ASCII
    }
    return (Get-Content $SecretsFile -Raw | ConvertFrom-Json)
}

function Test-PortListening([int]$port) {
    $c = New-Object System.Net.Sockets.TcpClient
    try { $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null); $ok = $iar.AsyncWaitHandle.WaitOne(300); if ($ok) { $c.EndConnect($iar) }; return ($ok -and $c.Connected) }
    catch { return $false } finally { $c.Close() }
}

function Invoke-Psql([string]$db, [string]$sql) {
    $s = Get-LocalSecrets
    $env:PGPASSWORD = $s.pgSuperPassword
    try {
        $out = & (Join-Path (Get-PgBin) 'psql.exe') -h 127.0.0.1 -p $PgPort -U postgres -d $db -v ON_ERROR_STOP=1 -tA -c $sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "psql failed ($db): $out" }
        return $out
    } finally { Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue }
}

function Start-Detached([string]$CommandLine, [string]$WorkingDirectory) {
    # Creates a process that is not a child of this shell and inherits no handles (hidden window).
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = $CommandLine; CurrentDirectory = $WorkingDirectory; ProcessStartupInformation = $startup }
    if ($r.ReturnValue -ne 0) { throw "Win32_Process.Create failed (code $($r.ReturnValue)) for: $CommandLine" }
    return [int]$r.ProcessId
}

function Set-AppDbEnv([string]$dbName = 'stockflow') {
    $s = Get-LocalSecrets
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$PgPort/$dbName"
    $env:DB_USERNAME = 'stockflow'
    $env:DB_PASSWORD = $s.appDbPassword
}
