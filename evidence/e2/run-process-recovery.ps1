<#
Opt-in destructive-process experiment. Run only from the repository root against the disposable
ledgercore_e1_test database on 127.0.0.1:55434 and the LedgerCore JAR on port 8080.
PayrollRecoveryFixtureHarness creates the ignored work/e2/recovery-fixture.json first.
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path '.').Path
$fixture = Get-Content (Join-Path $repo 'work/e2/recovery-fixture.json') -Raw | ConvertFrom-Json
$psql = Join-Path $repo 'work/postgresql-binaries/pgsql/bin/psql.exe'
$java = Join-Path $repo 'work/toolchain/jdk21/jdk-21.0.13+9/bin/java.exe'
$jar = Join-Path $repo 'backend/target/ledgercore-0.0.1-SNAPSHOT.jar'
$baseUrl = 'http://127.0.0.1:8080'
$dbArgs = @('-X', '-q', '-t', '-A', '-w', '-h', '127.0.0.1', '-p', '55434', '-U', 'ledgercore_test', '-d', 'ledgercore_e1_test')
$startedServer = $null

function Invoke-Sql([string]$sql) {
    $result = & $psql @dbArgs -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed while running a recovery experiment query." }
    return (($result -join "`n").Trim())
}

function New-Login([string]$email, [string]$password) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $before = Invoke-RestMethod -Uri "$baseUrl/api/auth/csrf" -WebSession $session
    $body = @{ email = $email; password = $password } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -WebSession $session `
        -ContentType 'application/json' -Headers @{ 'X-CSRF-TOKEN' = $before.token } -Body $body | Out-Null
    $after = Invoke-RestMethod -Uri "$baseUrl/api/auth/csrf" -WebSession $session
    return @{ Session = $session; Token = $after.token }
}

try {
    foreach ($requiredPath in @($psql, $java, $jar)) {
        if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required local test artifact is missing: $requiredPath" }
    }
    $databaseIdentity = Invoke-Sql 'SELECT current_database() || '':'' || inet_server_port()'
    if ($databaseIdentity -ne 'ledgercore_e1_test:55434') {
        throw "Refusing to modify an unexpected PostgreSQL target: $databaseIdentity"
    }
    if ((Invoke-RestMethod -Uri "$baseUrl/actuator/health").status -ne 'UP') {
        throw 'LedgerCore local server is not healthy.'
    }
    $serverPid = (Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -First 1).OwningProcess
    $serverProcess = if ($serverPid) { Get-CimInstance Win32_Process -Filter "ProcessId = $serverPid" }
    if (-not $serverProcess -or $serverProcess.Name -notin @('java.exe', 'javaw.exe') `
            -or $serverProcess.CommandLine -notlike '*ledgercore-0.0.1-SNAPSHOT.jar*') {
        throw 'Refusing to kill port 8080: its listener is not the LedgerCore test JAR.'
    }
    $initial = Invoke-Sql "SELECT b.status || ':' || count(*) FILTER (WHERE r.status='PENDING') FROM payroll_batches b JOIN payroll_items i ON i.batch_id=b.id JOIN payroll_item_results r ON r.item_id=i.id WHERE b.id='$($fixture.batchId)' GROUP BY b.status"
    if ($initial -ne 'PROCESSING:500') { throw "Recovery fixture is not pristine: $initial" }

    Invoke-Sql @'
DROP TRIGGER IF EXISTS trg_test_delay_payroll_commit ON payroll_item_results;
DROP FUNCTION IF EXISTS test_delay_payroll_commit();
CREATE FUNCTION test_delay_payroll_commit() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'SUCCEEDED' AND OLD.status = 'PENDING' THEN
        PERFORM pg_sleep(8);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_test_delay_payroll_commit
