[CmdletBinding()]
param([string] $Label = 'V2 textbook screenshots')

# Captures genuine screenshots of the accepted application for the V2 textbook: a real reserve-shortage incident on the
# Operations console, owed-settlement and UNKNOWN labels on the Payments page, and the same screens after the audited
# replenishment and one governed retry. It reuses the E6 lab and its guards (disposable cluster 127.0.0.1:55437, bank
# 18095, simulator 18094) and starts a Vite dev server on 127.0.0.1:5173 proxied to the lab bank. All identities and money
# are synthetic; the run-scoped credentials stay in the ignored run directory and never in the manifest.
$Case = 'book-capture'
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\..\assessment\e6\ClearingLab.Common.ps1')
$frontend = Join-Path $repo 'frontend'
$shots = Join-Path $repo 'manual/v2/figures/screenshots'
$vitePort = 5173

# Same helpers as assessment/e6/clearing-recovery-lab.ps1 (not part of the shared library).
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
if (Get-ListenerProcess $vitePort) { throw "Port $vitePort is in use." }
$RunId = [guid]::NewGuid().ToString()
$script:runDirectory = Get-RunPath $RunId
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $shots -Force | Out-Null
$script:statePath = Join-Path $script:runDirectory 'run.json'
$script:state = [ordered]@{ schemaVersion = 1; runId = $RunId; caseName = $Case; label = $Label; createdAt = [DateTime]::UtcNow.ToString('o')
    sourceSha = (& git -C $repo rev-parse HEAD).Trim(); trackedTreeDirty = [bool](& git -C $repo status --porcelain --untracked-files=no)
    bankJarSha256 = (Get-FileSha256 $bankJar); simJarSha256 = (Get-FileSha256 $simJar); status = 'INITIALIZING'; bankStarts = @(); reserveLab = @() }
Save-State
$vite = $null
$manifest = [ordered]@{ schemaVersion = 1; runId = $RunId; sourceSha = $script:state.sourceSha; trackedTreeDirty = $script:state.trackedTreeDirty
    bankJarSha256 = $script:state.bankJarSha256; simJarSha256 = $script:state.simJarSha256; startedAt = [DateTime]::UtcNow.ToString('o')
    application = 'LedgerCore bank and clearing simulator JARs built from sourceSha + Vite dev server from the same commit'
    database = 'Disposable PostgreSQL 18.7 cluster 127.0.0.1:55437 (ledgercore_e6_* run databases); synthetic example.test identities'
    browser = 'Playwright Chromium (frontend/node_modules), headless, 1440 x 1000'; files = @() }

function Invoke-Capture([string] $Phase) {
    $captureInput = Join-Path $script:runDirectory "capture-$Phase.json"
    [ordered]@{ baseUrl = "http://127.0.0.1:$vitePort"; phase = $Phase; outDir = $shots
        operations = @{ email = $script:state.users.operations.email; password = $script:state.users.operations.password }
        approver = @{ email = $script:state.users.approver.email; password = $script:state.users.approver.password } } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $captureInput -Encoding utf8
    $output = & node (Join-Path $PSScriptRoot 'capture-incident.mjs') $captureInput 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Screenshot capture ($Phase) failed: $($output -join ' ')" }
    Remove-Item -LiteralPath $captureInput -Force
    return ($output | Where-Object { "$_".StartsWith('{') } | Select-Object -Last 1) | ConvertFrom-Json
}

