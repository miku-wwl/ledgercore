[CmdletBinding()]
param([string] $Label = 'E12-G02 restore')

# E12-G02 representative restore (Round 4). The bank database is backed up while a payment is booked and only ACCEPTED,
# the independent simulator then settles it and delivers its callbacks, and the bank is restored from the backup into a
# new database. The simulator is NOT restored, so the two systems now disagree in time. Recovery must come from the
# original operation identity: reconciliation detects the missing callbacks, its authenticated inquiry reports the
# bank's restored sequence, the simulator redelivers the later signed callbacks (R4-07), and the payment settles exactly
# once with no second booking. Logical backup (pg_dump -Fc) of the bank database only; this is not a PITR or an offsite
# backup claim. Uses the E6 lab cluster rules (127.0.0.1:55437, run directory under work/e6-lab/runs).

$Case = 'e12-restore'
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\e6\ClearingLab.Common.ps1')
$pgDump = Join-Path $pgBin 'pg_dump.exe'
$pgRestore = Join-Path $pgBin 'pg_restore.exe'
$evidence = Join-Path $repo 'evidence/round4/e12/lab/E12-RESTORE.json'
$restoreAmountMinor = 555

Assert-MainAndArtifacts
$RunId = [guid]::NewGuid().ToString()
$script:runDirectory = Get-RunPath $RunId
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
$script:statePath = Join-Path $script:runDirectory 'run.json'
$script:state = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
    sourceSha = (& git -C $repo rev-parse HEAD).Trim(); trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
    bankJarSha256 = (Get-FileSha256 $bankJar); simJarSha256 = (Get-FileSha256 $simJar); status = 'INITIALIZING'; bankStarts = @() }
Save-State
$result = [ordered]@{ schemaVersion = 1; runId = $RunId; label = $Label; sourceSha = $script:state.sourceSha
    trackedTreeDirty = $script:state.trackedTreeDirty; bankJarSha256 = $script:state.bankJarSha256; simJarSha256 = $script:state.simJarSha256 }
