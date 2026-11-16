[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('inject', 'restore')] [string] $Action,
    [string] $RunId,
    [string] $Label = ''
)

# Round 4 E6 payroll case `payroll-mark-failure-crash` (hook PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK, implemented in
# Round 3 but never executed). An approved three-item batch pays 1.00, then fails 2,000.00 for insufficient funds; the
# JVM is killed after that item's transaction rolled back and before its FAILED result is recorded. Recovery must record
# the failure once, pay the third item once and never pay the first item again. Same lab rules as clearing-lab.ps1.

$Case = 'payroll-mark-failure-crash'
. (Join-Path $PSScriptRoot 'ClearingLab.Common.ps1')

function Get-PayrollSnapshot {
    Assert-PostgresTarget $script:state.bank.database $script:state.bank.role $script:state.bank.password 'ledgercore_e6_'
    $batch = $script:state.batchId
    $sql = @"
SELECT json_build_object('database', current_database(), 'serverPort', inet_server_port(), 'capturedAt', clock_timestamp(),
  'batchStatus', (SELECT status FROM payroll_batches WHERE id = '$batch'),
  'results', (SELECT json_agg(json_build_object('sequence', i.sequence_no, 'amountMinor', i.amount_minor, 'status', r.status,
       'failureCode', r.error_code, 'attempts', r.attempt_count, 'transferId', r.transfer_id) ORDER BY i.sequence_no)
     FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id WHERE i.batch_id = '$batch'),
  'transfersForBatch', (SELECT count(*) FROM transfers t JOIN payroll_item_results r ON r.transfer_id = t.id WHERE r.batch_id = '$batch'),
  'payrollReceipts', (SELECT count(*) FROM idempotency_records WHERE idempotency_key LIKE 'payroll-$batch-%' AND status = 'SUCCEEDED'),
  'payerLedgerMinor', (SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
     FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id WHERE la.customer_account_id = '$($script:state.accountId)'),
  'payeeLedgerMinor', (SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
     FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id WHERE la.customer_account_id = '$($script:state.payeeAccountId)'),
  'unbalancedJournalCount', (SELECT count(*) FROM (SELECT j.id FROM journal_entries j LEFT JOIN postings x ON x.journal_entry_id = j.id GROUP BY j.id
        HAVING count(x.id) < 2 OR COALESCE(sum(x.amount_minor) FILTER (WHERE x.side = 'DEBIT'), 0) <> COALESCE(sum(x.amount_minor) FILTER (WHERE x.side = 'CREDIT'), 0)) u),
  'projectionMismatchCount', (SELECT count(*) FROM ledger_accounts la JOIN account_balance_projection pr ON pr.ledger_account_id = la.id
        WHERE pr.balance_minor <> (SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
                                   FROM postings p WHERE p.ledger_account_id = la.id))
)::text;
"@
    return (Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password $sql 'Independent payroll SQL grade') | ConvertFrom-Json
}

function Invoke-Execute([object] $Session) {
    return Invoke-Api $Session 'POST' "/api/payroll/batches/$($script:state.batchId)/execute?limit=10" $null
}

