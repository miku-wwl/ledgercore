[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('verify', 'fullstack', 'collect')] [string] $Mode,
    [string] $RunId = ([guid]::NewGuid().ToString()),
    # collect: the E12 run ids whose verify/fullstack results and the lab run ids to combine into one assessment.
    [string[]] $Include = @(),
    [string] $Label = ''
)

# E12 independent assessment runner (Round 4). Modes:
#   verify    backend + simulator `clean verify` on fresh E12 databases, JUnit XML mapped to E12 cases, the executed E8
#             matrix, and the independent SQL financial grader over everything the suites wrote.
#   fullstack separate bank and simulator processes on fresh E12 databases, the real-API Chromium suite, then the grader.
#   collect   combines verify/fullstack results with the E6 lab verdicts named in cases.json into
#             evidence/round4/e12/E12-RESULTS.json; a case passes only if every one of its sources passed.
# Isolation: a dedicated cluster under work/e12-lab/pg on 127.0.0.1:55460 (created on first use); only databases named
# ledgercore_e12_<run>_* are created; the persistent service on 55432 is never contacted.

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pgBin = Join-Path $repo 'work/postgresql-binaries/pgsql/bin'
$psql = Join-Path $pgBin 'psql.exe'
$java = Join-Path $repo 'work/toolchain/jdk21/jdk-21.0.13+9/bin/java.exe'
$mvnw = Join-Path $repo 'backend/mvnw.cmd'
$pgData = Join-Path $repo 'work/e12-lab/pg'
$port = 55460
$role = 'ledgercore_e12'
$runsRoot = Join-Path $repo 'work/e12-lab/runs'
$evidenceRoot = Join-Path $repo 'evidence/round4/e12'
$casesPath = Join-Path $PSScriptRoot 'cases.json'
$grader = Join-Path $PSScriptRoot 'graders/financial-hard-gates.sql'
$hardGateFields = @('unbalancedJournals', 'nonPositiveOrForeignCurrencyPostings', 'projectionMismatches', 'negativeNormalBalances',
    'balanceSheetDifferenceMinor', 'duplicatePaymentJournals', 'completedWithoutSettlement', 'settlementWithoutSettledOutcome',
    'refundAfterSettlementOrWithoutRejection', 'bookedWithoutIndependentApproval', 'bookedPaymentJournalMismatches',
    'transfersWithoutExactJournal', 'transfersWithoutOneSucceededReceipt', 'payrollSucceededWithoutTransfer',
    'payrollItemsPaidMoreThanOnce', 'completedPayrollWithUnfinishedItems', 'callbackInboxDuplicateSequences',
    'deliveredWorkMaskingOwedEffect')

function Write-Json([string] $Path, [object] $Value) {
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $Value | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Get-Listener([int] $Port) {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)"
}

