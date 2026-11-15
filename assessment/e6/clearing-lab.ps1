[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('inject', 'grade', 'restore')] [string] $Action,
    [Parameter(Mandatory)] [ValidateSet('clearing-callback-commit-crash', 'clearing-effect-precommit-crash',
        'clearing-effect-ack-crash', 'clearing-effect-session-kill')] [string] $Case,
    [string] $RunId,
    [ValidateSet('prompt', 'after-dead-letter')] [string] $RestoreMode = 'prompt',
    [string] $Label = ''
)

# Round 4 two-process clearing fault lab. It extends the E6 conventions (assessment/e6/README.md): one GUID run,
# one dedicated PostgreSQL 18 cluster on 127.0.0.1:55437 holding a bank database and a separate simulator database,
# exact-PID process control, JSON verdicts derived only from independent SQL, and NOT_INJECTED when the intended
# checkpoint was never observed. Secrets (database passwords, the run's HMAC key) stay in the ignored run.json.

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runsRoot = Join-Path $repo 'work/e6-lab/runs'
$pgBin = Join-Path $repo 'work/postgresql-binaries/pgsql/bin'
$java = Join-Path $repo 'work/toolchain/jdk21/jdk-21.0.13+9/bin/java.exe'
$bankJar = Join-Path $repo 'backend/target/ledgercore-0.0.1-SNAPSHOT.jar'
$simJar = Join-Path $repo 'clearing-simulator/target/clearing-simulator-0.0.1-SNAPSHOT.jar'
$psql = Join-Path $pgBin 'psql.exe'
$initdb = Join-Path $pgBin 'initdb.exe'
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
$createdb = Join-Path $pgBin 'createdb.exe'
$postgresPort = 55437
$bankPort = 18095
$simPort = 18094
$bankUrl = "http://127.0.0.1:$bankPort"
$simUrl = "http://127.0.0.1:$simPort"
$amount = '12.34'
$amountMinor = 1234
$openingMinor = 100000
$reserveLedger = '00000000-0000-5000-8000-000000000001'
$payableLedger = '00000000-0000-5000-8000-000000000002'
$faultByCase = @{
    'clearing-callback-commit-crash' = 'CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT'
    'clearing-effect-precommit-crash' = 'CLEARING_EFFECT_POSTED_BEFORE_COMMIT'
    'clearing-effect-ack-crash' = 'CLEARING_EFFECT_COMMITTED_BEFORE_ACK'
    'clearing-effect-session-kill' = 'DATABASE_SESSION_TERMINATED_DURING_EFFECT'
}
$script:state = $null
$script:statePath = $null
$script:runDirectory = $null
$script:bankProcess = $null
$script:simProcess = $null

function Write-JsonFile([string] $Path, [object] $Value) {
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $Value | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Save-State { Write-JsonFile $script:statePath $script:state }

function Write-Step([string] $Message) {
    $line = "$([DateTime]::UtcNow.ToString('o')) $Message"
    Write-Host "E6-clearing: $Message"
    if ($script:runDirectory) { Add-Content -LiteralPath (Join-Path $script:runDirectory 'timeline.log') -Value $line -Encoding utf8 }
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
    if (-not $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Run directory escaped the E6 work directory.' }
    return $path
}

function Get-FileSha256([string] $Path) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }

function Assert-MainAndArtifacts {
    $branch = (& git -C $repo branch --show-current).Trim()
    if ($branch -ne 'main') { throw "The clearing lab only runs from main; current branch is $branch." }
    foreach ($path in @($java, $bankJar, $simJar, $psql, $initdb, $pgCtl, $createdb)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local artifact is missing: $path" }
    }
}

function Get-ListenerProcess([int] $Port) {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)"
}

