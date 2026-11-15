[CmdletBinding()]
param([string] $Label = 'E9 alert lab')

# Round 4 / E9: a real Prometheus 3.13.3 server scrapes the lab bank's /actuator/prometheus (authenticated as an
# OPERATIONS member) and evaluates the Round 4 clearing alert rules against a genuinely created failure state: a finite
# reserve refuses a SETTLED payment's settlement. The alerts must fire while the obligation is owed and resolve after an
# audited replenishment and one governed retry. Notification delivery (Alertmanager) is out of scope. Same lab rules
# as clearing-lab.ps1 (127.0.0.1:55437 run cluster; bank 18095; simulator 18094); Prometheus listens on 127.0.0.1:19090.

$Case = 'e9-alert-lab'
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\e6\ClearingLab.Common.ps1')
$prometheusHome = Join-Path $repo 'work/e9-lab/tools/prometheus-3.13.3/prometheus-3.13.3.windows-amd64'
$prometheus = Join-Path $prometheusHome 'prometheus.exe'
$promtool = Join-Path $prometheusHome 'promtool.exe'
$rules = Join-Path $PSScriptRoot 'ledgercore-round4-alerts.yml'
$promPort = 19090
$evidence = Join-Path $repo 'evidence/round4/e5-e9/E9-ALERT-LAB.json'

function Get-Alerts {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$promPort/api/v1/alerts" -TimeoutSec 10
    return @($response.data.alerts | ForEach-Object { [ordered]@{ alert = $_.labels.alertname; state = $_.state; activeAt = $_.activeAt; value = $_.value } })
}

function Get-Sample([string] $Metric) {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$promPort/api/v1/query?query=$Metric" -TimeoutSec 10
    return @($response.data.result | ForEach-Object { $_.value[1] }) | Select-Object -First 1
}

function Wait-AlertState([string[]] $Firing, [int] $Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $alerts = Get-Alerts
        $current = @($alerts | Where-Object { $_.state -eq 'firing' } | ForEach-Object { $_.alert } | Sort-Object -Unique)
        if ($null -eq (Compare-Object @($Firing | Sort-Object) $current -SyncWindow 0)) { return $alerts }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    return $alerts
}

Assert-MainAndArtifacts
if (Get-ListenerProcess $promPort) { throw "Port $promPort is in use." }
& $promtool check rules $rules | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'promtool rejected the Round 4 alert rules.' }
$RunId = [guid]::NewGuid().ToString()
$script:runDirectory = Get-RunPath $RunId
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
$script:statePath = Join-Path $script:runDirectory 'run.json'
$script:state = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
    sourceSha = (& git -C $repo rev-parse HEAD).Trim(); trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
    bankJarSha256 = (Get-FileSha256 $bankJar); status = 'INITIALIZING'; bankStarts = @(); reserveLab = @() }
Save-State
$result = [ordered]@{ schemaVersion = 1; runId = $RunId; label = $Label; sourceSha = $script:state.sourceSha
    trackedTreeDirty = $script:state.trackedTreeDirty; bankJarSha256 = $script:state.bankJarSha256; prometheus = '3.13.3' }
