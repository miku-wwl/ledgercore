[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('inject', 'grade', 'restore')] [string] $Action,
    [Parameter(Mandatory)] [ValidateSet('precommit-crash', 'postcommit-response-crash', 'outbox-before-dispatch')] [string] $Case,
    [string] $RunId,
    [switch] $AfterRestore
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$workRoot = Join-Path $repo 'work/e6-lab'
$runsRoot = Join-Path $workRoot 'runs'
$pgBin = Join-Path $repo 'work/postgresql-binaries/pgsql/bin'
$java = Join-Path $repo 'work/toolchain/jdk21/jdk-21.0.13+9/bin/java.exe'
$jar = Join-Path $repo 'backend/target/ledgercore-0.0.1-SNAPSHOT.jar'
$psql = Join-Path $pgBin 'psql.exe'
$initdb = Join-Path $pgBin 'initdb.exe'
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
$createdb = Join-Path $pgBin 'createdb.exe'
$baseUrl = 'http://127.0.0.1:18095'
$postgresPort = 55437
$apiPort = 18095
$moneyMinor = 1000
$amount = '10.00'
$script:state = $null
$script:statePath = $null
$script:runDirectory = $null
$script:appProcess = $null
$script:pgData = $null

function Write-JsonFile([string] $Path, [object] $Value) {
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Save-State {
    Write-JsonFile $script:statePath $script:state
}

function Invoke-Native([string] $Executable, [string[]] $Arguments, [string] $Description) {
    $output = & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Description failed with exit code $LASTEXITCODE." }
    return ($output -join "`n").Trim()
}

function Get-RunPath([string] $Id) {
    if ($Id -notmatch '^[0-9a-fA-F-]{36}$') { throw 'RunId must be a GUID returned by inject.' }
    $path = [IO.Path]::GetFullPath((Join-Path $runsRoot $Id))
    $root = [IO.Path]::GetFullPath($runsRoot).TrimEnd('\') + '\'
    if (-not $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Run directory escaped the dedicated E6 work directory.'
    }
    return $path
}

function Assert-MainAndArtifacts {
    $branch = (& git -C $repo branch --show-current).Trim()
    if ($branch -ne 'main') { throw "E6 only runs from main; current branch is $branch." }
    foreach ($path in @($java, $jar, $psql, $initdb, $pgCtl, $createdb)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local artifact is missing: $path" }
    }
}

function Get-ListenerProcess([int] $Port) {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $connection) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)"
}

function Assert-ExactJavaListener([int] $Port, [int] $ExpectedPid) {
    $listener = Get-ListenerProcess $Port
    if (-not $listener -or $listener.ProcessId -ne $ExpectedPid -or $listener.Name -notin @('java.exe', 'javaw.exe') `
            -or $listener.CommandLine -notlike "*$jar*") {
        throw "Refusing to stop or trust port ${Port}: its listener is not the recorded LedgerCore test JAR process."
    }
    return $listener
}

function Assert-PostgresTarget([string] $Database, [string] $Role, [string] $Password) {
    if ($Database -notmatch '^ledgercore_e6_[a-z0-9_]+$' -or $Role -notmatch '^e6_[a-z0-9]+$') {
        throw 'E6 database or role name does not have the required disposable prefix.'
    }
    $sql = "SELECT current_database() || ':' || host(inet_server_addr()) || ':' || inet_server_port() || ':' || current_user"
    $env:PGPASSWORD = $Password
    try {
        $identity = Invoke-Native $psql @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort",
                '-U', $Role, '-d', $Database, '-v', 'ON_ERROR_STOP=1', '-c', $sql) 'PostgreSQL identity check'
    }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    if ($identity -ne "$Database`:127.0.0.1`:$postgresPort`:$Role") {
        throw "Refusing mutation: connected PostgreSQL identity was $identity."
    }
}

