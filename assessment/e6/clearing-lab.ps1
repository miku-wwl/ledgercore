[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('inject', 'grade', 'restore')] [string] $Action,
    [Parameter(Mandatory)] [ValidateSet('clearing-callback-commit-crash', 'clearing-effect-precommit-crash',
        'clearing-effect-ack-crash', 'clearing-effect-session-kill')] [string] $Case,
    [string] $RunId,
    [ValidateSet('prompt', 'after-dead-letter')] [string] $RestoreMode = 'prompt',
    [string] $Label = '',
    # Optional dispatcher poll interval for the fault run only. A long interval keeps the durable worker from completing
    # the effect in the instant between the checkpoint and the kill, so the restart-recovery path is exercised.
    [int] $OutboxPollMs = 0
)

# Round 4 two-process clearing fault lab. It extends the E6 conventions (assessment/e6/README.md): one GUID run,
# one dedicated PostgreSQL 18 cluster on 127.0.0.1:55437 holding a bank database and a separate simulator database,
# exact-PID process control, JSON verdicts derived only from independent SQL, and NOT_INJECTED when the intended
# checkpoint was never observed. Secrets (database passwords, the run's HMAC key) stay in the ignored run.json.

. (Join-Path $PSScriptRoot 'ClearingLab.Common.ps1')
$script:outboxPollMs = if ($OutboxPollMs -gt 0) { $OutboxPollMs } else { $null }

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
            faultRunOutboxPollMs = $script:outboxPollMs
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