function Assert-ExactJavaListener([int] $Port, [int] $ExpectedPid, [string] $Jar) {
    $listener = Get-ListenerProcess $Port
    if (-not $listener -or $listener.ProcessId -ne $ExpectedPid -or $listener.Name -notin @('java.exe', 'javaw.exe') `
            -or $listener.CommandLine -notlike "*$Jar*") {
        throw "Refusing to trust or stop port ${Port}: its listener is not the recorded lab JAR process."
    }
}

function Start-DisposablePostgres {
    $pgData = Join-Path $script:runDirectory 'postgres-data'
    $pgLog = Join-Path $script:runDirectory 'postgres.log'
    $existing = Get-ListenerProcess $postgresPort
    if ($existing) {
        $pidFile = Join-Path $pgData 'postmaster.pid'
        if (-not (Test-Path -LiteralPath $pidFile)) { throw "Port $postgresPort is already occupied outside this run." }
        $recordedPid = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
        if ($existing.ProcessId -ne $recordedPid -or $existing.Name -ne 'postgres.exe' `
                -or $existing.CommandLine -notlike "*$($pgData.Replace('\', '/'))*") {
            throw "Port $postgresPort is not owned by this exact run's PostgreSQL cluster."
        }
        return
    }
    if (-not (Test-Path -LiteralPath $pgData)) {
        New-Item -ItemType Directory -Path $pgData -Force | Out-Null
        Invoke-Native $initdb @('-D', $pgData, '-U', 'postgres', '--auth-local=trust', '--auth-host=trust', '--no-locale',
                '--encoding=UTF8') 'Initialize the disposable PostgreSQL cluster' | Out-Null
    }
    elseif (-not (Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION'))) { throw 'The run PostgreSQL directory is not a cluster.' }
    $arguments = "-D `"$pgData`" -l `"$pgLog`" -o `"-h 127.0.0.1 -p $postgresPort`" -w start"
    $process = Start-Process -FilePath $pgCtl -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:runDirectory 'pgctl-start.stdout.log') `
        -RedirectStandardError (Join-Path $script:runDirectory 'pgctl-start.stderr.log')
    if (-not $process.WaitForExit(30000) -or $process.ExitCode -ne 0) { throw 'pg_ctl could not start the run cluster.' }
    $listener = Get-ListenerProcess $postgresPort
    if (-not $listener -or $listener.ProcessId -ne [int](Get-Content -LiteralPath (Join-Path $pgData 'postmaster.pid') -TotalCount 1)) {
        throw 'The started PostgreSQL listener does not match this run directory.'
    }
}

function Stop-DisposablePostgres {
    $pgData = Join-Path $script:runDirectory 'postgres-data'
    $pidFile = Join-Path $pgData 'postmaster.pid'
    $listener = Get-ListenerProcess $postgresPort
    if (-not $listener) { return }
    if (-not (Test-Path -LiteralPath $pidFile)) { throw "Refusing to stop port $postgresPort without this run's PID file." }
    $expected = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $expected"
    if ($listener.ProcessId -ne $expected -or $process.Name -ne 'postgres.exe' -or $process.CommandLine -notlike "*$($pgData.Replace('\', '/'))*") {
        throw 'Refusing to stop a PostgreSQL listener that does not belong to this run.'
    }
    $stop = Start-Process -FilePath $pgCtl -ArgumentList "-D `"$pgData`" -m fast -w stop" -WorkingDirectory $repo -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput (Join-Path $script:runDirectory 'pgctl-stop.stdout.log') `
        -RedirectStandardError (Join-Path $script:runDirectory 'pgctl-stop.stderr.log')
    if (-not $stop.WaitForExit(30000) -or $stop.ExitCode -ne 0) { throw 'pg_ctl could not stop the run cluster within 30 seconds.' }
}

function Invoke-Sql([string] $Database, [string] $Role, [string] $Password, [string] $Sql, [string] $Description) {
    $env:PGPASSWORD = $Password
    try {
        return Invoke-Native $psql @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', $Role, '-d', $Database,
                '-v', 'ON_ERROR_STOP=1', '-c', $Sql) $Description
    }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Assert-PostgresTarget([string] $Database, [string] $Role, [string] $Password, [string] $Prefix) {
    if ($Database -notmatch "^$Prefix[a-z0-9_]+$") { throw "Database $Database does not have the disposable prefix $Prefix." }
    $identity = Invoke-Sql $Database $Role $Password "SELECT current_database() || ':' || host(inet_server_addr()) || ':' || inet_server_port() || ':' || current_user" 'PostgreSQL identity check'
    if ($identity -ne "$Database`:127.0.0.1`:$postgresPort`:$Role") { throw "Refusing mutation: connected PostgreSQL identity was $identity." }
}

function New-RunDatabases {
    Start-DisposablePostgres
    $suffix = $script:state.runId.Replace('-', '').ToLowerInvariant()
    $bank = [ordered]@{ database = "ledgercore_e6_$($suffix.Substring(0, 16))"; role = "e6_$($suffix.Substring(0, 20))"; password = "E6Bank$($suffix)X9!" }
    $sim = [ordered]@{ database = "ledgercore_e6sim_$($suffix.Substring(0, 16))"; role = "e6s_$($suffix.Substring(0, 20))"; password = "E6Sim$($suffix)Q7!" }
    foreach ($target in @($bank, $sim)) {
        $exists = Invoke-Native $psql @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres', '-d', 'postgres',
                '-v', 'ON_ERROR_STOP=1', '-c', "SELECT count(*) FROM pg_database WHERE datname='$($target.database)'") 'Database absence check'
        if ($exists -ne '0') { throw "Database $($target.database) already exists; it was not reused." }
        Invoke-Native $psql @('-X', '-q', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1',
                '-c', "CREATE ROLE $($target.role) LOGIN PASSWORD '$($target.password)'") 'Create run-scoped role' | Out-Null
        Invoke-Native $createdb @('-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres', '-O', $target.role, $target.database) 'Create run database' | Out-Null
    }
    $script:state.bank = $bank
    $script:state.sim = $sim
    $bytes = New-Object byte[] 36
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $script:state.hmacSecret = [Convert]::ToBase64String($bytes)
    Save-State
    Assert-PostgresTarget $bank.database $bank.role $bank.password 'ledgercore_e6_'
    Assert-PostgresTarget $sim.database $sim.role $sim.password 'ledgercore_e6sim_'
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