function Start-DisposablePostgres([string] $Directory) {
    $script:pgData = Join-Path $Directory 'postgres-data'
    $pgLog = Join-Path $Directory 'postgres.log'
    Write-Host "E6: checking dedicated PostgreSQL cluster for run $($script:state.runId)"
    $existingListener = Get-ListenerProcess $postgresPort
    if ($existingListener) {
        $pidFile = Join-Path $script:pgData 'postmaster.pid'
        if (-not (Test-Path -LiteralPath $pidFile)) { throw "Port $postgresPort is already occupied outside this E6 run." }
        $recordedPid = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
        $recorded = Get-CimInstance Win32_Process -Filter "ProcessId = $recordedPid"
        $normalizedPgData = $script:pgData.Replace('\', '/')
        if ($existingListener.ProcessId -ne $recordedPid -or $recorded.Name -ne 'postgres.exe' `
                -or $recorded.CommandLine -notlike "*$normalizedPgData*") {
            throw "Port $postgresPort is not owned by this exact E6 PostgreSQL cluster."
        }
        return
    }
    if (-not (Test-Path -LiteralPath $script:pgData)) {
        New-Item -ItemType Directory -Path $script:pgData -Force | Out-Null
        Invoke-Native $initdb @('-D', $script:pgData, '-U', 'postgres', '--auth-local=trust', '--auth-host=trust',
                '--no-locale', '--encoding=UTF8') 'Initialize disposable PostgreSQL cluster' | Out-Null
    }
    elseif (-not (Test-Path -LiteralPath (Join-Path $script:pgData 'PG_VERSION'))) {
        throw 'E6 PostgreSQL directory exists but is not a recognized cluster; it was left untouched.'
    }
    $pgCtlOut = Join-Path $Directory 'pgctl-start.stdout.log'
    $pgCtlErr = Join-Path $Directory 'pgctl-start.stderr.log'
    $pgCtlArguments = "-D `"$script:pgData`" -l `"$pgLog`" -o `"-h 127.0.0.1 -p $postgresPort`" -w start"
    $pgCtlProcess = Start-Process -FilePath $pgCtl -ArgumentList $pgCtlArguments -WorkingDirectory $repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $pgCtlOut -RedirectStandardError $pgCtlErr
    if (-not $pgCtlProcess.WaitForExit(30000)) { throw 'pg_ctl start did not exit after its server reached the startup checkpoint.' }
    if ($pgCtlProcess.ExitCode -ne 0) { throw "Start disposable PostgreSQL cluster failed with exit code $($pgCtlProcess.ExitCode)." }
    Write-Host 'E6: dedicated PostgreSQL cluster started; verifying its listener identity'
    $listener = Get-ListenerProcess $postgresPort
    $pidFile = Join-Path $script:pgData 'postmaster.pid'
    if (-not $listener -or -not (Test-Path -LiteralPath $pidFile) `
            -or $listener.ProcessId -ne [int](Get-Content -LiteralPath $pidFile -TotalCount 1)) {
        throw 'Started PostgreSQL listener does not match this run directory.'
    }
}

function Stop-DisposablePostgres([string] $Directory) {
    $data = Join-Path $Directory 'postgres-data'
    $pidFile = Join-Path $data 'postmaster.pid'
    $listener = Get-ListenerProcess $postgresPort
    if (-not $listener) { return }
    if (-not (Test-Path -LiteralPath $pidFile)) { throw "Refusing to stop port $postgresPort without this run's PostgreSQL PID file." }
    $expectedPid = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $expectedPid"
    $normalized = $data.Replace('\', '/')
    if ($listener.ProcessId -ne $expectedPid -or $process.Name -ne 'postgres.exe' `
            -or $process.CommandLine -notlike "*$normalized*") {
        throw 'Refusing to stop a PostgreSQL listener that does not belong to this exact E6 run.'
    }
    $stdout = Join-Path $Directory 'pgctl-stop.stdout.log'
    $stderr = Join-Path $Directory 'pgctl-stop.stderr.log'
    $arguments = "-D `"$data`" -m fast -w stop"
    $stopProcess = Start-Process -FilePath $pgCtl -ArgumentList $arguments -WorkingDirectory $repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $stopProcess.WaitForExit(30000) -or $stopProcess.ExitCode -ne 0) {
        throw 'pg_ctl could not stop the exact E6 PostgreSQL cluster within 30 seconds.'
    }
}

function New-DisposableDatabase([string] $Id, [string] $Directory) {
    Start-DisposablePostgres $Directory
    Write-Host 'E6: checking that the generated disposable database name is unused'
    $suffix = $Id.Replace('-', '').ToLowerInvariant()
    $database = "ledgercore_e6_$($suffix.Substring(0, 16))"
    $role = "e6_$($suffix.Substring(0, 20))"
    $password = "E6Local$($suffix)X9!"
    $dbExists = Invoke-Native $psql @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort",
            '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', "SELECT count(*) FROM pg_database WHERE datname='$database'") `
        'Disposable database absence check'
    if ($dbExists -ne '0') { throw 'The generated E6 database already exists; it was not reused or overwritten.' }
    Write-Host 'E6: creating a unique database role and run-scoped database'
    Invoke-Native $psql @('-X', '-q', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres', '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-c', "CREATE ROLE $role LOGIN PASSWORD '$password'") 'Create run-scoped database role' | Out-Null
    Invoke-Native $createdb @('-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres', '-O', $role, $database) `
        'Create disposable E6 database' | Out-Null
    $script:state.databaseName = $database
    $script:state.databaseRole = $role
    $script:state.databasePassword = $password
    Save-State
    Write-Host 'E6: verifying database name, database role, loopback address and port'
    Assert-PostgresTarget $database $role $password
}

function Set-ChildEnvironment([hashtable] $Values) {
    $saved = @{}
    foreach ($name in $Values.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$Values[$name], 'Process')
    }
    return $saved
}

function Restore-ChildEnvironment([hashtable] $Saved) {
    foreach ($name in $Saved.Keys) { [Environment]::SetEnvironmentVariable($name, $Saved[$name], 'Process') }
}

function Start-LedgerCore([bool] $FaultProfile, [string] $FaultName, [bool] $SchedulerEnabled) {
    if (Get-ListenerProcess $apiPort) { throw "Port $apiPort is already in use; no process was stopped." }
    $stdout = Join-Path $script:runDirectory "app-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')).stdout.log"
    $stderr = Join-Path $script:runDirectory "app-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')).stderr.log"
    $environment = @{
        LEDGERCORE_DB_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/$($script:state.databaseName)"
        LEDGERCORE_DB_USERNAME = $script:state.databaseRole
        LEDGERCORE_DB_PASSWORD = $script:state.databasePassword
        LEDGERCORE_COOKIE_SECURE = 'false'
        LEDGERCORE_E6_CONFIRM = if ($FaultProfile) { 'DISPOSABLE_ONLY' } else { '' }
        LEDGERCORE_E6_RUN_ID = if ($FaultProfile) { $script:state.runId } else { '' }
        LEDGERCORE_E6_RUN_DIR = if ($FaultProfile) { $script:runDirectory } else { '' }
        LEDGERCORE_E6_CASE = if ($FaultProfile) { $Case } else { '' }
        LEDGERCORE_E6_FAULT = if ($FaultProfile) { $FaultName } else { '' }
    }
    $savedEnvironment = Set-ChildEnvironment $environment
    try {
        Write-Host "E6: starting LedgerCore (fault profile=$FaultProfile, scheduler=$SchedulerEnabled)"
        $arguments = @('--server.address=127.0.0.1', "--server.port=$apiPort",
            "--ledgercore.outbox.scheduler.enabled=$($SchedulerEnabled.ToString().ToLowerInvariant())")
        if ($FaultProfile) { $arguments += @('--spring.profiles.active=e6-lab', '--ledgercore.e6.enabled=true') }
        $script:appProcess = Start-Process -FilePath $java -ArgumentList (@('-jar', $jar) + $arguments) `
            -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    }
    finally { Restore-ChildEnvironment $savedEnvironment }
    $script:state.appPid = $script:appProcess.Id
    $script:state.stdoutLog = $stdout
    $script:state.stderrLog = $stderr
    Save-State
    $healthy = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($script:appProcess.HasExited) { break }
        try {
            if ((Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') {
                $healthy = $true
                break
            }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) {
        $tail = if (Test-Path -LiteralPath $stderr) { (Get-Content -LiteralPath $stderr -Tail 60) -join "`n" } else { '' }
        throw "LedgerCore did not become healthy. $tail"
    }
    Assert-ExactJavaListener $apiPort $script:appProcess.Id | Out-Null
}

function Stop-LedgerCore([int] $ExpectedPid) {
    $listener = Get-ListenerProcess $apiPort
    if (-not $listener) { return }
    Assert-ExactJavaListener $apiPort $ExpectedPid | Out-Null
    Stop-Process -Id $ExpectedPid -Force
    try { Wait-Process -Id $ExpectedPid -Timeout 20 -ErrorAction SilentlyContinue } catch { }
}

function Get-Csrf([Microsoft.PowerShell.Commands.WebRequestSession] $Session) {
    return (Invoke-RestMethod -Uri "$baseUrl/api/auth/csrf" -WebSession $Session -TimeoutSec 10).token
}

function New-LabFixture {
    $suffix = $script:state.runId.Replace('-', '').ToLowerInvariant()
    $email = "e6-$suffix@example.test"
    $password = "LedgerCore-E6-$suffix`$X9!"
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Get-Csrf $session
    $registration = @{ email = $email; password = $password; displayName = 'E6 Disposable Customer' } | ConvertTo-Json -Compress
    $profile = Invoke-RestMethod -Uri "$baseUrl/api/auth/register" -Method Post -WebSession $session -ContentType 'application/json' `
        -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body $registration -TimeoutSec 15
    $csrf = Get-Csrf $session
    $source = Invoke-RestMethod -Uri "$baseUrl/api/accounts" -Method Post -WebSession $session -ContentType 'application/json' `
        -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ displayName = 'E6 Source' } | ConvertTo-Json -Compress) -TimeoutSec 15
    $csrf = Get-Csrf $session
    $target = Invoke-RestMethod -Uri "$baseUrl/api/accounts" -Method Post -WebSession $session -ContentType 'application/json' `
        -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ displayName = 'E6 Target' } | ConvertTo-Json -Compress) -TimeoutSec 15
    $cookies = @()
    foreach ($cookie in $session.Cookies.GetCookies([uri]$baseUrl)) { $cookies += "$($cookie.Name)=$($cookie.Value)" }
    if (-not $cookies -or -not $csrf) { throw 'E6 API fixture did not establish a session and CSRF token.' }
    $script:state.customerId = $profile.id
    $script:state.email = $email
    $script:state.customerPassword = $password
    $script:state.sourceAccountId = $source.id
    $script:state.targetAccountId = $target.id
    $script:state.idempotencyKey = "e6-transfer-$suffix"
    $script:state.amountMinor = $moneyMinor
    $script:state.initialBalanceMinor = 100000
    $script:state.expectedFault = switch ($Case) {
        'precommit-crash' { 'TRANSFER_PRE_COMMIT' }
        'outbox-before-dispatch' { 'OUTBOX_PERSISTED_BEFORE_DISPATCH' }
        default { 'TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE' }
    }
    $script:state.cookieHeader = $cookies -join '; '
    $script:state.csrfToken = $csrf
    Save-State
}

function Get-SqlSnapshot {
    Assert-PostgresTarget $script:state.databaseName $script:state.databaseRole $script:state.databasePassword
    $owner = $script:state.customerId
    $source = $script:state.sourceAccountId
    $target = $script:state.targetAccountId
    $key = $script:state.idempotencyKey
    $sql = @"
SELECT json_build_object(
  'database', current_database(),
  'server_address', inet_server_addr()::text,
  'server_port', inet_server_port(),
  'transfer_count', (SELECT count(*) FROM transfers WHERE source_account_id='$source' AND target_account_id='$target' AND amount_minor=$moneyMinor),
  'receipt_count', (SELECT count(*) FROM idempotency_records WHERE owner_customer_id='$owner' AND operation='TRANSFER' AND idempotency_key='$key'),
  'successful_receipt_count', (SELECT count(*) FROM idempotency_records WHERE owner_customer_id='$owner' AND operation='TRANSFER' AND idempotency_key='$key' AND status='SUCCEEDED'),
  'outbox_count', (SELECT count(*) FROM outbox_events e JOIN transfers t ON e.aggregate_id=t.id WHERE e.event_type='transfer.posted' AND t.source_account_id='$source' AND t.target_account_id='$target'),
  'outbox_states', COALESCE((SELECT string_agg(e.status || ':' || e.attempt_count, ',' ORDER BY e.created_at, e.id) FROM outbox_events e JOIN transfers t ON e.aggregate_id=t.id WHERE e.event_type='transfer.posted' AND t.source_account_id='$source' AND t.target_account_id='$target'), ''),
  'unbalanced_entry_count', (SELECT count(*) FROM (SELECT j.id FROM journal_entries j LEFT JOIN postings p ON p.journal_entry_id=j.id GROUP BY j.id HAVING count(p.id)<2 OR coalesce(sum(p.amount_minor) FILTER (WHERE p.side='DEBIT'),0)<>coalesce(sum(p.amount_minor) FILTER (WHERE p.side='CREDIT'),0)) x),
  'negative_projection_count', (SELECT count(*) FROM account_balance_projection WHERE balance_minor<0),
  'projection_mismatch_count', (SELECT count(*) FROM ledger_accounts la JOIN account_balance_projection p ON p.ledger_account_id=la.id WHERE la.kind='CUSTOMER_DEPOSIT' AND p.balance_minor<>(SELECT coalesce(sum(CASE WHEN posting.side=la.normal_side THEN posting.amount_minor ELSE -posting.amount_minor END),0) FROM postings posting WHERE posting.ledger_account_id=la.id)),
  'source_balance_minor', (SELECT p.balance_minor FROM account_balance_projection p JOIN ledger_accounts la ON la.id=p.ledger_account_id WHERE la.customer_account_id='$source' AND la.kind='CUSTOMER_DEPOSIT'),
  'target_balance_minor', (SELECT p.balance_minor FROM account_balance_projection p JOIN ledger_accounts la ON la.id=p.ledger_account_id WHERE la.customer_account_id='$target' AND la.kind='CUSTOMER_DEPOSIT')
)::text;
"@
    $env:PGPASSWORD = $script:state.databasePassword
    try {
        $json = Invoke-Native $psql @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort",
                '-U', $script:state.databaseRole, '-d', $script:state.databaseName, '-v', 'ON_ERROR_STOP=1', '-c', $sql) `
            'Independent PostgreSQL grade query'
    }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    return $json | ConvertFrom-Json
}

