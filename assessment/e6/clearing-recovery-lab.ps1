[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('inject', 'restore')] [string] $Action,
    [Parameter(Mandatory)] [ValidateSet('clearing-reserve-shortage', 'clearing-lost-callback')] [string] $Case,
    [string] $RunId,
    [string] $Label = ''
)

# Round 4 recovery scenarios that need more than a single crash point (same lab, guards and evidence rules as
# clearing-lab.ps1):
#   clearing-reserve-shortage  R4-02: a genuinely finite reserve (lab-only liquidity sweep) refuses a SETTLED payment's
#                              settlement; a server restart, an audited replenishment and one governed retry follow.
#   clearing-lost-callback     R4-04: the bank is down for longer than the simulator's delivery budget, so its later
#                              callbacks go DEAD; recovery must come from an authenticated inquiry, not a new identity.

. (Join-Path $PSScriptRoot 'ClearingLab.Common.ps1')

$shortagePaymentAmount = '80.00'
$shortagePaymentMinor = 8000
$reserveLeftMinor = 5000

function Invoke-ReserveLab([string] $Direction, [string] $Amount, [string] $Reason) {
    $requestId = [guid]::NewGuid().ToString()
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $stdout = Join-Path $script:runDirectory "reserve-lab-$Direction-$stamp.stdout.log"
    $stderr = Join-Path $script:runDirectory "reserve-lab-$Direction-$stamp.stderr.log"
    $environment = @{
        LEDGERCORE_DB_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/$($script:state.bank.database)"
        LEDGERCORE_DB_USERNAME = $script:state.bank.role
        LEDGERCORE_DB_PASSWORD = $script:state.bank.password
        LEDGERCORE_RESERVE_LAB_CONFIRM = 'DISPOSABLE_ONLY'
        LEDGERCORE_RESERVELAB_ENABLED = 'true'
        LEDGERCORE_RESERVELAB_DIRECTION = $Direction
        LEDGERCORE_RESERVELAB_AMOUNT = $Amount
        LEDGERCORE_RESERVELAB_REQUESTID = $requestId
        LEDGERCORE_RESERVELAB_REASON = $Reason
        LEDGERCORE_CLEARING_ENABLED = 'false'
    }
    $arguments = @('-jar', $bankJar, '--spring.profiles.active=reserve-lab', '--spring.main.web-application-type=none',
        '--ledgercore.outbox.scheduler.enabled=false')
    $saved = Set-ChildEnvironment $environment
    try {
        $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    }
    finally { Restore-ChildEnvironment $saved }
    if (-not $process.WaitForExit(180000)) { Stop-Process -Id $process.Id -Force; throw 'The reserve lab command did not finish.' }
    $line = Select-String -LiteralPath $stdout -Pattern '^RESERVE_LAB_RESULT ' | Select-Object -Last 1
    if ($process.ExitCode -ne 0 -or -not $line) { throw "The reserve lab command failed with exit code $($process.ExitCode)." }
    $result = $line.Line.Substring('RESERVE_LAB_RESULT '.Length) | ConvertFrom-Json
    Write-Step "reserve lab $Direction $Amount -> reserve $($result.reserveMinorAfter) treasury $($result.treasuryMinorAfter) (journal $($result.journalEntryId))"
    $script:state.reserveLab = @($script:state.reserveLab) + @($result)
    Save-State
    return $result
}

