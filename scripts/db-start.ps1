# Starts (and on first run initialises) the project-owned PostgreSQL cluster on 127.0.0.1:$PgPort.
# Never touches other PostgreSQL services or data directories.
param([int]$TimeoutSeconds = 60)
. "$PSScriptRoot\_common.ps1"

$bin = Get-PgBin
$sec = Get-LocalSecrets

if (-not (Test-Path (Join-Path $PgData 'PG_VERSION'))) {
    Write-Step "Initialising PostgreSQL cluster in $PgData"
    $pwFile = Join-Path $Runtime 'pwfile.tmp'
    Set-Content -Path $pwFile -Value $sec.pgSuperPassword -Encoding ASCII -NoNewline
    try {
        & (Join-Path $bin 'initdb.exe') -D $PgData -U postgres --pwfile=$pwFile --auth-host=scram-sha-256 --auth-local=scram-sha-256 -E UTF8 --locale=C 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "initdb failed (exit $LASTEXITCODE)" }
    } finally { Remove-Item $pwFile -ErrorAction SilentlyContinue }
    Add-Content -Path (Join-Path $PgData 'postgresql.conf') -Value @"

# --- StockFlow local settings ---
listen_addresses = '127.0.0.1'
port = $PgPort
max_connections = 100
shared_buffers = 128MB
log_timezone = 'UTC'
timezone = 'UTC'
"@
}

if (Test-PortListening $PgPort) {
    $st = & (Join-Path $bin 'pg_ctl.exe') status -D $PgData 2>&1 | Out-String
    if ($st -match 'server is running') { Write-Step "PostgreSQL already running on port $PgPort" }
    else { throw "Port $PgPort is used by another process. Set STOCKFLOW_PG_PORT to a free port and re-run." }
} else {
    Write-Step "Starting PostgreSQL on 127.0.0.1:$PgPort (log: $PgLog)"
    # Launch pg_ctl through Win32_Process.Create: the server is fully detached and inherits no handles from this
    # console (Start-Process with redirection makes postgres hold the caller's stdout pipe open forever).
    Start-Detached -CommandLine "`"$(Join-Path $bin 'pg_ctl.exe')`" start -D `"$PgData`" -l `"$PgLog`" -W" -WorkingDirectory $Runtime | Out-Null
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ($true) {
    & (Join-Path $bin 'pg_isready.exe') -h 127.0.0.1 -p $PgPort -q
    if ($LASTEXITCODE -eq 0) { break }
    if ((Get-Date) -gt $deadline) { throw "PostgreSQL not ready after $TimeoutSeconds s. See $PgLog" }
    Start-Sleep -Milliseconds 500
}

# Application role and databases (idempotent)
$appPw = $sec.appDbPassword
$roleExists = Invoke-Psql 'postgres' "select 1 from pg_roles where rolname='stockflow'"
if (-not $roleExists) { Invoke-Psql 'postgres' "create role stockflow login password '$appPw'" | Out-Null }
else { Invoke-Psql 'postgres' "alter role stockflow password '$appPw'" | Out-Null }
foreach ($db in @('stockflow', 'stockflow_test', 'stockflow_e2e')) {
    $exists = Invoke-Psql 'postgres' "select 1 from pg_database where datname='$db'"
    if (-not $exists) { Invoke-Psql 'postgres' "create database $db owner stockflow" | Out-Null; Write-Step "Created database $db" }
}
Write-Step "PostgreSQL ready: jdbc:postgresql://127.0.0.1:$PgPort/stockflow (user stockflow, password in .runtime\secrets.json)"