function Test-Invariants([object] $Snapshot, [bool] $ExpectCommitted, [bool] $ExpectDelivered) {
    $outboxExpectedDispatch = $true
    if ($ExpectCommitted) {
        if ($ExpectDelivered) { $outboxExpectedDispatch = $Snapshot.outbox_states -eq 'DELIVERED:1' }
        else { $outboxExpectedDispatch = $Snapshot.outbox_states -eq 'PENDING:0' }
    }
    $checks = [ordered]@{
        expected_transfer_count = ([int]$Snapshot.transfer_count -eq $(if ($ExpectCommitted) { 1 } else { 0 }))
        expected_receipt_count = ([int]$Snapshot.receipt_count -eq $(if ($ExpectCommitted) { 1 } else { 0 }))
        successful_receipt = (-not $ExpectCommitted -or [int]$Snapshot.successful_receipt_count -eq 1)
        no_unbalanced_entries = ([int]$Snapshot.unbalanced_entry_count -eq 0)
        no_negative_projection = ([int]$Snapshot.negative_projection_count -eq 0)
        projections_match_ledger = ([int]$Snapshot.projection_mismatch_count -eq 0)
        outbox_durable = (-not $ExpectCommitted -or [int]$Snapshot.outbox_count -eq 1)
        outbox_expected_dispatch = $outboxExpectedDispatch
        source_balance_expected = ([int]$Snapshot.source_balance_minor -eq $(if ($ExpectCommitted) { 99000 } else { 100000 }))
        target_balance_expected = ([int]$Snapshot.target_balance_minor -eq $(if ($ExpectCommitted) { 101000 } else { 100000 }))
    }
    return [pscustomobject]$checks
}