function Get-TreasuryAndIdentity {
    $sql = @"
SELECT json_build_object(
  'treasuryLedgerMinor', (SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
      FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id WHERE la.id = '00000000-0000-5000-8000-000000000003'),
  'treasuryProjectionMinor', (SELECT balance_minor FROM account_balance_projection WHERE ledger_account_id = '00000000-0000-5000-8000-000000000003'),
  'debitNormalTotalMinor', (SELECT COALESCE(sum(CASE WHEN p.side = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END), 0)
      FROM postings p JOIN ledger_accounts la ON la.id = p.ledger_account_id WHERE la.normal_side = 'DEBIT'),
  'creditNormalTotalMinor', (SELECT COALESCE(sum(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor ELSE -p.amount_minor END), 0)
      FROM postings p JOIN ledger_accounts la ON la.id = p.ledger_account_id WHERE la.normal_side = 'CREDIT'),
  'liquidityJournals', (SELECT count(*) FROM journal_entries WHERE entry_type = 'RESERVE_LIQUIDITY_TRANSFER'),
  'labAuditRows', (SELECT count(*) FROM audit_events WHERE action = 'LAB_RESERVE_LIQUIDITY_TRANSFER'),
  'manualRetryAudits', (SELECT COALESCE(json_agg(json_build_object('actor', actor_customer_id, 'eventId', resource_id)), '[]'::json)
      FROM audit_events WHERE action = 'OUTBOX_MANUAL_RETRY'),
  'resolutionAudits', (SELECT COALESCE(json_agg(json_build_object('actor', actor_customer_id, 'details', details)), '[]'::json)
      FROM audit_events WHERE action = 'RECONCILIATION_EXCEPTION_RESOLVED')
)::text;
"@
    return (Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password $sql 'Ledger identity grade') | ConvertFrom-Json
}

function Get-CallbackEventId {
    return Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password `
        "SELECT id FROM outbox_events WHERE event_type = 'clearing.callback.accepted' AND aggregate_id = '$($script:state.operationId)' ORDER BY created_at DESC LIMIT 1" 'Callback event lookup'
}

function Wait-CallbackEventStatus([string] $Status, [int] $Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $bank = Get-BankSnapshot
        $events = @($bank.callbackEvents)
        if ($events.Count -gt 0 -and $events[-1].status -eq $Status) { return $bank }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    return $bank
}

function Invoke-SignedSimulatorInquiry {
    $body = (@{ operationId = $script:state.operationId } | ConvertTo-Json -Compress)
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($script:state.hmacSecret))
    try { $signature = -join ($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes("$timestamp.") + $bytes) | ForEach-Object { $_.ToString('x2') }) }
    finally { $hmac.Dispose() }
    return Invoke-RestMethod -Uri "$simUrl/internal/operations/$($script:state.operationId)/inquiry" -Method Post -ContentType 'application/json' `
        -Headers @{ 'X-Clearing-Timestamp' = "$timestamp"; 'X-Clearing-Signature' = $signature } -Body $bytes -TimeoutSec 15
}