try {
    New-RunDatabases
    Start-Simulator
    Start-Bank $false
    $users = New-ClearingFixture '5.55' 'SIM-ACCEPT'
    Invoke-Api $users.approver.session 'POST' "/api/payments/$($script:state.paymentId)/approve" $null | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 1 } while ([int]$bank.inboxCallbacks -lt 1 -and [DateTime]::UtcNow -lt $deadline)
    $result.beforeBackup = $bank

    $dump = Join-Path $script:runDirectory 'bank-backup.dump'
    $env:PGPASSWORD = $script:state.bank.password
    $backupStart = [DateTime]::UtcNow
    try { & $pgDump -h 127.0.0.1 -p $postgresPort -U $script:state.bank.role -d $script:state.bank.database -Fc -f $dump; if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' } }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    $result.backup = [ordered]@{ seconds = [math]::Round(([DateTime]::UtcNow - $backupStart).TotalSeconds, 3); bytes = (Get-Item $dump).Length
        sha256 = (Get-FileSha256 $dump); takenAt = $backupStart.ToString('o') }
    Write-Step "logical backup taken ($($result.backup.bytes) bytes) while the payment was $($bank.externalStatus)"

    # After the backup the simulator settles the payment and the live bank receives every callback.
    $ops = Login-User $script:state.users.operations
    Invoke-Api $ops 'POST' "/api/operations/clearing/$($script:state.operationId)/inquiry" $null | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Api $ops 'POST' "/api/operations/clearing/$($script:state.operationId)/inquiry" $null | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 1 } while ([int]$bank.settlementJournalCount -lt 1 -and [DateTime]::UtcNow -lt $deadline)
    $result.liveBankBeforeFailure = $bank
    $result.simulatorBeforeFailure = Get-SimSnapshot
    Write-Step "live bank settled ($($bank.paymentStatus)); simulator callbacks delivered; failing the bank now"

    # Failure: the bank database is lost. Restore the backup into a new database and restart the bank on it.
    $failureAt = [DateTime]::UtcNow
    Stop-JavaProcess $bankPort ([int]$script:state.bankPid) $bankJar
    $original = $script:state.bank.database
    $restored = "$original" + '_r'
    & $createdb -h 127.0.0.1 -p $postgresPort -U postgres -O $script:state.bank.role $restored
    if ($LASTEXITCODE -ne 0) { throw 'createdb for the restore target failed.' }
    $env:PGPASSWORD = $script:state.bank.password
    $restoreStart = [DateTime]::UtcNow
    try { & $pgRestore -h 127.0.0.1 -p $postgresPort -U $script:state.bank.role -d $restored --exit-on-error $dump; if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed.' } }
    finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    $result.restore = [ordered]@{ seconds = [math]::Round(([DateTime]::UtcNow - $restoreStart).TotalSeconds, 3); database = $restored; originalDatabase = $original }
    $script:state.bank.database = $restored
    Save-State
    Start-Bank $false
    $result.bankReadyAfterFailureSeconds = [math]::Round(([DateTime]::UtcNow - $failureAt).TotalSeconds, 3)
    $result.restoredBank = Get-BankSnapshot
    Write-Step "restored bank is back at $($result.restoredBank.externalStatus) with $($result.restoredBank.settlementJournalCount) settlement journals"

    # Recovery under the original identity: reconciliation detects the gap; its inquiry asks for later callbacks.
    $ops = Login-User $script:state.users.operations
    $recoveryStart = [DateTime]::UtcNow
    $result.reconciliation = Invoke-ReconciliationRun
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do { $bank = Get-BankSnapshot; Start-Sleep -Seconds 1 } while ([int]$bank.settlementJournalCount -lt 1 -and [DateTime]::UtcNow -lt $deadline)
    Start-Sleep -Seconds 3
    $result.recoverySeconds = if ([int]$bank.settlementJournalCount -ge 1) { [math]::Round(([DateTime]::UtcNow - $recoveryStart).TotalSeconds, 3) } else { $null }
    $bank = Get-BankSnapshot
    $sim = Get-SimSnapshot
    $grader = (Invoke-Sql $restored $script:state.bank.role $script:state.bank.password `
        (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'graders/financial-hard-gates.sql') -Raw) 'E12 financial grader') | ConvertFrom-Json
    $hard = @('unbalancedJournals', 'projectionMismatches', 'negativeNormalBalances', 'balanceSheetDifferenceMinor', 'duplicatePaymentJournals',
        'completedWithoutSettlement', 'settlementWithoutSettledOutcome', 'refundAfterSettlementOrWithoutRejection', 'bookedWithoutIndependentApproval',
        'deliveredWorkMaskingOwedEffect')
    $checks = [ordered]@{
        restored_to_the_backup_point = ($result.restoredBank.externalStatus -eq 'ACCEPTED' -and [int]$result.restoredBank.settlementJournalCount -eq 0)
        gap_detected = (@($result.reconciliation.paymentFindings | Where-Object { $_.category -eq 'CLEARING_CALLBACK_MISSING' }).Count -ge 1)
        one_booking = ([int]$bank.bookingJournalCount -eq 1)
        one_settlement = ([int]$bank.settlementJournalCount -eq 1)
        no_refund = ([int]$bank.refundJournalCount -eq 0)
        completed = ($bank.paymentStatus -eq 'COMPLETED' -and $bank.externalStatus -eq 'SETTLED')
        payable_cleared = ([long]$bank.payableLedgerMinor -eq 0)
        customer_debited_once = ([long]$bank.sourceDepositLedgerMinor -eq ($openingMinor - $restoreAmountMinor))
        reserve_debited_once = ([long]$bank.reserveLedgerMinor -eq ($openingMinor - $restoreAmountMinor))
        all_callbacks_received_in_order = ([int]$bank.inboxCallbacks -eq @($sim.callbacks).Count)
        grader_hard_gates = (@($hard | Where-Object { [long]$grader.$_ -ne 0 }).Count -eq 0)
    }
    $result.finalBank = $bank
    $result.finalSimulator = $sim
    $result.grader = $grader
    $result.checks = $checks
    $result.verdict = if (Test-AllTrue ([pscustomobject]$checks)) { 'PASS' } else { 'FAIL' }
}
catch { $result.verdict = 'FAIL'; $result.failure = $_.Exception.Message }
finally { Stop-All }
$result.completedAt = [DateTime]::UtcNow.ToString('o')
Write-JsonFile (Join-Path $script:runDirectory 'restore-result.json') $result
Write-JsonFile $evidence $result
$result | ConvertTo-Json -Depth 12