function Start-E12Cluster {
    $listener = Get-Listener $port
    if ($listener) {
        if ($listener.Name -ne 'postgres.exe' -or $listener.CommandLine -notlike "*$($pgData.Replace('\', '/'))*") {
            throw "Port $port is not the E12 cluster; refusing to continue."
        }
        return
    }
    if (-not (Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION'))) {
        New-Item -ItemType Directory -Path $pgData -Force | Out-Null
        & (Join-Path $pgBin 'initdb.exe') -D $pgData -U postgres --auth-local=trust --auth-host=trust --no-locale --encoding=UTF8 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed for the E12 cluster.' }
    }
    $log = Join-Path $repo 'work/e12-lab/pg.log'
    $process = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -ArgumentList "-D `"$pgData`" -l `"$log`" -o `"-h 127.0.0.1 -p $port -c max_connections=200`" -w start" `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $repo 'work/e12-lab/pgctl.out') -RedirectStandardError (Join-Path $repo 'work/e12-lab/pgctl.err')
    if (-not $process.WaitForExit(30000) -or $process.ExitCode -ne 0) { throw 'pg_ctl could not start the E12 cluster.' }
    & $psql -X -q -h 127.0.0.1 -p $port -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$role'" | Out-String | ForEach-Object {
        if ($_.Trim() -ne '1') { & $psql -X -q -h 127.0.0.1 -p $port -U postgres -d postgres -c "CREATE ROLE $role LOGIN" | Out-Null }
    }
}

function New-E12Database([string] $Suffix) {
    $name = "ledgercore_e12_$($RunId.Replace('-', '').Substring(0, 8))_$Suffix"
    if ($name -notmatch '^ledgercore_e12_[a-z0-9_]+$') { throw "Unexpected database name $name" }
    $exists = (& $psql -X -q -t -A -h 127.0.0.1 -p $port -U postgres -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$name'").Trim()
    if ($exists -ne '0') { throw "E12 database $name already exists; a run never reuses a database." }
    & $psql -X -q -h 127.0.0.1 -p $port -U postgres -d postgres -c "CREATE DATABASE $name OWNER $role" | Out-Null
    return $name
}

function Invoke-Grader([string] $Database) {
    $identity = (& $psql -X -q -t -A -h 127.0.0.1 -p $port -U $role -d $Database -c "SELECT current_database() || ':' || inet_server_port()").Trim()
    if ($identity -ne "$Database`:$port") { throw "Grader target identity mismatch: $identity" }
    $json = (& $psql -X -q -t -A -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $port -U $role -d $Database -f $grader) -join ''
    $result = $json | ConvertFrom-Json
    $failed = @($hardGateFields | Where-Object { [long]$result.$_ -ne 0 })
    return [ordered]@{ database = $Database; result = $result; failedHardGates = $failed; pass = ($failed.Count -eq 0) }
}

function Read-SurefireReports([string] $Directory) {
    $cases = @()
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($case in $xml.testsuite.testcase) {
            $outcome = if ($case.failure) { 'FAIL' } elseif ($case.error) { 'ERROR' } elseif ($case.skipped) { 'SKIPPED' } else { 'PASS' }
            $cases += [ordered]@{ test = "$($case.classname)#$($case.name)"; outcome = $outcome; seconds = [double]$case.time }
        }
    }
    return $cases
}

function Set-JavaEnvironment {
    $env:JAVA_HOME = Join-Path $repo 'work/toolchain/jdk21/jdk-21.0.13+9'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:MAVEN_USER_HOME = Join-Path $repo 'work/maven-home'
    $env:MAVEN_OPTS = "-Dmaven.repo.local=$(Join-Path $repo 'work/m2')"
}

$branch = (& git -C $repo branch --show-current).Trim()
if ($branch -ne 'main') { throw "E12 runs only from main (current: $branch)." }
$sha = (& git -C $repo rev-parse HEAD).Trim()
$dirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
$runDir = Join-Path $runsRoot $RunId
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$summary = [ordered]@{ schemaVersion = 1; runId = $RunId; mode = $Mode; label = $Label; sourceSha = $sha; trackedTreeDirty = $dirty
    startedAt = [DateTime]::UtcNow.ToString('o') }