function Invoke-TransferAsync {
    $base = $baseUrl
    $cookies = $script:state.cookieHeader
    $csrf = $script:state.csrfToken
    $key = $script:state.idempotencyKey
    $source = $script:state.sourceAccountId
    $target = $script:state.targetAccountId
    $body = @{ sourceAccountId = $source; targetAccountId = $target; amount = $amount } | ConvertTo-Json -Compress
    return Start-Job -ArgumentList $base, $cookies, $csrf, $key, $body -ScriptBlock {
        param($url, $cookie, $token, $idempotencyKey, $requestBody)
        try {
            $response = Invoke-WebRequest -Uri "$url/api/transfers" -Method Post -Headers @{
                Cookie = $cookie; 'X-CSRF-TOKEN' = $token; 'Idempotency-Key' = $idempotencyKey
            } -ContentType 'application/json' -Body $requestBody -TimeoutSec 600
            [pscustomobject]@{ status = [int]$response.StatusCode; body = $response.Content }
        }
        catch {
            [pscustomobject]@{ status = 0; error = $_.Exception.GetType().Name }
        }
    }
}

function Start-RecoveryLogin {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Get-Csrf $session
    $login = @{ email = $script:state.email; password = $script:state.customerPassword } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -WebSession $session -ContentType 'application/json' `
        -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body $login -TimeoutSec 15 | Out-Null
    return @{ session = $session; csrf = (Get-Csrf $session) }
}

function Read-State([string] $Id) {
    $script:runDirectory = Get-RunPath $Id
    $script:statePath = Join-Path $script:runDirectory 'run.json'
    if (-not (Test-Path -LiteralPath $script:statePath)) { throw "E6 run state not found: $Id" }
    $script:state = Get-Content -LiteralPath $script:statePath -Raw | ConvertFrom-Json
    if ($script:state.caseName -ne $Case -or $script:state.runId -ne $Id) { throw 'E6 run identity does not match case and requested RunId.' }
    $script:pgData = Join-Path $script:runDirectory 'postgres-data'
}

Assert-MainAndArtifacts

switch ($Action) {
    'inject' {
        if (-not $RunId) { $RunId = [guid]::NewGuid().ToString() }
        $script:runDirectory = Get-RunPath $RunId
        if (Test-Path -LiteralPath $script:runDirectory) { throw 'E6 run directory already exists; inject never reuses a prior run.' }
        New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
        $script:statePath = Join-Path $script:runDirectory 'run.json'
        $expectedFault = switch ($Case) {
            'precommit-crash' { 'TRANSFER_PRE_COMMIT' }
            'outbox-before-dispatch' { 'OUTBOX_PERSISTED_BEFORE_DISPATCH' }
            default { 'TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE' }
        }
        $script:state = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; createdAt = [DateTime]::UtcNow.ToString('o'); status = 'INITIALIZING'; injectionObserved = $false; expectedFault = $expectedFault }
        Save-State
        Write-Host "E6: starting $Case run $RunId in its dedicated ignored work directory"
        try {
            New-DisposableDatabase $RunId $script:runDirectory
            $scheduler = $false
            Start-LedgerCore $true $script:state.expectedFault $scheduler
            New-LabFixture
            $job = Invoke-TransferAsync
            $checkpointPath = Join-Path $script:runDirectory 'checkpoint.json'
            $deadline = [DateTime]::UtcNow.AddSeconds(60)
            while (-not (Test-Path -LiteralPath $checkpointPath) -and [DateTime]::UtcNow -lt $deadline -and -not $script:appProcess.HasExited) {
                Start-Sleep -Milliseconds 250
            }
            if (Test-Path -LiteralPath $checkpointPath) {
                $checkpoint = Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json
                if ($checkpoint.runId -ne $RunId -or $checkpoint.caseName -ne $Case -or $checkpoint.faultPoint -ne $script:state.expectedFault `
                        -or [int]$checkpoint.processId -ne $script:appProcess.Id) {
                    throw 'E6 checkpoint identity does not match the intended case and process.'
                }
                if ($Case -eq 'outbox-before-dispatch') {
                    $finishedJob = Wait-Job -Job $job -Timeout 20
                    if (-not $finishedJob) { throw 'The transfer response did not return after the durable outbox checkpoint.' }
                    $response = Receive-Job -Job $job | Select-Object -First 1
                    if ([int]$response.status -ne 200) { throw 'The transfer did not return HTTP 200 before the outbox recovery crash.' }
                    $responseReceipt = $response.body | ConvertFrom-Json
                    if ([string]$responseReceipt.transferId -ne [string]$checkpoint.facts.transferId) {
                        throw 'The returned transfer id does not match the durable outbox checkpoint.'
                    }
                    $script:state.responseReceived = $true
                    $script:state.responseTransferId = $responseReceipt.transferId
                }
                Assert-ExactJavaListener $apiPort $script:appProcess.Id | Out-Null
                Stop-LedgerCore $script:appProcess.Id
                $script:state.injectionObserved = $true
                $script:state.checkpoint = $checkpoint
                $script:state.status = 'INJECTED_PROCESS_STOPPED'
            }
            else {
                if (-not $script:appProcess.HasExited) { Stop-LedgerCore $script:appProcess.Id }
                $script:state.status = 'NOT_INJECTED'
                $script:state.failure = 'Expected fault checkpoint was not reached before timeout or application exit.'
            }
            $script:state.updatedAt = [DateTime]::UtcNow.ToString('o')
            Save-State
            try { Stop-Job -Job $job -ErrorAction SilentlyContinue | Out-Null; Remove-Job -Job $job -Force -ErrorAction SilentlyContinue | Out-Null } catch { }
            $snapshot = Get-SqlSnapshot
            $expectCommitted = $Case -ne 'precommit-crash'
            $checks = Test-Invariants $snapshot $expectCommitted $false
            $pass = [bool]$script:state.injectionObserved -and (($checks.PSObject.Properties | ForEach-Object { $_.Value }) -notcontains $false)
            $verdict = [pscustomobject]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; phase = 'after_injection'; verdict = if ($pass) { 'PASS' } elseif (-not $script:state.injectionObserved) { 'NOT_INJECTED' } else { 'FAIL' }; injectionObserved = [bool]$script:state.injectionObserved; faultPoint = $script:state.expectedFault; gradedAt = [DateTime]::UtcNow.ToString('o'); independentPostgresState = $snapshot; checks = $checks }
            Write-JsonFile (Join-Path $script:runDirectory 'grade-after-injection.json') $verdict
            $script:state.initialVerdict = $verdict.verdict
            Save-State
            $verdict | ConvertTo-Json -Depth 12
        }
        catch {
            $script:state.status = 'INJECT_FAILED'
            $script:state.failure = $_.Exception.Message
            Save-State
            try { Stop-DisposablePostgres $script:runDirectory } catch { $script:state.cleanupFailure = $_.Exception.Message; Save-State }
            throw
        }
    }
    'grade' {
        if (-not $RunId) { throw 'Pass -RunId from the inject output.' }
        Read-State $RunId
        Start-DisposablePostgres $script:runDirectory
        $snapshot = Get-SqlSnapshot
        $expectedCommitted = if ($AfterRestore) { $true } else { $Case -ne 'precommit-crash' }
        $expectedDelivered = [bool]$AfterRestore
        $checks = Test-Invariants $snapshot $expectedCommitted $expectedDelivered
        $pass = [bool]$script:state.injectionObserved -and (($checks.PSObject.Properties | ForEach-Object { $_.Value }) -notcontains $false)
        $verdict = [pscustomobject]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; phase = if ($AfterRestore) { 'after_restore' } else { 'after_injection' }; verdict = if ($pass) { 'PASS' } elseif (-not $script:state.injectionObserved) { 'NOT_INJECTED' } else { 'FAIL' }; injectionObserved = [bool]$script:state.injectionObserved; gradedAt = [DateTime]::UtcNow.ToString('o'); independentPostgresState = $snapshot; checks = $checks }
        $file = if ($AfterRestore) { 'grade-after-restore.json' } else { 'grade-after-injection.json' }
        Write-JsonFile (Join-Path $script:runDirectory $file) $verdict
        $verdict | ConvertTo-Json -Depth 12
    }
    'restore' {
        if (-not $RunId) { throw 'Pass -RunId from the inject output.' }
        Read-State $RunId
        $recovery = [ordered]@{ runId = $RunId; caseName = $Case; startedAt = [DateTime]::UtcNow.ToString('o'); recoveryAttempted = $false }
        try {
            Start-DisposablePostgres $script:runDirectory
            Assert-PostgresTarget $script:state.databaseName $script:state.databaseRole $script:state.databasePassword
            if (-not $script:state.injectionObserved) {
                $recovery.verdict = 'NOT_INJECTED'
                $recovery.detail = 'No intended fault checkpoint was observed; no successful recovery claim is made.'
            }
            else {
                Start-LedgerCore $false '' $true
                $login = Start-RecoveryLogin
                $body = @{ sourceAccountId = $script:state.sourceAccountId; targetAccountId = $script:state.targetAccountId; amount = $amount } | ConvertTo-Json -Compress
                $receipt = Invoke-RestMethod -Uri "$baseUrl/api/transfers" -Method Post -WebSession $login.session -ContentType 'application/json' `
                    -Headers @{ 'X-CSRF-TOKEN' = $login.csrf; 'Idempotency-Key' = $script:state.idempotencyKey } -Body $body -TimeoutSec 20
                $recovery.recoveryAttempted = $true
                $recovery.replayedTransferId = $receipt.transferId
                $recovery.replayStatus = $receipt.status
                $recovery.replayAmount = $receipt.amount
                $checkpointTransferId = [string]$script:state.checkpoint.facts.transferId
                $sameCommittedTransfer = ([string]$receipt.transferId -eq $checkpointTransferId)
                $recovery.checkpointTransferId = $checkpointTransferId
                $recovery.sameTransferIdempotentReplay = $sameCommittedTransfer
                $deadline = [DateTime]::UtcNow.AddSeconds(45)
                do {
                    $snapshot = Get-SqlSnapshot
                    if ($snapshot.outbox_states -eq 'DELIVERED:1') { break }
                    Start-Sleep -Milliseconds 500
                } while ([DateTime]::UtcNow -lt $deadline)
                $checks = Test-Invariants $snapshot $true $true
                $identityExpected = if ($Case -eq 'precommit-crash') { -not $sameCommittedTransfer } else { $sameCommittedTransfer }
                $recovery.transferIdentityRecoveryCheck = $identityExpected
                $pass = $receipt.status -eq 'POSTED' -and $receipt.amount -eq '10.00' -and $identityExpected `
                    -and (($checks.PSObject.Properties | ForEach-Object { $_.Value }) -notcontains $false)
                $recovery.verdict = if ($pass) { 'PASS' } else { 'FAIL' }
                $recovery.finalIndependentPostgresState = $snapshot
                $recovery.checks = $checks
            }
        }
        catch {
            $recovery.verdict = 'FAIL'
            $recovery.failure = $_.Exception.Message
        }
        finally {
            if ($script:appProcess) {
                try { Stop-LedgerCore $script:appProcess.Id } catch { $recovery.stopApplicationError = $_.Exception.Message }
            }
            try { Stop-DisposablePostgres $script:runDirectory } catch { $recovery.stopPostgresError = $_.Exception.Message }
        }
        $recovery.completedAt = [DateTime]::UtcNow.ToString('o')
            Write-JsonFile (Join-Path $script:runDirectory 'restore.json') ([pscustomobject]$recovery)
        $script:state.status = if ($recovery.verdict -eq 'PASS') { 'RESTORED' } else { $recovery.verdict }
        $script:state.updatedAt = [DateTime]::UtcNow.ToString('o')
        Save-State
        $recovery | ConvertTo-Json -Depth 12
    }
}
