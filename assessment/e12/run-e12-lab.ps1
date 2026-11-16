[CmdletBinding()]
param([string] $Label = 'E12 lab')

# Runs every registered E6 fault case once, sequentially (they share the lab ports), at the current commit and writes
# evidence/round4/e12/lab/E12-LAB.json: per case the run id, the after-injection verdict, the recovery verdict and the
# cleanup result. Each verdict comes from that run's independent SQL grade; raw files stay in work/e6-lab/runs/<run>/.
# Parameters are passed by hashtable splatting: an array such as @('-Action', 'inject') is bound positionally to a
# script, so '-Action' itself would become the value of -Action (found by the first official E12 attempt).
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$e6 = Join-Path $repo 'assessment/e6'
$out = Join-Path $repo 'evidence/round4/e12/lab/E12-LAB.json'
$sha = (& git -C $repo rev-parse HEAD).Trim()

function Get-JsonTail([object[]] $Lines) {
    $text = ($Lines | ForEach-Object { "$_" }) -join "`n"
    $start = $text.IndexOf('{')
    if ($start -lt 0) { throw "No JSON verdict in output: $($text.Substring(0, [Math]::Min(400, $text.Length)))" }
    return $text.Substring($start) | ConvertFrom-Json
}

$plan = @(
    @{ name = 'transfer-precommit-crash'; script = 'e6-lab.ps1'; inject = @{ Action = 'inject'; Case = 'precommit-crash' }; restore = @{ Action = 'restore'; Case = 'precommit-crash' } },
    @{ name = 'transfer-postcommit-response-crash'; script = 'e6-lab.ps1'; inject = @{ Action = 'inject'; Case = 'postcommit-response-crash' }; restore = @{ Action = 'restore'; Case = 'postcommit-response-crash' } },
    @{ name = 'transfer-outbox-before-dispatch'; script = 'e6-lab.ps1'; inject = @{ Action = 'inject'; Case = 'outbox-before-dispatch' }; restore = @{ Action = 'restore'; Case = 'outbox-before-dispatch' } },
    @{ name = 'payroll-mark-failure-crash'; script = 'payroll-lab.ps1'; inject = @{ Action = 'inject'; Label = $Label }; restore = @{ Action = 'restore' } },
    @{ name = 'clearing-callback-commit-crash'; script = 'clearing-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-callback-commit-crash'; OutboxPollMs = 60000; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-callback-commit-crash'; RestoreMode = 'after-dead-letter' } },
    @{ name = 'clearing-effect-precommit-crash'; script = 'clearing-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-effect-precommit-crash'; OutboxPollMs = 60000; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-effect-precommit-crash'; RestoreMode = 'after-dead-letter' } },
    @{ name = 'clearing-effect-ack-crash'; script = 'clearing-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-effect-ack-crash'; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-effect-ack-crash'; RestoreMode = 'prompt' } },
    @{ name = 'clearing-effect-session-kill'; script = 'clearing-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-effect-session-kill'; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-effect-session-kill'; RestoreMode = 'prompt' } },
    @{ name = 'clearing-reserve-shortage'; script = 'clearing-recovery-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-reserve-shortage'; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-reserve-shortage' } },
    @{ name = 'clearing-lost-callback'; script = 'clearing-recovery-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-lost-callback'; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-lost-callback' } },
    @{ name = 'clearing-simulator-restart'; script = 'clearing-recovery-lab.ps1'; inject = @{ Action = 'inject'; Case = 'clearing-simulator-restart'; Label = $Label }; restore = @{ Action = 'restore'; Case = 'clearing-simulator-restart' } }
)

$results = [ordered]@{ schemaVersion = 2; label = $Label; sourceSha = $sha
    trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no); startedAt = [DateTime]::UtcNow.ToString('o') }
foreach ($step in $plan) {
    $record = [ordered]@{}
    try {
        $script = Join-Path $e6 $step.script
        $injectParams = $step.inject.Clone()
        $inject = Get-JsonTail (& $script @injectParams 6>$null)
        $record.runId = $inject.runId
        $record.injection = $inject.verdict
        $record.injectionObserved = $inject.injectionObserved
        $restoreParams = $step.restore.Clone()
        $restoreParams.RunId = $inject.runId
        $restore = Get-JsonTail (& $script @restoreParams 6>$null)
        $record.recovery = $restore.verdict
        $record.failedChecks = if ($restore.checks) { @($restore.checks.PSObject.Properties | Where-Object { -not $_.Value } | ForEach-Object { $_.Name }) } else { @() }
        if ($restore.failure) { $record.failure = $restore.failure }
    }
    catch { $record.recovery = 'HARNESS_ERROR'; $record.failure = $_.Exception.Message }
    # Cleanup result: every process the case started must be gone and the lab ports free before the next case.
    $leftovers = @(foreach ($port in 55437, 18095, 18094) {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) { "$port(pid $($listener.OwningProcess))" } })
    $record.cleanup = if ($leftovers.Count -eq 0) { 'PROCESSES_STOPPED_PORTS_FREE' } else { "LEFTOVER $($leftovers -join ', ')" }
    $results[$step.name] = $record
    $results.updatedAt = [DateTime]::UtcNow.ToString('o')
    $parent = Split-Path -Parent $out
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $out -Encoding utf8
}
$results | ConvertTo-Json -Depth 8
