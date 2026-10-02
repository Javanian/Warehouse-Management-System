# Stops the project-owned PostgreSQL cluster (fast shutdown; data is kept).
. "$PSScriptRoot\_common.ps1"
$bin = Get-PgBin
if (-not (Test-Path (Join-Path $PgData 'PG_VERSION'))) { Write-Step 'No project cluster initialised; nothing to stop.'; exit 0 }
$st = & (Join-Path $bin 'pg_ctl.exe') status -D $PgData 2>&1 | Out-String
if ($st -notmatch 'server is running') { Write-Step 'PostgreSQL (project cluster) is not running.'; exit 0 }
& (Join-Path $bin 'pg_ctl.exe') stop -D $PgData -m fast -w 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'pg_ctl stop failed' }
Write-Step 'PostgreSQL stopped (data preserved in .runtime\pgdata).'