switch ($Mode) {
    'verify' {
        Start-E12Cluster
        Set-JavaEnvironment
        $bankDb = New-E12Database 'verify'
        $simDb = New-E12Database 'simverify'
        $env:LEDGERCORE_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$port/$bankDb"
        $env:LEDGERCORE_TEST_DB_USERNAME = $role
        $env:LEDGERCORE_TEST_DB_PASSWORD = ''
        $env:CLEARING_SIMULATOR_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$port/$simDb"
        $env:CLEARING_SIMULATOR_TEST_DB_USERNAME = $role
        $env:CLEARING_SIMULATOR_TEST_DB_PASSWORD = ''
        Push-Location $repo
        try {
            & $mvnw --batch-mode --no-transfer-progress -f backend/pom.xml clean verify *> (Join-Path $runDir 'backend-verify.log')
            $summary.backendExitCode = $LASTEXITCODE
            $summary.backendTests = Read-SurefireReports (Join-Path $repo 'backend/target/surefire-reports')
            $matrix = Join-Path $repo 'backend/target/e8-authorization-matrix.json'
            if (Test-Path -LiteralPath $matrix) { Copy-Item -LiteralPath $matrix (Join-Path $runDir 'e8-authorization-matrix.json') }
            & $mvnw --batch-mode --no-transfer-progress -f clearing-simulator/pom.xml clean verify *> (Join-Path $runDir 'simulator-verify.log')
            $summary.simulatorExitCode = $LASTEXITCODE
            $summary.simulatorTests = Read-SurefireReports (Join-Path $repo 'clearing-simulator/target/surefire-reports')
        }
        finally { Pop-Location }
        $summary.grader = Invoke-Grader $bankDb
    }
    'fullstack' {
        Start-E12Cluster
        Set-JavaEnvironment
        foreach ($p in 18196, 18197, 5173) { if (Get-Listener $p) { throw "Port $p is in use; stop the process that owns it first." } }
        $bankDb = New-E12Database 'fsbank'
        $simDb = New-E12Database 'fssim'
        $secretBytes = New-Object byte[] 36
        [Security.Cryptography.RandomNumberGenerator]::Fill($secretBytes)
        $secret = [Convert]::ToBase64String($secretBytes)
        $saved = @{}
        $environment = @{
            CLEARING_SIMULATOR_DB_URL = "jdbc:postgresql://127.0.0.1:$port/$simDb"; CLEARING_SIMULATOR_DB_USERNAME = $role
            CLEARING_SIMULATOR_DB_PASSWORD = ''; CLEARING_SIMULATOR_PORT = '18197'; LEDGERCORE_CLEARING_HMAC_SECRET = $secret
            LEDGERCORE_BANK_CALLBACK_URL = 'http://127.0.0.1:18196/api/clearing/callbacks'
            LEDGERCORE_DB_URL = "jdbc:postgresql://127.0.0.1:$port/$bankDb"; LEDGERCORE_DB_USERNAME = $role; LEDGERCORE_DB_PASSWORD = ''
            LEDGERCORE_COOKIE_SECURE = 'false'; LEDGERCORE_CLEARING_ENABLED = 'true'; LEDGERCORE_CLEARING_SIMULATOR_URL = 'http://127.0.0.1:18197'
            VITE_API_TARGET = 'http://127.0.0.1:18196'
        }
        foreach ($name in $environment.Keys) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process'); [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process') }
        $sim = $null; $bank = $null
        try {
            $sim = Start-Process -FilePath $java -ArgumentList @('-jar', (Join-Path $repo 'clearing-simulator/target/clearing-simulator-0.0.1-SNAPSHOT.jar'), '--server.address=127.0.0.1') `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'simulator.out.log') -RedirectStandardError (Join-Path $runDir 'simulator.err.log')
            $bank = Start-Process -FilePath $java -ArgumentList @('-jar', (Join-Path $repo 'backend/target/ledgercore-0.0.1-SNAPSHOT.jar'), '--server.address=127.0.0.1', '--server.port=18196') `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'bank.out.log') -RedirectStandardError (Join-Path $runDir 'bank.err.log')
            $deadline = [DateTime]::UtcNow.AddSeconds(120)
            do {
                Start-Sleep -Seconds 2
                $up = $false
                try { $up = (Invoke-RestMethod -Uri 'http://127.0.0.1:18196/actuator/health' -TimeoutSec 2).status -eq 'UP' } catch { }
            } while (-not $up -and [DateTime]::UtcNow -lt $deadline)
            if (-not $up) { throw 'The E12 bank did not become healthy.' }
            Push-Location (Join-Path $repo 'frontend')
            try {
                $env:PLAYWRIGHT_JSON_OUTPUT_NAME = Join-Path $runDir 'playwright.json'
                & npx playwright test --reporter=list,json *> (Join-Path $runDir 'playwright.log')
                $summary.playwrightExitCode = $LASTEXITCODE
            }
            finally { Pop-Location; Remove-Item Env:PLAYWRIGHT_JSON_OUTPUT_NAME -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 5
            $report = Get-Content -LiteralPath (Join-Path $runDir 'playwright.json') -Raw | ConvertFrom-Json
            $tests = @()
            foreach ($suite in $report.suites) {
                foreach ($spec in @($suite.specs) + @($suite.suites | ForEach-Object { $_.specs })) {
                    if (-not $spec) { continue }
                    $status = @($spec.tests | ForEach-Object { $_.results | Select-Object -Last 1 | ForEach-Object { $_.status } })
                    $tests += [ordered]@{ test = "$($suite.file)#$($spec.title)"; outcome = if (@($status | Where-Object { $_ -ne 'passed' }).Count -eq 0) { 'PASS' } else { 'FAIL' } }
                }
            }
            $summary.playwrightTests = $tests
            $summary.grader = Invoke-Grader $bankDb
        }
        finally {
            foreach ($process in @($bank, $sim)) { if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
            foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        }
    }
    'collect' {
        $cases = (Get-Content -LiteralPath $casesPath -Raw | ConvertFrom-Json).cases
        $tests = @{}
        $graders = @()
        foreach ($id in $Include) {
            $path = Join-Path $evidenceRoot "runs/$id.json"
            if (-not (Test-Path -LiteralPath $path)) { throw "No E12 run summary $path" }
            $run = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            foreach ($t in @($run.backendTests) + @($run.simulatorTests) + @($run.playwrightTests)) { if ($t) { $tests[$t.test] = $t.outcome } }
            if ($run.grader) { $graders += $run.grader }
        }
        $results = @()
        foreach ($case in $cases) {
            $evidence = @()
            foreach ($pattern in @($case.tests)) {
                if (-not $pattern) { continue }
                $matched = @($tests.Keys | Where-Object { $_ -like $pattern })
                if ($matched.Count -eq 0) { $evidence += [ordered]@{ source = $pattern; outcome = 'NOT_RUN' } }
                foreach ($name in $matched) { $evidence += [ordered]@{ source = $name; outcome = $tests[$name] } }
            }
            foreach ($lab in @($case.lab)) {
                if (-not $lab) { continue }
                $file = Join-Path $repo $lab.file
                $outcome = 'NOT_RUN'
                if (Test-Path -LiteralPath $file) {
                    $data = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
                    $value = $data
                    foreach ($segment in $lab.path.Split('.')) { $value = if ($segment -match '^\d+$') { @($value)[[int]$segment] } else { $value.$segment } }
                    $outcome = if ("$value" -eq "$($lab.expect)") { 'PASS' } else { "FAIL ($value)" }
                }
                $evidence += [ordered]@{ source = "$($lab.file)::$($lab.path)"; outcome = $outcome }
            }
            if ($case.grader) {
                foreach ($g in $graders) { $evidence += [ordered]@{ source = "grader:$($g.database)"; outcome = if ($g.pass) { 'PASS' } else { "FAIL ($($g.failedHardGates -join ','))" } } }
            }
            $verdict = if ($case.notRun) { 'NOT_RUN' }
                elseif ($evidence.Count -eq 0) { 'NOT_RUN' }
                elseif (@($evidence | Where-Object { $_.outcome -like 'FAIL*' -or $_.outcome -eq 'ERROR' }).Count -gt 0) { 'FAIL' }
                elseif (@($evidence | Where-Object { $_.outcome -eq 'NOT_RUN' -or $_.outcome -eq 'SKIPPED' }).Count -gt 0) { 'PARTIAL' }
                else { 'PASS' }
            $results += [ordered]@{ id = $case.id; category = $case.category; title = $case.title; hardGate = [bool]$case.hardGate
                verdict = $verdict; evidence = $evidence; limitation = $case.limitation }
        }
        $summary.included = $Include
        $summary.cases = $results
        $summary.counts = [ordered]@{
            total = $results.Count
            pass = @($results | Where-Object verdict -eq 'PASS').Count
            partial = @($results | Where-Object verdict -eq 'PARTIAL').Count
            fail = @($results | Where-Object verdict -eq 'FAIL').Count
            notRun = @($results | Where-Object verdict -eq 'NOT_RUN').Count
            hardGateFailures = @($results | Where-Object { $_.hardGate -and $_.verdict -ne 'PASS' }).Count
        }
        Write-Json (Join-Path $evidenceRoot 'E12-RESULTS.json') $summary
    }
}

$summary.completedAt = [DateTime]::UtcNow.ToString('o')
if ($Mode -ne 'collect') { Write-Json (Join-Path $evidenceRoot "runs/$RunId.json") $summary }
$summary | ConvertTo-Json -Depth 6 | Select-Object -First 200
