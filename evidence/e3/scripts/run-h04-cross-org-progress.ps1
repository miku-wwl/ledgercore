param(
    [string]$RunLabel
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
if ((& git -C $repositoryRoot branch --show-current).Trim() -cne 'main') {
    throw 'E3 H04 is restricted to main.'
}

$pg = Join-Path $repositoryRoot 'work\postgresql-binaries\pgsql\bin\psql.exe'
if (-not (Test-Path -LiteralPath $pg)) { throw "Local PostgreSQL 18.7 psql was not found: $pg" }
$measurements = Join-Path $repositoryRoot 'evidence\e3\measurements'
if ([string]::IsNullOrWhiteSpace($RunLabel)) {
    $RunLabel = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
}
if ($RunLabel -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$') {
    throw 'RunLabel may contain only letters, digits, underscores, and hyphens.'
}
$output = Join-Path $measurements "h04-cross-org-progress-$RunLabel"
if (Test-Path -LiteralPath $output) { throw "H04 output already exists; preserve it and choose a new RunLabel: $output" }
New-Item -ItemType Directory -Path $output | Out-Null

$batchId = (& $pg -X -w -tA -h 127.0.0.1 -p 55434 -U ledgercore_test -d ledgercore_e3_perf `
    -v ON_ERROR_STOP=1 -c "SELECT id FROM payroll_batches WHERE name = 'E3 plan-5000'").Trim()
if ($LASTEXITCODE -ne 0 -or $batchId -notmatch '^[0-9a-f-]{36}$') {
    throw 'Could not resolve the unique H03 5,000-item target batch in ledgercore_e3_perf.'
}

$sourceCommit = (& git -C $repositoryRoot log -1 --format=%H -- backend/src/test/java/nz/ac/ledgercore/performance/E3PerformanceExperimentHarness.java).Trim()
$capturedAtUtc = [DateTime]::UtcNow.ToString('o')
@"
{
  "capturedAtUtc": "$capturedAtUtc",
  "sourceCommit": "$sourceCommit",
  "preregistrationCommit": "$(git -C $repositoryRoot rev-parse HEAD)",
  "branch": "main",
  "database": "ledgercore_e3_perf",
  "host": "127.0.0.1:55434",
  "targetBatchId": "$batchId",
  "unrelatedFixture": "10 synthetic organizations x 5,000 DRAFT payroll items/results"
}
"@ | Set-Content -LiteralPath (Join-Path $output 'environment.json')

& $pg -X -w -h 127.0.0.1 -p 55434 -U ledgercore_test -d ledgercore_e3_perf `
    -v ON_ERROR_STOP=1 -v target_batch_id=$batchId `
    -f (Join-Path $repositoryRoot 'evidence\e3\scripts\h04-cross-org-progress.sql') `
    2>&1 | Tee-Object -FilePath (Join-Path $output 'commands-and-results.log')
if ($LASTEXITCODE -ne 0) { throw "H04 SQL experiment failed with psql exit code $LASTEXITCODE" }

Write-Host "H04 evidence: $output"