function Start-Simulator {
    if (Get-ListenerProcess $simPort) { throw "Port $simPort is already in use; no process was stopped." }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $environment = @{
        CLEARING_SIMULATOR_DB_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/$($script:state.sim.database)"
        CLEARING_SIMULATOR_DB_USERNAME = $script:state.sim.role
        CLEARING_SIMULATOR_DB_PASSWORD = $script:state.sim.password
        CLEARING_SIMULATOR_PORT = "$simPort"
        CLEARING_SIMULATOR_CALLBACK_DISPATCH_ENABLED = 'true'
        LEDGERCORE_CLEARING_HMAC_SECRET = $script:state.hmacSecret
        LEDGERCORE_BANK_CALLBACK_URL = "$bankUrl/api/clearing/callbacks"
    }
    $saved = Set-ChildEnvironment $environment
    try {
        $script:simProcess = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar, '--server.address=127.0.0.1') -WorkingDirectory $repo `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:runDirectory "sim-$stamp.stdout.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "sim-$stamp.stderr.log")
    }
    finally { Restore-ChildEnvironment $saved }
    $script:state.simPid = $script:simProcess.Id
    Save-State
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($script:simProcess.HasExited) { throw 'The clearing simulator exited during startup.' }
        try { Invoke-WebRequest -Uri "$simUrl/" -TimeoutSec 2 -UseBasicParsing | Out-Null }
        catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { Assert-ExactJavaListener $simPort $script:simProcess.Id $simJar; return }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'The clearing simulator did not become ready.'
}

function Start-Bank([bool] $FaultProfile) {
    if (Get-ListenerProcess $bankPort) { throw "Port $bankPort is already in use; no process was stopped." }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $environment = @{
        LEDGERCORE_DB_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/$($script:state.bank.database)"
        LEDGERCORE_DB_USERNAME = $script:state.bank.role
        LEDGERCORE_DB_PASSWORD = $script:state.bank.password
        LEDGERCORE_COOKIE_SECURE = 'false'
        LEDGERCORE_CLEARING_ENABLED = 'true'
        LEDGERCORE_CLEARING_HMAC_SECRET = $script:state.hmacSecret
        LEDGERCORE_CLEARING_SIMULATOR_URL = $simUrl
        LEDGERCORE_E6_CONFIRM = if ($FaultProfile) { 'DISPOSABLE_ONLY' } else { '' }
        LEDGERCORE_E6_RUN_ID = if ($FaultProfile) { $script:state.runId } else { '' }
        LEDGERCORE_E6_RUN_DIR = if ($FaultProfile) { $script:runDirectory } else { '' }
        LEDGERCORE_E6_CASE = if ($FaultProfile) { $Case } else { '' }
        LEDGERCORE_E6_FAULT = if ($FaultProfile) { $script:state.expectedFault } else { '' }
    }
    $arguments = @('-jar', $bankJar, '--server.address=127.0.0.1', "--server.port=$bankPort", '--ledgercore.outbox.scheduler.enabled=true')
    if ($FaultProfile) { $arguments += @('--spring.profiles.active=e6-lab', '--ledgercore.e6.enabled=true') }
    $saved = Set-ChildEnvironment $environment
    try {
        $script:bankProcess = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:runDirectory "bank-$stamp.stdout.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "bank-$stamp.stderr.log")
    }
    finally { Restore-ChildEnvironment $saved }
    $script:state.bankPid = $script:bankProcess.Id
    $script:state.bankStarts = @($script:state.bankStarts) + @([ordered]@{ pid = $script:bankProcess.Id; faultProfile = $FaultProfile; at = [DateTime]::UtcNow.ToString('o') })
    Save-State
    for ($attempt = 0; $attempt -lt 160; $attempt++) {
        if ($script:bankProcess.HasExited) { throw 'LedgerCore exited during startup.' }
        try {
            if ((Invoke-RestMethod -Uri "$bankUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') {
                Assert-ExactJavaListener $bankPort $script:bankProcess.Id $bankJar
                return
            }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    throw 'LedgerCore did not become healthy.'
}

function Stop-JavaProcess([int] $Port, [int] $ExpectedPid, [string] $Jar) {
    if (-not (Get-ListenerProcess $Port)) {
        if (Get-Process -Id $ExpectedPid -ErrorAction SilentlyContinue) { throw "Process $ExpectedPid is alive without its listener; not stopped." }
        return
    }
    Assert-ExactJavaListener $Port $ExpectedPid $Jar
    Stop-Process -Id $ExpectedPid -Force
    try { Wait-Process -Id $ExpectedPid -Timeout 20 -ErrorAction SilentlyContinue } catch { }
}

function New-Session { return [Microsoft.PowerShell.Commands.WebRequestSession]::new() }

function Invoke-Api([Microsoft.PowerShell.Commands.WebRequestSession] $Session, [string] $Method, [string] $Path, [object] $Body) {
    $csrf = (Invoke-RestMethod -Uri "$bankUrl/api/auth/csrf" -WebSession $Session -TimeoutSec 10).token
    $parameters = @{ Uri = "$bankUrl$Path"; Method = $Method; WebSession = $Session; Headers = @{ 'X-CSRF-TOKEN' = $csrf }; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.ContentType = 'application/json'; $parameters.Body = ($Body | ConvertTo-Json -Compress -Depth 6) }
    return Invoke-RestMethod @parameters
}

function Register-User([string] $Role) {
    $suffix = $script:state.runId.Replace('-', '').Substring(0, 12).ToLowerInvariant()
    $session = New-Session
    $email = "e6c-$Role-$suffix@example.test".ToLowerInvariant()
    $password = "Ledger-E6C-$suffix-$Role!"
    $profile = Invoke-Api $session 'POST' '/api/auth/register' @{ email = $email; password = $password; displayName = "E6 clearing $Role" }
    return [ordered]@{ role = $Role; email = $email; password = $password; customerId = $profile.id; session = $session }
}

function Login-User([object] $User) {
    $session = New-Session
    Invoke-Api $session 'POST' '/api/auth/login' @{ email = $User.email; password = $User.password } | Out-Null
    return $session
}

function New-ClearingFixture {
    $admin = Register-User 'admin'
    $organization = Invoke-Api $admin.session 'POST' '/api/organizations' @{ displayName = "E6 Clearing Lab $($script:state.runId.Substring(0, 8))" }
    $account = Invoke-Api $admin.session 'POST' "/api/organizations/$($organization.id)/accounts" @{ displayName = 'E6 Clearing Operating' }
    $users = [ordered]@{ admin = $admin }
    foreach ($entry in @(@('maker', 'PAYMENT_MAKER'), @('approver', 'PAYMENT_APPROVER'), @('operations', 'OPERATIONS'))) {
        $user = Register-User $entry[0]
        Invoke-Api $admin.session 'POST' "/api/organizations/$($organization.id)/members" @{ email = $user.email; roles = @($entry[1]) } | Out-Null
        $users[$entry[0]] = $user
    }
    $payment = Invoke-Api $users.maker.session 'POST' "/api/organizations/$($organization.id)/payments" @{
        sourceAccountId = $account.id; beneficiaryName = 'E6 Synthetic Supplier'
        beneficiaryAccountReference = "NZ-E6C-$($script:state.runId.Substring(0, 8))"; beneficiaryRoutingCode = 'SIM-SETTLE'; amount = $amount }
    Invoke-Api $users.maker.session 'POST' "/api/payments/$($payment.id)/submit" $null | Out-Null
    $script:state.organizationId = $organization.id
    $script:state.accountId = $account.id
    $script:state.paymentId = $payment.id
    $script:state.operationId = $payment.operationId
    $script:state.users = [ordered]@{}
    foreach ($name in $users.Keys) {
        $script:state.users[$name] = [ordered]@{ email = $users[$name].email; password = $users[$name].password; customerId = $users[$name].customerId }
    }
    Save-State
    return $users
}

function Get-BankSnapshot {
    Assert-PostgresTarget $script:state.bank.database $script:state.bank.role $script:state.bank.password 'ledgercore_e6_'
    $payment = $script:state.paymentId
    $sql = @"
WITH ledger AS (
  SELECT la.id, la.kind, la.normal_side,
         COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor,
         proj.balance_minor AS projection_minor
  FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
  LEFT JOIN account_balance_projection proj ON proj.ledger_account_id = la.id
  GROUP BY la.id, la.kind, la.normal_side, proj.balance_minor)
SELECT json_build_object(
  'database', current_database(), 'serverPort', inet_server_port(), 'capturedAt', clock_timestamp(),
  'paymentStatus', p.status, 'externalStatus', p.external_status,
  'bookingJournalCount', (SELECT count(*) FROM journal_entries WHERE reference = 'corporate-payment:' || p.id || ':booking'),
  'settlementJournalCount', (SELECT count(*) FROM journal_entries WHERE reference = 'corporate-payment:' || p.id || ':settlement'),
  'refundJournalCount', (SELECT count(*) FROM journal_entries WHERE reference = 'corporate-payment:' || p.id || ':refund'),
  'settlementLinked', p.settlement_journal_entry_id IS NOT NULL,
  'instructionStatus', (SELECT status FROM clearing_instructions WHERE operation_id = p.operation_id),
  'inboxCallbacks', (SELECT count(*) FROM clearing_callback_inbox WHERE operation_id = p.operation_id),
  'inboxTerminalCallbacks', (SELECT count(*) FROM clearing_callback_inbox WHERE operation_id = p.operation_id AND external_status IN ('SETTLED', 'REJECTED')),
  'callbackEvents', (SELECT COALESCE(json_agg(json_build_object('status', e.status, 'attempts', e.attempt_count, 'lastError', e.last_error,
        'receipts', (SELECT COALESCE(json_agg(r.consumer_key ORDER BY r.consumer_key), '[]'::json) FROM outbox_consumer_receipts r WHERE r.event_id = e.id))
        ORDER BY e.created_at), '[]'::json) FROM outbox_events e WHERE e.event_type = 'clearing.callback.accepted' AND e.aggregate_id = p.operation_id),
  'payableLedgerMinor', (SELECT ledger_minor FROM ledger WHERE id = '$payableLedger'),
  'payableProjectionMinor', (SELECT projection_minor FROM ledger WHERE id = '$payableLedger'),
  'reserveLedgerMinor', (SELECT ledger_minor FROM ledger WHERE id = '$reserveLedger'),
  'reserveProjectionMinor', (SELECT projection_minor FROM ledger WHERE id = '$reserveLedger'),
  'sourceDepositLedgerMinor', (SELECT l.ledger_minor FROM ledger l JOIN ledger_accounts la ON la.id = l.id WHERE la.customer_account_id = p.source_account_id),
  'unbalancedJournalCount', (SELECT count(*) FROM (SELECT j.id FROM journal_entries j LEFT JOIN postings x ON x.journal_entry_id = j.id GROUP BY j.id
        HAVING count(x.id) < 2 OR COALESCE(sum(x.amount_minor) FILTER (WHERE x.side = 'DEBIT'), 0) <> COALESCE(sum(x.amount_minor) FILTER (WHERE x.side = 'CREDIT'), 0)) u),
  'projectionMismatchCount', (SELECT count(*) FROM ledger WHERE projection_minor IS DISTINCT FROM ledger_minor),
  'negativeProjectionCount', (SELECT count(*) FROM account_balance_projection WHERE balance_minor < 0),
  'exceptions', (SELECT COALESCE(json_agg(json_build_object('category', category, 'severity', severity, 'status', status) ORDER BY detected_at), '[]'::json)
        FROM reconciliation_exceptions WHERE operation_id = p.operation_id)
)::text FROM corporate_payment_intents p WHERE p.id = '$payment';
"@
    return (Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password $sql 'Independent bank SQL grade') | ConvertFrom-Json
}

function Get-SimSnapshot {
    Assert-PostgresTarget $script:state.sim.database $script:state.sim.role $script:state.sim.password 'ledgercore_e6sim_'
    $sql = @"
SELECT json_build_object('database', current_database(), 'serverPort', inet_server_port(), 'capturedAt', clock_timestamp(),
  'operationStatus', o.external_status, 'inquiryCount', o.inquiry_count,
  'callbacks', (SELECT COALESCE(json_agg(json_build_object('sequence', c.sequence_no, 'status', c.external_status, 'delivery', c.delivery_status,
      'attempts', c.attempt_count, 'lastError', c.last_error) ORDER BY c.sequence_no), '[]'::json) FROM simulator_callback_outbox c WHERE c.operation_id = o.operation_id)
)::text FROM simulator_operations o WHERE o.operation_id = '$($script:state.operationId)';
"@
    $raw = Invoke-Sql $script:state.sim.database $script:state.sim.role $script:state.sim.password $sql 'Independent simulator SQL grade'
    if (-not $raw) { return [pscustomobject]@{ operationStatus = $null; callbacks = @() } }
    return $raw | ConvertFrom-Json
}

function Test-AfterInjection([object] $Bank, [object] $Sim) {
    $committedEffect = $Case -eq 'clearing-effect-ack-crash'
    $checks = [ordered]@{
        exactly_one_booking = ([int]$Bank.bookingJournalCount -eq 1)
        terminal_callback_recorded_once = ([int]$Bank.inboxTerminalCallbacks -eq 1)
        external_status_settled = ($Bank.externalStatus -eq 'SETTLED')
        no_refund = ([int]$Bank.refundJournalCount -eq 0)
        at_most_one_settlement = ([int]$Bank.settlementJournalCount -le 1)
        no_unbalanced_journals = ([int]$Bank.unbalancedJournalCount -eq 0)
        projections_match_ledger = ([int]$Bank.projectionMismatchCount -eq 0)
        no_negative_projection = ([int]$Bank.negativeProjectionCount -eq 0)
        simulator_settled = ($Sim.operationStatus -eq 'SETTLED')
    }
    if ($Case -eq 'clearing-effect-session-kill') {
        # No process crash: the simulator receives an HTTP answer. The settlement count is the observation under test,
        # so it is reported, not asserted, at this phase; the restore phase decides whether the effect was recovered.
        $terminal = @($Sim.callbacks | Where-Object { $_.status -in @('SETTLED', 'REJECTED') })
        $checks.simulator_terminal_callback_delivered = ($terminal.Count -eq 1 -and $terminal[0].delivery -eq 'DELIVERED')
    }
    else {
        $checks.settlement_state_matches_fault_window = if ($committedEffect) { [int]$Bank.settlementJournalCount -eq 1 } else { [int]$Bank.settlementJournalCount -eq 0 }
        $checks.payable_matches_window = if ($committedEffect) { [long]$Bank.payableLedgerMinor -eq 0 } else { [long]$Bank.payableLedgerMinor -eq $amountMinor }
        $checks.reserve_matches_window = if ($committedEffect) { [long]$Bank.reserveLedgerMinor -eq ($openingMinor - $amountMinor) } else { [long]$Bank.reserveLedgerMinor -eq $openingMinor }
    }
    return [pscustomobject]$checks
}

function Test-Recovered([object] $Bank, [object] $Sim) {
    $checks = [ordered]@{
        exactly_one_booking = ([int]$Bank.bookingJournalCount -eq 1)
        exactly_one_settlement = ([int]$Bank.settlementJournalCount -eq 1)
        no_refund = ([int]$Bank.refundJournalCount -eq 0)
        payment_completed = ($Bank.paymentStatus -eq 'COMPLETED' -and $Bank.externalStatus -eq 'SETTLED' -and [bool]$Bank.settlementLinked)
        terminal_callback_recorded_once = ([int]$Bank.inboxTerminalCallbacks -eq 1)
        payable_cleared = ([long]$Bank.payableLedgerMinor -eq 0 -and [long]$Bank.payableProjectionMinor -eq 0)
        reserve_debited_once = ([long]$Bank.reserveLedgerMinor -eq ($openingMinor - $amountMinor))
        customer_debited_once = ([long]$Bank.sourceDepositLedgerMinor -eq ($openingMinor - $amountMinor))
        no_unbalanced_journals = ([int]$Bank.unbalancedJournalCount -eq 0)
        projections_match_ledger = ([int]$Bank.projectionMismatchCount -eq 0)
        no_negative_projection = ([int]$Bank.negativeProjectionCount -eq 0)
        simulator_settled = ($Sim.operationStatus -eq 'SETTLED')
    }
    return [pscustomobject]$checks
}

function Test-AllTrue([object] $Checks) { return (($Checks.PSObject.Properties | ForEach-Object { $_.Value }) -notcontains $false) }

function Invoke-ReconciliationRun {
    $session = Login-User $script:state.users.operations
    $run = Invoke-Api $session 'POST' "/api/organizations/$($script:state.organizationId)/operations/reconciliation/runs?limit=50" $null
    $related = @($run.findings | Where-Object { $_.resourceId -eq $script:state.paymentId -or $_.resourceId -eq $script:state.operationId -or $_.operationId -eq $script:state.operationId })
    return [ordered]@{
        runId = $run.runId; findingCount = $run.findingCount; checkedDomains = $run.checkedDomains; skippedDomains = $run.skippedDomains
        findingCounts = $run.findingCounts
        paymentFindings = @($related | ForEach-Object { [ordered]@{ category = $_.category; severity = $_.severity; resourceType = $_.resourceType; resourceId = $_.resourceId } })
    }
}

function Wait-ForCheckpoint([int] $Seconds) {
    $path = Join-Path $script:runDirectory 'checkpoint.json'
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while (-not (Test-Path -LiteralPath $path) -and [DateTime]::UtcNow -lt $deadline -and -not $script:bankProcess.HasExited) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    Start-Sleep -Milliseconds 50
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

function Start-LockHolder([int] $HoldSeconds) {
    $sqlPath = Join-Path $script:runDirectory 'fault-lock-holder.sql'
    @"
SET application_name = 'e6-lock-holder';
BEGIN;
SELECT id FROM ledger_accounts WHERE id = '$reserveLedger' FOR UPDATE;
SELECT pg_sleep($HoldSeconds);
COMMIT;
"@ | Set-Content -LiteralPath $sqlPath -Encoding ascii
    $env:PGPASSWORD = $script:state.bank.password
    try {
        return Start-Process -FilePath $psql -ArgumentList @('-X', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', $script:state.bank.role,
                '-d', $script:state.bank.database, '-v', 'ON_ERROR_STOP=1', '-f', "`"$sqlPath`"") -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:runDirectory 'fault-lock-holder.stdout.log') -RedirectStandardError (Join-Path $script:runDirectory 'fault-lock-holder.stderr.log')
    }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Start-SessionTerminator {
    $sqlPath = Join-Path $script:runDirectory 'fault-terminator.sql'
    $database = $script:state.bank.database
    @"
SET application_name = 'e6-session-terminator';
CREATE TEMP TABLE e6_victim (pid int, query text, observed_at timestamptz);
DO `$`$
DECLARE victim_pid int; victim_query text; i int := 0;
BEGIN
  LOOP
    SELECT pid, query INTO victim_pid, victim_query FROM pg_stat_activity
     WHERE datname = '$database' AND wait_event_type = 'Lock' AND application_name <> 'e6-lock-holder'
       AND query LIKE 'SELECT id FROM ledger_accounts WHERE id = %FOR UPDATE%' LIMIT 1;
    EXIT WHEN victim_pid IS NOT NULL OR i >= 1800;
    PERFORM pg_sleep(0.05);
    i := i + 1;
  END LOOP;
  IF victim_pid IS NOT NULL THEN
    INSERT INTO e6_victim VALUES (victim_pid, victim_query, clock_timestamp());
    PERFORM pg_terminate_backend(victim_pid);
  END IF;
END
`$`$;
SELECT json_build_object('terminatedPid', pid, 'query', query, 'observedAt', observed_at) FROM e6_victim;
"@ | Set-Content -LiteralPath $sqlPath -Encoding ascii
    return Start-Process -FilePath $psql -ArgumentList @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', "$postgresPort", '-U', 'postgres',
            '-d', $database, '-v', 'ON_ERROR_STOP=1', '-f', "`"$sqlPath`"") -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:runDirectory 'fault-terminator.stdout.log') -RedirectStandardError (Join-Path $script:runDirectory 'fault-terminator.stderr.log')
}

function Wait-SimCallbackState([string[]] $States, [int] $Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $sim = Get-SimSnapshot
        $terminal = @($sim.callbacks | Where-Object { $_.status -in @('SETTLED', 'REJECTED') })
        if ($terminal.Count -gt 0 -and $terminal[-1].delivery -in $States) { return $sim }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    return $sim
}

function Read-State([string] $Id) {
    $script:runDirectory = Get-RunPath $Id
    $script:statePath = Join-Path $script:runDirectory 'run.json'
    if (-not (Test-Path -LiteralPath $script:statePath)) { throw "Run state not found: $Id" }
    $script:state = Get-Content -LiteralPath $script:statePath -Raw | ConvertFrom-Json -AsHashtable
    if ($script:state.caseName -ne $Case -or $script:state.runId -ne $Id) { throw 'Run identity does not match the case and RunId.' }
}

function Stop-All {
    if ($script:state.bankPid) { try { Stop-JavaProcess $bankPort ([int]$script:state.bankPid) $bankJar } catch { $script:state.stopBankError = $_.Exception.Message } }
    if ($script:state.simPid) { try { Stop-JavaProcess $simPort ([int]$script:state.simPid) $simJar } catch { $script:state.stopSimError = $_.Exception.Message } }
    try { Stop-DisposablePostgres } catch { $script:state.stopPostgresError = $_.Exception.Message }
    Save-State
}

Assert-MainAndArtifacts

switch ($Action) {
    'inject' {
        if (-not $RunId) { $RunId = [guid]::NewGuid().ToString() }
        $script:runDirectory = Get-RunPath $RunId
        if (Test-Path -LiteralPath $script:runDirectory) { throw 'The run directory already exists; inject never reuses a run.' }
        New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
        $script:statePath = Join-Path $script:runDirectory 'run.json'
        $head = (& git -C $repo rev-parse HEAD).Trim()
        $dirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
        $script:state = [ordered]@{
            schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
            sourceSha = $head; trackedTreeDirty = $dirty; bankJarSha256 = (Get-FileSha256 $bankJar); simJarSha256 = (Get-FileSha256 $simJar)
            expectedFault = $faultByCase[$Case]; status = 'INITIALIZING'; injectionObserved = $false; bankStarts = @()
        }
        Save-State
        Write-Step "starting $Case run $RunId at $head (label '$Label')"
        try {
            New-RunDatabases
            Start-Simulator
            $hooked = $Case -ne 'clearing-effect-session-kill'
            Start-Bank $hooked
            $users = New-ClearingFixture
            Write-Step "fixture ready: payment $($script:state.paymentId) operation $($script:state.operationId)"
            $holder = $null
            $terminator = $null
            if (-not $hooked) {
                $holder = Start-LockHolder 15
                $deadline = [DateTime]::UtcNow.AddSeconds(20)
                do {
                    $held = Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password `
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'e6-lock-holder' AND state = 'active'" 'Lock holder check'
                    if ($held -eq '1') { break }
                    Start-Sleep -Milliseconds 100
                } while ([DateTime]::UtcNow -lt $deadline)
                if ($held -ne '1') { throw 'The reserve lock holder session did not start.' }
                $terminator = Start-SessionTerminator
                Write-Step 'reserve ledger row lock held; session terminator armed'
            }
            Invoke-Api $users.approver.session 'POST' "/api/payments/$($script:state.paymentId)/approve" $null | Out-Null
            $script:state.approvedAt = [DateTime]::UtcNow.ToString('o')
            Write-Step 'payment approved and booked; waiting for the clearing callback window'
            if ($hooked) {
                $checkpoint = Wait-ForCheckpoint 120
                if ($checkpoint) {
                    if ($checkpoint.runId -ne $RunId -or $checkpoint.caseName -ne $Case -or $checkpoint.faultPoint -ne $script:state.expectedFault `
                            -or [int]$checkpoint.processId -ne $script:bankProcess.Id) {
                        throw 'Checkpoint identity does not match the intended case and process.'
                    }
                    Assert-ExactJavaListener $bankPort $script:bankProcess.Id $bankJar
                    Stop-Process -Id $script:bankProcess.Id -Force
                    try { Wait-Process -Id $script:bankProcess.Id -Timeout 20 -ErrorAction SilentlyContinue } catch { }
                    $script:state.killedAt = [DateTime]::UtcNow.ToString('o')
                    $script:state.injectionObserved = $true
                    $script:state.checkpoint = $checkpoint
                    $script:state.status = 'INJECTED_PROCESS_KILLED'
                    Write-Step "checkpoint $($checkpoint.faultPoint) observed in PID $($checkpoint.processId); process killed"
                }
                else {
                    $script:state.status = 'NOT_INJECTED'
                    $script:state.failure = 'The checkpoint was not reached before the timeout or application exit.'
                }
            }
            else {
                $sim = Wait-SimCallbackState @('DELIVERED', 'DEAD') 60
                if (-not $terminator.WaitForExit(60000)) { throw 'The session terminator did not finish.' }
                $victim = (Get-Content -LiteralPath (Join-Path $script:runDirectory 'fault-terminator.stdout.log') -Raw).Trim()
                if (-not $holder.WaitForExit(30000)) { throw 'The lock holder did not finish.' }
                if ($victim) {
                    $script:state.injectionObserved = $true
                    $script:state.checkpoint = $victim | ConvertFrom-Json
                    $script:state.status = 'INJECTED_SESSION_TERMINATED'
                    Write-Step "terminated the effect session $($script:state.checkpoint.terminatedPid) while it waited for the reserve lock"
                }
                else {
                    $script:state.status = 'NOT_INJECTED'
                    $script:state.failure = 'No financial-effect session waited on the reserve ledger lock.'
                }
                Start-Sleep -Seconds 5
            }
            Save-State
            $bank = Get-BankSnapshot
            $sim = Get-SimSnapshot
            $checks = Test-AfterInjection $bank $sim
            $verdictName = if (-not $script:state.injectionObserved) { 'NOT_INJECTED' } elseif (Test-AllTrue $checks) { 'PASS' } else { 'FAIL' }
            $verdict = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; sourceSha = $script:state.sourceSha
                trackedTreeDirty = $script:state.trackedTreeDirty; phase = 'after_injection'; verdict = $verdictName
                injectionObserved = [bool]$script:state.injectionObserved; faultPoint = $script:state.expectedFault; checkpoint = $script:state.checkpoint
                gradedAt = [DateTime]::UtcNow.ToString('o'); bank = $bank; simulator = $sim; checks = $checks }
            Write-JsonFile (Join-Path $script:runDirectory 'grade-after-injection.json') $verdict
            $script:state.initialVerdict = $verdictName
            Save-State
            $verdict | ConvertTo-Json -Depth 14
        }
        catch {
            $script:state.status = 'INJECT_FAILED'
            $script:state.failure = $_.Exception.Message
            Save-State
            Stop-All
            throw
        }
    }
    'grade' {
        if (-not $RunId) { throw 'Pass -RunId from the inject output.' }
        Read-State $RunId
        Start-DisposablePostgres
        $bank = Get-BankSnapshot
        $sim = Get-SimSnapshot
        [ordered]@{ runId = $RunId; caseName = $Case; gradedAt = [DateTime]::UtcNow.ToString('o'); bank = $bank; simulator = $sim
            afterInjectionChecks = (Test-AfterInjection $bank $sim); recoveredChecks = (Test-Recovered $bank $sim) } | ConvertTo-Json -Depth 14
    }
    'restore' {
        if (-not $RunId) { throw 'Pass -RunId from the inject output.' }
        Read-State $RunId
        $result = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $script:state.label; sourceSha = $script:state.sourceSha
            restoreMode = $RestoreMode; startedAt = [DateTime]::UtcNow.ToString('o') }
        try {
            Start-DisposablePostgres
            if (-not $script:state.injectionObserved) {
                $result.verdict = 'NOT_INJECTED'
                $result.detail = 'The intended fault was not observed; no recovery claim is made.'
            }
            else {
                if (-not (Get-ListenerProcess $simPort)) { Start-Simulator } else { $script:simProcess = Get-Process -Id ([int]$script:state.simPid) }
                $result.simulatorBeforeRestart = Get-SimSnapshot
                if ($RestoreMode -eq 'after-dead-letter') {
                    Write-Step 'waiting until the simulator stops redelivering the terminal callback (DEAD)'
                    $result.simulatorBeforeRestart = Wait-SimCallbackState @('DEAD', 'DELIVERED') 600
                }
                $bankAlive = $script:state.bankPid -and (Get-ListenerProcess $bankPort)
                if ($bankAlive) {
                    $script:bankProcess = Get-Process -Id ([int]$script:state.bankPid)
                    Write-Step 'bank still running (no process crash in this case); observing without a restart first'
                    Start-Sleep -Seconds 30
                    $result.beforeRestartBank = Get-BankSnapshot
                    $result.beforeRestartReconciliation = Invoke-ReconciliationRun
                    Stop-JavaProcess $bankPort ([int]$script:state.bankPid) $bankJar
                }
                Write-Step 'restarting LedgerCore without the fault profile'
                $restartAt = [DateTime]::UtcNow
                Start-Bank $false
                $result.bankRestartedAt = $restartAt.ToString('o')
                $deadline = [DateTime]::UtcNow.AddSeconds(90)
                do {
                    $bank = Get-BankSnapshot
                    if ([int]$bank.settlementJournalCount -ge 1) { break }
                    Start-Sleep -Seconds 2
                } while ([DateTime]::UtcNow -lt $deadline)
                Start-Sleep -Seconds 5
                $bank = Get-BankSnapshot
                $sim = Get-SimSnapshot
                $result.recoveredWithinSeconds = if ([int]$bank.settlementJournalCount -ge 1) { [math]::Round(([DateTime]::UtcNow - $restartAt).TotalSeconds, 1) } else { $null }
                $result.reconciliation = Invoke-ReconciliationRun
                $checks = Test-Recovered $bank $sim
                $result.verdict = if (Test-AllTrue $checks) { 'PASS' } else { 'FAIL' }
                $result.finalBank = $bank
                $result.finalSimulator = $sim
                $result.checks = $checks
            }
        }
        catch {
            $result.verdict = 'FAIL'
            $result.failure = $_.Exception.Message
        }
        finally { Stop-All }
        $result.completedAt = [DateTime]::UtcNow.ToString('o')
        Write-JsonFile (Join-Path $script:runDirectory "restore-$RestoreMode.json") $result
        $script:state.status = "RESTORED_$($result.verdict)"
        Save-State
        $result | ConvertTo-Json -Depth 14
    }
}