$promProcess = $null
try {
    New-RunDatabases
    Start-Simulator
    Start-Bank $false
    $users = New-ClearingFixture '80.00' 'SIM-SETTLE'
    $ops = Login-User $script:state.users.operations
    $cookie = ($ops.Cookies.GetCookies([uri]$bankUrl) | Where-Object Name -eq 'JSESSIONID').Value
    if (-not $cookie) { throw 'No Operations session cookie for the scrape.' }
    $config = Join-Path $script:runDirectory 'prometheus.yml'
    @"
global:
  scrape_interval: 2s
  evaluation_interval: 2s
rule_files:
  - "$($rules.Replace('\', '/'))"
scrape_configs:
  - job_name: ledgercore-lab
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['127.0.0.1:$bankPort']
    http_headers:
      Cookie:
        values: ['JSESSIONID=$cookie']
"@ | Set-Content -LiteralPath $config -Encoding utf8
    & $promtool check config $config | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'promtool rejected the scrape configuration.' }
    $promProcess = Start-Process -FilePath $prometheus -ArgumentList @("--config.file=$config", "--web.listen-address=127.0.0.1:$promPort",
        "--storage.tsdb.path=$(Join-Path $script:runDirectory 'tsdb')") -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:runDirectory 'prometheus.out.log') -RedirectStandardError (Join-Path $script:runDirectory 'prometheus.err.log')
    Start-Sleep -Seconds 8
    $result.baselineAlerts = Wait-AlertState @() 30
    $result.baselineShortfall = Get-Sample 'ledgercore_clearing_reserve_shortfall_minor'

    # Genuine failure: sweep the reserve down to 50.00 through the guarded lab command, then settle an 80.00 payment.
    $bank = Get-BankSnapshot
    $sweep = [long]$bank.reserveLedgerMinor - 5000
    $sweepText = '{0}.{1:D2}' -f [math]::Floor($sweep / 100), ($sweep % 100)
    Invoke-ReserveLab 'OUT' $sweepText 'E9 alert lab: finite settlement reserve' | Out-Null
    Invoke-Api $users.approver.session 'POST' "/api/payments/$($script:state.paymentId)/approve" $null | Out-Null
    $result.failureCreatedAt = [DateTime]::UtcNow.ToString('o')
    $result.firing = Wait-AlertState @('LedgerCoreClearingEffectPending', 'LedgerCoreClearingReserveShortfall') 180
    $result.firingAt = [DateTime]::UtcNow.ToString('o')
    $result.shortfallWhileFiring = Get-Sample 'ledgercore_clearing_reserve_shortfall_minor'
    $result.effectsPendingWhileFiring = Get-Sample 'ledgercore_clearing_effects_pending'

    # Recovery: audited replenishment and one governed retry; both alerts must resolve.
    Invoke-ReserveLab 'IN' '100.00' 'E9 alert lab: replenish the settlement reserve' | Out-Null
    $eventId = Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password `
        "SELECT id FROM outbox_events WHERE event_type = 'clearing.callback.accepted' AND aggregate_id = '$($script:state.operationId)'" 'Work item lookup'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $status = Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password "SELECT status FROM outbox_events WHERE id = '$eventId'" 'Work item status'
        if ($status -eq 'DEAD') { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($status -eq 'DEAD') { Invoke-Api $ops 'POST' "/api/organizations/$($script:state.organizationId)/operations/outbox/$eventId/retry" $null | Out-Null }
    $result.retryPath = if ($status -eq 'DEAD') { 'governed dead-letter retry' } else { "automatic retry ($status)" }
    $result.resolved = Wait-AlertState @() 180
    $result.resolvedAt = [DateTime]::UtcNow.ToString('o')
    $result.shortfallAfterRecovery = Get-Sample 'ledgercore_clearing_reserve_shortfall_minor'
    $result.finalBank = Get-BankSnapshot
    $firingNames = @($result.firing | Where-Object { $_.state -eq 'firing' } | ForEach-Object { $_.alert })
    $checks = [ordered]@{
        quiet_before_failure = (@($result.baselineAlerts | Where-Object { $_.state -eq 'firing' }).Count -eq 0)
        effect_pending_alert_fired = ($firingNames -contains 'LedgerCoreClearingEffectPending')
        reserve_shortfall_alert_fired = ($firingNames -contains 'LedgerCoreClearingReserveShortfall')
        shortfall_measured = ([long]$result.shortfallWhileFiring -eq 3000)
        alerts_resolved_after_recovery = (@($result.resolved | Where-Object { $_.state -eq 'firing' }).Count -eq 0)
        settled_exactly_once = ([int]$result.finalBank.settlementJournalCount -eq 1 -and $result.finalBank.paymentStatus -eq 'COMPLETED')
    }
    $result.checks = $checks
    $result.verdict = if (Test-AllTrue ([pscustomobject]$checks)) { 'PASS' } else { 'FAIL' }
}
catch { $result.verdict = 'FAIL'; $result.failure = $_.Exception.Message }
finally {
    if ($promProcess -and -not $promProcess.HasExited) { Stop-Process -Id $promProcess.Id -Force }
    Stop-All
}
$result.completedAt = [DateTime]::UtcNow.ToString('o')
Write-JsonFile $evidence $result
$result | ConvertTo-Json -Depth 10