function Get-OpenException([string] $Category) {
    return Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password `
        "SELECT id FROM reconciliation_exceptions WHERE operation_id = '$($script:state.operationId)' AND category = '$Category' AND status <> 'RESOLVED' ORDER BY detected_at LIMIT 1" 'Exception lookup'
}

function Invoke-Resolve([object] $Session, [string] $ExceptionId, [string] $Note) {
    try {
        Invoke-Api $Session 'POST' "/api/organizations/$($script:state.organizationId)/operations/reconciliation/exceptions/$ExceptionId/resolve" @{ resolution = $Note } | Out-Null
        return 200
    }
    catch { return [int]$_.Exception.Response.StatusCode }
}

Assert-MainAndArtifacts

switch ($Action) {
    'inject' {
        if (-not $RunId) { $RunId = [guid]::NewGuid().ToString() }
        $script:runDirectory = Get-RunPath $RunId
        if (Test-Path -LiteralPath $script:runDirectory) { throw 'The run directory already exists; inject never reuses a run.' }
        New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
        $script:statePath = Join-Path $script:runDirectory 'run.json'
        $script:state = [ordered]@{
            schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
            sourceSha = (& git -C $repo rev-parse HEAD).Trim(); trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
            bankJarSha256 = (Get-FileSha256 $bankJar); simJarSha256 = (Get-FileSha256 $simJar); status = 'INITIALIZING'
            injectionObserved = $false; bankStarts = @(); reserveLab = @()
        }
        Save-State
        Write-Step "starting $Case run $RunId at $($script:state.sourceSha) (label '$Label')"
        $verdict = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; sourceSha = $script:state.sourceSha
            trackedTreeDirty = $script:state.trackedTreeDirty; bankJarSha256 = $script:state.bankJarSha256; simJarSha256 = $script:state.simJarSha256
            phase = 'after_injection' }
        try {
            New-RunDatabases
            Start-Simulator
            Start-Bank $false
            if ($Case -eq 'clearing-reserve-shortage') {
                $users = New-ClearingFixture $shortagePaymentAmount 'SIM-SETTLE'
                $bank = Get-BankSnapshot
                $sweep = [long]$bank.reserveLedgerMinor - $reserveLeftMinor
                $sweepText = '{0}.{1:D2}' -f [math]::Floor($sweep / 100), ($sweep % 100)
                Invoke-ReserveLab 'OUT' $sweepText 'R4-02 lab: make the settlement reserve genuinely finite' | Out-Null
                Invoke-Api $users.approver.session 'POST' "/api/payments/$($script:state.paymentId)/approve" $null | Out-Null
                Write-Step 'payment approved and booked; waiting for the settlement work item to exhaust its retries'
                $bank = Wait-CallbackEventStatus 'DEAD' 90
                $sim = Get-SimSnapshot
                $identity = Get-TreasuryAndIdentity
                $script:state.injectionObserved = ($bank.externalStatus -eq 'SETTLED' -and @($bank.callbackEvents)[-1].status -eq 'DEAD')
                $checks = [ordered]@{
                    external_settled = ($bank.externalStatus -eq 'SETTLED')
                    work_item_dead_letter = (@($bank.callbackEvents)[-1].status -eq 'DEAD')
                    no_settlement = ([int]$bank.settlementJournalCount -eq 0)
                    no_refund = ([int]$bank.refundJournalCount -eq 0)
                    payment_still_pending = ($bank.paymentStatus -eq 'PENDING_CLEARING')
                    obligation_kept = ([long]$bank.payableLedgerMinor -eq $shortagePaymentMinor)
                    reserve_untouched = ([long]$bank.reserveLedgerMinor -eq $reserveLeftMinor -and [long]$bank.reserveProjectionMinor -eq $reserveLeftMinor)
                    critical_exception = (@($bank.exceptions | Where-Object { $_.category -eq 'CLEARING_RESERVE_INSUFFICIENT' -and $_.severity -eq 'CRITICAL' }).Count -eq 1)
                    balance_sheet_balances = ([long]$identity.debitNormalTotalMinor -eq [long]$identity.creditNormalTotalMinor)
                    no_unbalanced_journals = ([int]$bank.unbalancedJournalCount -eq 0)
                    projections_match_ledger = ([int]$bank.projectionMismatchCount -eq 0)
                    simulator_settled = ($sim.operationStatus -eq 'SETTLED')
                }
            }
            else {
                $users = New-ClearingFixture '6.54' 'SIM-ACCEPT'
                Invoke-Api $users.approver.session 'POST' "/api/payments/$($script:state.paymentId)/approve" $null | Out-Null
                $deadline = [DateTime]::UtcNow.AddSeconds(60)
                do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 1 } while ([int]$bank.inboxCallbacks -lt 1 -and [DateTime]::UtcNow -lt $deadline)
                Write-Step "bank recorded callback 1 ($($bank.externalStatus)); stopping the bank for longer than the delivery budget"
                Stop-JavaProcess $bankPort ([int]$script:state.bankPid) $bankJar
                $first = Invoke-SignedSimulatorInquiry
                $second = Invoke-SignedSimulatorInquiry
                $script:state.inquiriesWhileBankDown = @($first, $second)
                Save-State
                Write-Step "simulator moved to $($second.externalStatus) (sequence $($second.sequenceNo)) while the bank was down; waiting for DEAD"
                $deadline = [DateTime]::UtcNow.AddSeconds(600)
                do {
                    $sim = Get-SimSnapshot
                    $dead = @($sim.callbacks | Where-Object { $_.delivery -eq 'DEAD' })
                    if ($dead.Count -ge 1) { break }
                    Start-Sleep -Seconds 5
                } while ([DateTime]::UtcNow -lt $deadline)
                Start-Bank $false
                Start-Sleep -Seconds 20
                $bank = Get-BankSnapshot
                $sim = Get-SimSnapshot
                $script:state.injectionObserved = ($dead.Count -ge 1)
                $checks = [ordered]@{
                    delivery_budget_exhausted = ($dead.Count -ge 1)
                    simulator_settled = ($sim.operationStatus -eq 'SETTLED')
                    bank_missed_later_callbacks = ([int]$bank.inboxCallbacks -eq 1 -and $bank.externalStatus -eq 'ACCEPTED')
                    no_settlement_yet = ([int]$bank.settlementJournalCount -eq 0)
                    obligation_kept = ([long]$bank.payableLedgerMinor -eq 654)
                    no_unbalanced_journals = ([int]$bank.unbalancedJournalCount -eq 0)
                    projections_match_ledger = ([int]$bank.projectionMismatchCount -eq 0)
                }
            }
            $verdict.injectionObserved = [bool]$script:state.injectionObserved
            $verdict.verdict = if (-not $script:state.injectionObserved) { 'NOT_INJECTED' } elseif (Test-AllTrue ([pscustomobject]$checks)) { 'PASS' } else { 'FAIL' }
            $verdict.bank = $bank
            $verdict.simulator = $sim
            $verdict.checks = $checks
            $verdict.gradedAt = [DateTime]::UtcNow.ToString('o')
            $script:state.status = 'INJECTED'
            Save-State
        }
        catch {
            $script:state.status = 'INJECT_FAILED'
            $script:state.failure = $_.Exception.Message
            Save-State
            Stop-All
            throw
        }
        Write-JsonFile (Join-Path $script:runDirectory 'grade-after-injection.json') $verdict
        $verdict | ConvertTo-Json -Depth 14
    }
    'restore' {
        if (-not $RunId) { throw 'Pass -RunId from the inject output.' }
        Read-State $RunId
        $result = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $script:state.label; sourceSha = $script:state.sourceSha
            startedAt = [DateTime]::UtcNow.ToString('o') }
        try {
            Start-DisposablePostgres
            if (-not (Get-ListenerProcess $simPort)) { Start-Simulator } else { $script:simProcess = Get-Process -Id ([int]$script:state.simPid) }
            if (Get-ListenerProcess $bankPort) { Stop-JavaProcess $bankPort ([int]$script:state.bankPid) $bankJar }
            Write-Step 'server restart: LedgerCore stopped and started again'
            Start-Bank $false
            $ops = Login-User $script:state.users.operations
            if ($Case -eq 'clearing-reserve-shortage') {
                Start-Sleep -Seconds 20
                $result.afterRestart = Get-BankSnapshot
                $result.afterRestartReconciliation = Invoke-ReconciliationRun
                $reserveException = Get-OpenException 'CLEARING_RESERVE_INSUFFICIENT'
                $pendingException = Get-OpenException 'CLEARING_FINANCIAL_EFFECT_PENDING'
                $result.resolveBeforeReplenishment = [ordered]@{ reserve = (Invoke-Resolve $ops $reserveException 'Attempted before replenishment.')
                    pending = (Invoke-Resolve $ops $pendingException 'Attempted before replenishment.') }
                $result.replenishment = Invoke-ReserveLab 'IN' '100.00' 'R4-02 lab: audited replenishment of the settlement reserve'
                $eventId = Get-CallbackEventId
                $first = try { Invoke-Api $ops 'POST' "/api/organizations/$($script:state.organizationId)/operations/outbox/$eventId/retry" $null | Out-Null; 200 } catch { [int]$_.Exception.Response.StatusCode }
                $second = try { Invoke-Api $ops 'POST' "/api/organizations/$($script:state.organizationId)/operations/outbox/$eventId/retry" $null | Out-Null; 200 } catch { [int]$_.Exception.Response.StatusCode }
                $result.governedRetryStatuses = @($first, $second)
                $deadline = [DateTime]::UtcNow.AddSeconds(60)
                do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 1 } while ([int]$bank.settlementJournalCount -lt 1 -and [DateTime]::UtcNow -lt $deadline)
                Start-Sleep -Seconds 3
                $result.resolveAfterSettlement = [ordered]@{ reserve = (Invoke-Resolve $ops $reserveException 'Treasury replenished the reserve; settlement posted once.')
                    pending = (Invoke-Resolve $ops $pendingException 'Settlement posted once after the governed retry.') }
            }
            else {
                $result.reconciliationBeforeInquiry = Invoke-ReconciliationRun
                $missingException = Get-OpenException 'CLEARING_CALLBACK_MISSING'
                $result.inquiry = Invoke-Api $ops 'POST' "/api/operations/clearing/$($script:state.operationId)/inquiry" $null
                Write-Step "operator inquiry returned $($result.inquiry.externalStatus) sequence $($result.inquiry.sequenceNo)"
                $deadline = [DateTime]::UtcNow.AddSeconds(90)
                do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 2 } while ([int]$bank.settlementJournalCount -lt 1 -and [DateTime]::UtcNow -lt $deadline)
                Start-Sleep -Seconds 3
                $result.resolveMissingCallback = if ($missingException) { Invoke-Resolve $ops $missingException 'Callbacks redelivered after the authenticated inquiry.' } else { $null }
            }
            $bank = Get-BankSnapshot
            $sim = Get-SimSnapshot
            $identity = Get-TreasuryAndIdentity
            $result.reconciliation = Invoke-ReconciliationRun
            $checks = Test-Recovered $bank $sim
            if ($Case -eq 'clearing-reserve-shortage') {
                $amountChecks = [ordered]@{
                    payable_cleared = ([long]$bank.payableLedgerMinor -eq 0)
                    reserve_after_one_settlement = ([long]$bank.reserveLedgerMinor -eq ($reserveLeftMinor + 10000 - $shortagePaymentMinor))
                    customer_debited_once = ([long]$bank.sourceDepositLedgerMinor -eq ($openingMinor - $shortagePaymentMinor))
                    treasury_projection_matches = ([long]$identity.treasuryLedgerMinor -eq [long]$identity.treasuryProjectionMinor)
                    balance_sheet_balances = ([long]$identity.debitNormalTotalMinor -eq [long]$identity.creditNormalTotalMinor)
                    resolution_refused_while_owed = ($result.resolveBeforeReplenishment.reserve -eq 409 -and $result.resolveBeforeReplenishment.pending -eq 409)
                    one_governed_retry_accepted = (@($result.governedRetryStatuses | Where-Object { $_ -eq 200 }).Count -eq 1)
                    resolution_accepted_after_recheck = ($result.resolveAfterSettlement.reserve -eq 200 -and $result.resolveAfterSettlement.pending -eq 200)
                    no_pending_effect_finding = (@($result.reconciliation.paymentFindings | Where-Object { $_.category -eq 'CLEARING_FINANCIAL_EFFECT_PENDING' }).Count -eq 0)
                }
                foreach ($name in @('reserve_debited_once', 'customer_debited_once', 'payable_cleared')) { $checks.PSObject.Properties.Remove($name) }
                foreach ($entry in $amountChecks.GetEnumerator()) { $checks | Add-Member -NotePropertyName $entry.Key -NotePropertyValue $entry.Value }
            }
            else {
                foreach ($name in @('reserve_debited_once', 'customer_debited_once')) { $checks.PSObject.Properties.Remove($name) }
                $checks | Add-Member -NotePropertyName 'callback_missing_detected_before_recovery' -NotePropertyValue `
                    (@($result.reconciliationBeforeInquiry.paymentFindings | Where-Object { $_.category -eq 'CLEARING_CALLBACK_MISSING' }).Count -ge 1)
                $checks | Add-Member -NotePropertyName 'all_callbacks_delivered_in_order' -NotePropertyValue `
                    (@($sim.callbacks | Where-Object { $_.delivery -ne 'DELIVERED' }).Count -eq 0 -and [int]$bank.inboxCallbacks -eq @($sim.callbacks).Count)
                $checks | Add-Member -NotePropertyName 'customer_debited_once' -NotePropertyValue ([long]$bank.sourceDepositLedgerMinor -eq ($openingMinor - 654))
                $checks | Add-Member -NotePropertyName 'reserve_debited_once' -NotePropertyValue ([long]$bank.reserveLedgerMinor -eq ($openingMinor - 654))
            }
            $result.verdict = if (Test-AllTrue $checks) { 'PASS' } else { 'FAIL' }
            $result.finalBank = $bank
            $result.finalSimulator = $sim
            $result.ledgerIdentity = $identity
            $result.checks = $checks
        }
        catch {
            $result.verdict = 'FAIL'
            $result.failure = $_.Exception.Message
        }
        finally { Stop-All }
        $result.completedAt = [DateTime]::UtcNow.ToString('o')
        Write-JsonFile (Join-Path $script:runDirectory 'restore.json') $result
        $script:state.status = "RESTORED_$($result.verdict)"
        Save-State
        $result | ConvertTo-Json -Depth 14
    }
}
