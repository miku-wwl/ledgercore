[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Label,
    [Parameter(Mandatory)] [string] $SummaryPath
)

# Runs the registered Round 4 clearing fault matrix sequentially (the cases share the lab ports) and writes one summary
# with each run id, its after-injection verdict and its recovery verdict. Raw per-run evidence stays in work/e6-lab/runs.
$ErrorActionPreference = 'Stop'
$lab = Join-Path $PSScriptRoot 'clearing-lab.ps1'
$matrix = @(
    @{ id = 'R4-01-A'; case = 'clearing-callback-commit-crash'; restore = 'after-dead-letter' },
    @{ id = 'R4-01-B'; case = 'clearing-callback-commit-crash'; restore = 'prompt' },
    @{ id = 'R4-01-C'; case = 'clearing-effect-session-kill'; restore = 'prompt' },
    @{ id = 'R4-01-D'; case = 'clearing-effect-precommit-crash'; restore = 'after-dead-letter' },
    @{ id = 'R4-01-E'; case = 'clearing-effect-ack-crash'; restore = 'prompt' }
)

function Get-JsonTail([object[]] $Lines) {
    $text = ($Lines | ForEach-Object { "$_" }) -join "`n"
    $start = $text.IndexOf('{')
    if ($start -lt 0) { throw "No JSON verdict in output: $text" }
    return $text.Substring($start) | ConvertFrom-Json
}

$results = @()
foreach ($entry in $matrix) {
    $record = [ordered]@{ id = $entry.id; case = $entry.case; restoreMode = $entry.restore; label = $Label }
    try {
        $inject = Get-JsonTail (& $lab -Action inject -Case $entry.case -Label "$Label $($entry.id)" 6>$null)
        $record.runId = $inject.runId
        $record.sourceSha = $inject.sourceSha
        $record.trackedTreeDirty = $inject.trackedTreeDirty
        $record.injectionVerdict = $inject.verdict
        $record.injectionObserved = $inject.injectionObserved
        $record.afterInjection = [ordered]@{ paymentStatus = $inject.bank.paymentStatus; externalStatus = $inject.bank.externalStatus
            settlementJournalCount = $inject.bank.settlementJournalCount; payableLedgerMinor = $inject.bank.payableLedgerMinor
            simulatorCallbacks = $inject.simulator.callbacks }
        $restore = Get-JsonTail (& $lab -Action restore -Case $entry.case -RunId $inject.runId -RestoreMode $entry.restore 6>$null)
        $record.recoveryVerdict = $restore.verdict
        $record.recoveredWithinSeconds = $restore.recoveredWithinSeconds
        $record.final = [ordered]@{ paymentStatus = $restore.finalBank.paymentStatus; externalStatus = $restore.finalBank.externalStatus
            settlementJournalCount = $restore.finalBank.settlementJournalCount; payableLedgerMinor = $restore.finalBank.payableLedgerMinor
            reserveLedgerMinor = $restore.finalBank.reserveLedgerMinor; callbackEvents = $restore.finalBank.callbackEvents
            exceptions = $restore.finalBank.exceptions; simulatorCallbacksBeforeRestart = $restore.simulatorBeforeRestart.callbacks }
        $record.reconciliationPaymentFindings = $restore.reconciliation.paymentFindings
        $record.reconciliationFindingCount = $restore.reconciliation.findingCount
        if ($restore.beforeRestartBank) {
            $record.beforeRestart = [ordered]@{ paymentStatus = $restore.beforeRestartBank.paymentStatus
                settlementJournalCount = $restore.beforeRestartBank.settlementJournalCount
                reconciliationPaymentFindings = $restore.beforeRestartReconciliation.paymentFindings }
        }
        $record.failedChecks = @($restore.checks.PSObject.Properties | Where-Object { -not $_.Value } | ForEach-Object { $_.Name })
        if ($restore.failure) { $record.failure = $restore.failure }
    }
    catch { $record.harnessError = $_.Exception.Message }
    $results += [pscustomobject]$record
    $results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $SummaryPath -Encoding utf8
}
$results | ConvertTo-Json -Depth 12