AFTER UPDATE ON payroll_item_results
FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'SUCCEEDED')
EXECUTE FUNCTION test_delay_payroll_commit();
'@ | Out-Null

    $approver = New-Login $fixture.approverEmail $fixture.password
    $cookieParts = @()
    foreach ($cookie in $approver.Session.Cookies.GetCookies([uri]$baseUrl)) {
        $cookieParts += "$($cookie.Name)=$($cookie.Value)"
    }
    $cookieHeader = $cookieParts -join '; '
    if (-not $cookieHeader) { throw 'Approver login did not produce a session cookie.' }

    $interruptionFile = Join-Path $repo 'work/e2/recovery-http-interruption.txt'
    $executeJob = Start-Job -ArgumentList $baseUrl, $fixture.batchId, $cookieHeader, $approver.Token, $interruptionFile `
        -ScriptBlock {
            param($url, $batchId, $cookie, $csrf, $interruptionFile)
            try {
                Invoke-RestMethod -Uri "$url/api/payroll/batches/$batchId/execute?limit=500" -Method Post `
                    -Headers @{ Cookie = $cookie; 'X-CSRF-TOKEN' = $csrf } -ContentType 'application/json' -Body '{}' `
                    -TimeoutSec 120 | ConvertTo-Json -Compress | Set-Content -LiteralPath ($interruptionFile -replace 'recovery-http-interruption\.txt$', 'recovery-http-result.json')
                'HTTP_EXECUTION_FINISHED'
            }
            catch {
                ('HTTP_EXECUTION_INTERRUPTED=' + $_.Exception.GetType().Name) |
                    Set-Content -LiteralPath $interruptionFile
                'HTTP_EXECUTION_INTERRUPTED'
            }
        }

    $observedCommitted = 0
    $observedInFlightTransaction = $false
    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ($executeJob.State -eq 'Running' -and [DateTime]::UtcNow -lt $deadline) {
        $state = Invoke-Sql "SELECT (SELECT count(*) FILTER (WHERE r.status='SUCCEEDED') || ':' || count(*) FILTER (WHERE r.status='PENDING') FROM payroll_items i JOIN payroll_item_results r ON r.item_id=i.id WHERE i.batch_id='$($fixture.batchId)') || ':' || (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query ILIKE '%payroll_item_results%')"
        $parts = $state.Split(':')
        $observedCommitted = [int]$parts[0]
        $observedInFlightTransaction = [int]$parts[2] -gt 0
        if ($observedCommitted -ge 3 -and $observedInFlightTransaction) {
            Stop-Process -Id $serverPid -Force
            break
        }
        Start-Sleep -Milliseconds 150
    }
    if ($observedCommitted -lt 3 -or -not $observedInFlightTransaction) {
        throw "Worker did not reach the in-flight kill checkpoint; committed=$observedCommitted, inFlight=$observedInFlightTransaction."
    }
    Wait-Job -Job $executeJob -Timeout 20 | Out-Null
    Receive-Job -Job $executeJob | Out-Null
    Remove-Job -Job $executeJob -Force
    Start-Sleep -Milliseconds 300
    $afterKill = Invoke-Sql "SELECT count(*) FILTER (WHERE r.status='SUCCEEDED') || ':' || count(*) FILTER (WHERE r.status='PENDING') || ':' || count(*) FILTER (WHERE r.status='FAILED') FROM payroll_items i JOIN payroll_item_results r ON r.item_id=i.id WHERE i.batch_id='$($fixture.batchId)'"
    $expectedPartial = "$observedCommitted`:$([int](500 - $observedCommitted)):0"
    if ($afterKill -ne $expectedPartial) { throw "Post-kill checkpoint mismatch. Expected $expectedPartial but observed $afterKill." }
    Invoke-Sql 'DROP TRIGGER IF EXISTS trg_test_delay_payroll_commit ON payroll_item_results; DROP FUNCTION IF EXISTS test_delay_payroll_commit();' | Out-Null

    $env:LEDGERCORE_DB_URL = 'jdbc:postgresql://127.0.0.1:55434/ledgercore_e1_test'
    $env:LEDGERCORE_DB_USERNAME = 'ledgercore_test'
    $env:LEDGERCORE_DB_PASSWORD = ''
    $env:LEDGERCORE_COOKIE_SECURE = 'false'
    $stdoutPath = Join-Path $repo 'work/e2/recovery-restarted-server.stdout.log'
    $stderrPath = Join-Path $repo 'work/e2/recovery-restarted-server.stderr.log'
    $startedServer = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory $repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $healthy = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            if ((Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') {
                $healthy = $true
                break
            }
        }
        catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw 'LedgerCore did not become healthy after restart.' }

    $restartedApprover = New-Login $fixture.approverEmail $fixture.password
    $response = Invoke-RestMethod -Uri "$baseUrl/api/payroll/batches/$($fixture.batchId)/execute?limit=1000" `
        -Method Post -WebSession $restartedApprover.Session `
        -Headers @{ 'X-CSRF-TOKEN' = $restartedApprover.Token } -ContentType 'application/json' -Body '{}'
    if ($response.status -ne 'COMPLETED' -or $response.succeededCount -ne 500 -or $response.pendingCount -ne 0) {
        throw "Recovery response did not reconcile: $($response.status), succeeded=$($response.succeededCount), pending=$($response.pendingCount)."
    }

    $batchSummary = Invoke-Sql "SELECT b.status || ':' || count(*) FILTER (WHERE r.status='SUCCEEDED') || ':' || count(*) FILTER (WHERE r.status='PENDING') || ':' || count(*) FILTER (WHERE r.status='FAILED') || ':' || sum(i.amount_minor) FROM payroll_batches b JOIN payroll_items i ON i.batch_id=b.id JOIN payroll_item_results r ON r.item_id=i.id WHERE b.id='$($fixture.batchId)' GROUP BY b.status"
    $financialSummary = Invoke-Sql "SELECT count(DISTINCT r.transfer_id) || ':' || count(DISTINCT ir.idempotency_key) || ':' || (SELECT count(*) FROM transfers t JOIN payroll_item_results x ON x.transfer_id=t.id JOIN payroll_items y ON y.id=x.item_id WHERE y.batch_id='$($fixture.batchId)' AND t.source_account_id='$($fixture.payerAccountId)') FROM payroll_items i JOIN payroll_item_results r ON r.item_id=i.id JOIN payroll_batches b ON b.id=i.batch_id LEFT JOIN idempotency_records ir ON ir.owner_customer_id=b.created_by_customer_id AND ir.operation='TRANSFER' AND ir.idempotency_key=('payroll-' || b.id || '-' || i.id) WHERE b.id='$($fixture.batchId)'"
    $unbalancedCount = Invoke-Sql "WITH transfer_postings AS (SELECT t.id, count(p.id) AS posting_count, coalesce(sum(p.amount_minor) FILTER (WHERE p.side='DEBIT'),0) AS debits, coalesce(sum(p.amount_minor) FILTER (WHERE p.side='CREDIT'),0) AS credits FROM payroll_item_results r JOIN payroll_items i ON i.id=r.item_id JOIN transfers t ON t.id=r.transfer_id LEFT JOIN postings p ON p.journal_entry_id=t.journal_entry_id WHERE i.batch_id='$($fixture.batchId)' GROUP BY t.id) SELECT count(*) FILTER (WHERE posting_count<>2 OR debits<>credits) FROM transfer_postings"
    $projectionMismatchCount = Invoke-Sql "SELECT count(*) FROM accounts a JOIN ledger_accounts la ON la.customer_account_id=a.id AND la.kind='CUSTOMER_DEPOSIT' JOIN account_balance_projection p ON p.ledger_account_id=la.id WHERE (a.id='$($fixture.payerAccountId)' OR EXISTS (SELECT 1 FROM payroll_items i WHERE i.batch_id='$($fixture.batchId)' AND i.payee_account_id=a.id)) AND p.balance_minor<>(SELECT coalesce(sum(CASE WHEN posting.side='CREDIT' THEN posting.amount_minor ELSE -posting.amount_minor END),0) FROM postings posting WHERE posting.ledger_account_id=la.id)"
    $payerBalance = Invoke-Sql "SELECT p.balance_minor FROM ledger_accounts la JOIN account_balance_projection p ON p.ledger_account_id=la.id WHERE la.customer_account_id='$($fixture.payerAccountId)' AND la.kind='CUSTOMER_DEPOSIT'"
    if ($batchSummary -ne 'COMPLETED:500:0:0:50000' -or $financialSummary -ne '500:500:500' `
            -or $unbalancedCount -ne '0' -or $projectionMismatchCount -ne '0' -or $payerBalance -ne '50000') {
        throw "Final evidence mismatch: batch=$batchSummary; financial=$financialSummary; unbalanced=$unbalancedCount; projectionMismatch=$projectionMismatchCount; payerBalance=$payerBalance."
    }

    [pscustomobject]@{
        BatchId = $fixture.batchId
        KilledApplicationProcessId = $serverPid
        RestartedApplicationProcessId = $startedServer.Id
        CommittedBeforeKill = $observedCommitted
        InFlightPayrollResultTriggerObserved = $observedInFlightTransaction
        AfterKillStatuses = $afterKill
        FinalBatchSummary = $batchSummary
        TransferIdempotencySourceCounts = $financialSummary
        UnbalancedTransfers = $unbalancedCount
        ProjectionMismatches = $projectionMismatchCount
        PayerBalanceMinor = $payerBalance
        Result = 'PASS'
    } | Format-List
}
finally {
    try { Invoke-Sql 'DROP TRIGGER IF EXISTS trg_test_delay_payroll_commit ON payroll_item_results; DROP FUNCTION IF EXISTS test_delay_payroll_commit();' | Out-Null } catch { }
    if ($startedServer -and -not $startedServer.HasExited) {
        Stop-Process -Id $startedServer.Id -Force -ErrorAction SilentlyContinue
    }
}