Assert-MainAndArtifacts
switch ($Action) {
    'inject' {
        if (-not $RunId) { $RunId = [guid]::NewGuid().ToString() }
        $script:runDirectory = Get-RunPath $RunId
        if (Test-Path -LiteralPath $script:runDirectory) { throw 'The run directory already exists.' }
        New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
        $script:statePath = Join-Path $script:runDirectory 'run.json'
        $script:state = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
            sourceSha = (& git -C $repo rev-parse HEAD).Trim(); trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
            bankJarSha256 = (Get-FileSha256 $bankJar); expectedFault = 'PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK'; status = 'INITIALIZING'
            injectionObserved = $false; bankStarts = @() }
        Save-State
        Write-Step "starting $Case run $RunId at $($script:state.sourceSha)"
        try {
            New-RunDatabases
            Start-Simulator
            Start-Bank $true
            $admin = Register-User 'padmin'
            $organization = Invoke-Api $admin.session 'POST' '/api/organizations' @{ displayName = "E6 Payroll Lab $($RunId.Substring(0, 8))" }
            $account = Invoke-Api $admin.session 'POST' "/api/organizations/$($organization.id)/accounts" @{ displayName = 'E6 Payroll Payer' }
            $users = [ordered]@{ admin = $admin }
            foreach ($entry in @(@('pmaker', 'PAYROLL_MAKER'), @('papprover', 'PAYROLL_APPROVER'), @('operations', 'OPERATIONS'))) {
                $user = Register-User $entry[0]
                Invoke-Api $admin.session 'POST' "/api/organizations/$($organization.id)/members" @{ email = $user.email; roles = @($entry[1]) } | Out-Null
                $users[$entry[0]] = $user
            }
            $employee = Register-User 'employee'
            $payee = Invoke-Api $employee.session 'POST' '/api/accounts' @{ displayName = 'E6 Salary' }
            $batch = Invoke-Api $users.pmaker.session 'POST' "/api/organizations/$($organization.id)/payroll/batches" @{ name = 'E6 crash batch'; payerAccountId = $account.id }
            $items = @(
                @{ employeeReference = 'E6-P1'; employeeName = 'E6 One'; payeeAccountId = $payee.id; amount = '1.00' },
                @{ employeeReference = 'E6-P2'; employeeName = 'E6 Two'; payeeAccountId = $payee.id; amount = '2000.00' },
                @{ employeeReference = 'E6-P3'; employeeName = 'E6 Three'; payeeAccountId = $payee.id; amount = '3.00' })
            Invoke-Api $users.pmaker.session 'POST' "/api/payroll/batches/$($batch.id)/items" @{ items = $items } | Out-Null
            Invoke-Api $users.pmaker.session 'POST' "/api/payroll/batches/$($batch.id)/submit" $null | Out-Null
            Invoke-Api $users.papprover.session 'POST' "/api/payroll/batches/$($batch.id)/approve" $null | Out-Null
            $script:state.organizationId = $organization.id
            $script:state.accountId = $account.id
            $script:state.payeeAccountId = $payee.id
            $script:state.batchId = $batch.id
            $script:state.users = [ordered]@{}
            foreach ($name in $users.Keys) { $script:state.users[$name] = [ordered]@{ email = $users[$name].email; password = $users[$name].password; customerId = $users[$name].customerId } }
            Save-State
            $approverCookies = $users.papprover.session
            $job = Start-ThreadJob -ScriptBlock { param($base, $batchId, $session)
                $csrf = (Invoke-RestMethod -Uri "$base/api/auth/csrf" -WebSession $session -TimeoutSec 10).token
                try { Invoke-RestMethod -Uri "$base/api/payroll/batches/$batchId/execute?limit=10" -Method Post -WebSession $session -Headers @{ 'X-CSRF-TOKEN' = $csrf } -TimeoutSec 600 | Out-Null }
                catch { } } -ArgumentList $bankUrl, $batch.id, $approverCookies
            $checkpoint = Wait-ForCheckpoint 120
            if ($checkpoint -and $checkpoint.faultPoint -eq $script:state.expectedFault -and [int]$checkpoint.processId -eq $script:bankProcess.Id) {
                Assert-ExactJavaListener $bankPort $script:bankProcess.Id $bankJar
                Stop-Process -Id $script:bankProcess.Id -Force
                try { Wait-Process -Id $script:bankProcess.Id -Timeout 20 -ErrorAction SilentlyContinue } catch { }
                $script:state.injectionObserved = $true
                $script:state.checkpoint = $checkpoint
                $script:state.status = 'INJECTED_PROCESS_KILLED'
                Write-Step "checkpoint $($checkpoint.faultPoint) for item $($checkpoint.facts.itemId) ($($checkpoint.facts.failureCode)); process killed"
            }
            else { $script:state.status = 'NOT_INJECTED' }
            try { Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue } catch { }
            Save-State
            $snapshot = Get-PayrollSnapshot
            $results = @($snapshot.results)
            $checks = [ordered]@{
                first_item_paid_once = ($results[0].status -eq 'SUCCEEDED' -and [int]$snapshot.transfersForBatch -eq 1)
                failing_item_rolled_back_and_unmarked = ($results[1].status -eq 'PENDING' -and -not $results[1].transferId)
                third_item_untouched = ($results[2].status -eq 'PENDING')
                batch_still_processing = ($snapshot.batchStatus -eq 'PROCESSING')
                payer_debited_once = ([long]$snapshot.payerLedgerMinor -eq 99900)
                no_unbalanced_journals = ([int]$snapshot.unbalancedJournalCount -eq 0)
                projections_match_ledger = ([int]$snapshot.projectionMismatchCount -eq 0)
            }
            $verdict = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; sourceSha = $script:state.sourceSha
                trackedTreeDirty = $script:state.trackedTreeDirty; bankJarSha256 = $script:state.bankJarSha256; phase = 'after_injection'
                verdict = if (-not $script:state.injectionObserved) { 'NOT_INJECTED' } elseif (Test-AllTrue ([pscustomobject]$checks)) { 'PASS' } else { 'FAIL' }
                checkpoint = $script:state.checkpoint; payroll = $snapshot; checks = $checks }
            Write-JsonFile (Join-Path $script:runDirectory 'grade-after-injection.json') $verdict
            $verdict | ConvertTo-Json -Depth 12
        }
        catch {
            $script:state.status = 'INJECT_FAILED'
            $script:state.failure = $_.Exception.Message
            Save-State
            Stop-All
            throw
        }
    }
    'restore' {
        Read-State $RunId
        $result = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; sourceSha = $script:state.sourceSha; startedAt = [DateTime]::UtcNow.ToString('o') }
        try {
            Start-DisposablePostgres
            if (-not (Get-ListenerProcess $simPort)) { Start-Simulator } else { $script:simProcess = Get-Process -Id ([int]$script:state.simPid) }
            Start-Bank $false
            $approver = Login-User $script:state.users.papprover
            $result.execution = Invoke-Execute $approver
            $result.secondExecution = Invoke-Execute $approver
            $snapshot = Get-PayrollSnapshot
            $results = @($snapshot.results)
            $checks = [ordered]@{
                batch_partially_completed = ($snapshot.batchStatus -eq 'PARTIALLY_COMPLETED')
                first_item_paid_exactly_once = ($results[0].status -eq 'SUCCEEDED')
                failing_item_failed_once = ($results[1].status -eq 'FAILED' -and $results[1].failureCode -eq 'INSUFFICIENT_FUNDS' -and -not $results[1].transferId)
                third_item_paid = ($results[2].status -eq 'SUCCEEDED')
                exactly_two_transfers_and_receipts = ([int]$snapshot.transfersForBatch -eq 2 -and [int]$snapshot.payrollReceipts -eq 2)
                payer_debited_exactly_four_dollars = ([long]$snapshot.payerLedgerMinor -eq 99600)
                payee_credited_exactly_four_dollars = ([long]$snapshot.payeeLedgerMinor -eq 100400)
                no_unbalanced_journals = ([int]$snapshot.unbalancedJournalCount -eq 0)
                projections_match_ledger = ([int]$snapshot.projectionMismatchCount -eq 0)
                repeated_execution_is_terminal_noop = ($result.secondExecution.status -eq 'PARTIALLY_COMPLETED')
            }
            $result.verdict = if (-not $script:state.injectionObserved) { 'NOT_INJECTED' } elseif (Test-AllTrue ([pscustomobject]$checks)) { 'PASS' } else { 'FAIL' }
            $result.payroll = $snapshot
            $result.checks = $checks
        }
        catch { $result.verdict = 'FAIL'; $result.failure = $_.Exception.Message }
        finally { Stop-All }
        $result.completedAt = [DateTime]::UtcNow.ToString('o')
        Write-JsonFile (Join-Path $script:runDirectory 'restore.json') $result
        $script:state.status = "RESTORED_$($result.verdict)"
        Save-State
        $result | ConvertTo-Json -Depth 12
    }
}