try {
    New-RunDatabases
    Start-Simulator
    Start-Bank $false
    $users = New-ClearingFixture '80.00' 'SIM-SETTLE'
    $settledPayment = $script:state.paymentId
    $settledOperation = $script:state.operationId

    # A genuine shortage: leave 50.00 in the reserve through the guarded lab command, then approve the 80.00 payment.
    $bank = Get-BankSnapshot
    $sweep = [long]$bank.reserveLedgerMinor - 5000
    Invoke-ReserveLab 'OUT' ('{0}.{1:D2}' -f [math]::Floor($sweep / 100), ($sweep % 100)) 'Textbook capture: finite settlement reserve' | Out-Null
    Invoke-Api $users.approver.session 'POST' "/api/payments/$settledPayment/approve" $null | Out-Null
    $eventId = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        $eventId = Invoke-Sql $script:state.bank.database $script:state.bank.role $script:state.bank.password `
            "SELECT id FROM outbox_events WHERE event_type = 'clearing.callback.accepted' AND aggregate_id = '$settledOperation' AND status = 'DEAD'" 'Dead work item'
    } while (-not $eventId -and [DateTime]::UtcNow -lt $deadline)
    if (-not $eventId) { throw 'The refused settlement did not reach the dead-letter state.' }
    $ops = Login-User $script:state.users.operations
    Invoke-ReconciliationRun | Out-Null

    # A second payment whose outcome the simulator itself reports as UNKNOWN. Created after the reconciliation run,
    # because the run's own authenticated inquiry would let the toy simulator advance UNKNOWN before the capture.
    $unknown = Invoke-Api $users.maker.session 'POST' "/api/organizations/$($script:state.organizationId)/payments" @{
        sourceAccountId = $script:state.accountId; beneficiaryName = 'Synthetic Fuel Supplies'
        beneficiaryAccountReference = "NZ-BOOK-$($RunId.Substring(0, 8))"; beneficiaryRoutingCode = 'SIM-UNKNOWN'; amount = '12.40' }
    Invoke-Api $users.maker.session 'POST' "/api/payments/$($unknown.id)/submit" $null | Out-Null
    Invoke-Api $users.approver.session 'POST' "/api/payments/$($unknown.id)/approve" $null | Out-Null

    Start-Sleep -Seconds 3
    $viteOut = Join-Path $script:runDirectory 'vite.out.log'
    $viteErr = Join-Path $script:runDirectory 'vite.err.log'
    $previousTarget = $env:VITE_API_TARGET
    $env:VITE_API_TARGET = $bankUrl
    try {
        $vite = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--', '--host', '127.0.0.1', '--port', "$vitePort", '--strictPort') `
            -WorkingDirectory $frontend -WindowStyle Hidden -PassThru -RedirectStandardOutput $viteOut -RedirectStandardError $viteErr
    }
    finally { $env:VITE_API_TARGET = $previousTarget }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $up = $false
    do { Start-Sleep -Seconds 1; try { $up = (Invoke-WebRequest -Uri "http://127.0.0.1:$vitePort/" -TimeoutSec 2 -UseBasicParsing).StatusCode -eq 200 } catch { } }
    while (-not $up -and [DateTime]::UtcNow -lt $deadline)
    if (-not $up) { throw 'The Vite dev server did not start.' }

    $incident = Invoke-Capture 'incident'
    $manifest.files += @($incident.files)
    $manifest.incidentState = Get-BankSnapshot

    # Recovery: audited replenishment, one governed retry, resolution after the independent recheck.
    Invoke-ReserveLab 'IN' '100.00' 'Textbook capture: replenish the settlement reserve' | Out-Null
    Invoke-Api $ops 'POST' "/api/organizations/$($script:state.organizationId)/operations/outbox/$eventId/retry" $null | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do { Start-Sleep -Seconds 1; $bank = Get-BankSnapshot } while ([int]$bank.settlementJournalCount -lt 1 -and [DateTime]::UtcNow -lt $deadline)
    foreach ($category in 'CLEARING_RESERVE_INSUFFICIENT', 'CLEARING_FINANCIAL_EFFECT_PENDING') {
        $exception = Get-OpenException $category
        if ($exception) { Invoke-Resolve $ops $exception 'Treasury replenished the reserve; the settlement posted once after a governed retry.' | Out-Null }
    }
    Invoke-ReconciliationRun | Out-Null
    $recovered = Invoke-Capture 'recovered'
    $manifest.files += @($recovered.files)
    $manifest.recoveredState = Get-BankSnapshot
    $manifest.verdict = if ([int]$manifest.recoveredState.settlementJournalCount -eq 1 -and $manifest.recoveredState.paymentStatus -eq 'COMPLETED') { 'CAPTURED' } else { 'CAPTURED_WITH_UNEXPECTED_STATE' }
}
catch { $manifest.verdict = 'FAILED'; $manifest.failure = $_.Exception.Message }
finally {
    if ($vite -and -not $vite.HasExited) { & taskkill.exe /PID $vite.Id /T /F | Out-Null }
    Stop-All
}
$manifest.completedAt = [DateTime]::UtcNow.ToString('o')
$manifest.note = 'Genuine screens of the running application on synthetic data; nothing was edited after capture. Run directory: work/e6-lab/runs/' + $RunId
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $shots 'capture-manifest.json') -Encoding utf8
$manifest | ConvertTo-Json -Depth 3
