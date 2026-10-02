# Runs the backend Maven build with the portable JDK and the project test DB password.
# Output (UTF-8) goes to .runtime\mvn.log unless -Log is given. Exit code = Maven exit code.
param([string]$Goals = 'test', [string]$Log = '')
. "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Continue'
if (-not $Log) { $Log = Join-Path $Runtime 'mvn.log' }
$env:JAVA_HOME = Get-JavaHome
$env:STOCKFLOW_TEST_DB_PASSWORD = (Get-LocalSecrets).appDbPassword
Push-Location (Join-Path $Root 'backend')
try {
    # cmd redirection keeps Maven output as UTF-8/ANSI text (PowerShell 5.1 '*>' writes UTF-16).
    cmd /c "mvnw.cmd -B $Goals > `"$Log`" 2>&1"
    $code = $LASTEXITCODE
} finally { Pop-Location }
"exit=$code" | Out-File -FilePath $Log -Append -Encoding ascii
exit $code
