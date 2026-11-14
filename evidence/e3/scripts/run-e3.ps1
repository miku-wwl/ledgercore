param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('capture', 'h01', 'h02', 'h03', 'all')]
    [string]$Phase
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$javaHome = 'C:\Program Files\HONOR\HNMagicAI\jdk'
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Required Java 21 runtime was not found at $javaHome"
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

$branch = (& git -C $repositoryRoot branch --show-current).Trim()
if ($branch -cne 'main') {
    throw "E3 is main-only; current branch is '$branch'."
}

$phaseMethods = @{
    capture = @('captureEnvironmentBeforeMeasurements')
    h01 = @('balanceHistoryExperiment')
    h02 = @('transferConcurrencyExperiment')
    h03 = @('payrollConcurrencyAndQueryPlans')
    all = @('captureEnvironmentBeforeMeasurements', 'balanceHistoryExperiment',
            'transferConcurrencyExperiment', 'payrollConcurrencyAndQueryPlans')
}

Push-Location (Join-Path $repositoryRoot 'backend')
try {
    foreach ($method in $phaseMethods[$Phase]) {
        Write-Host "Running E3 method: $method"
        $mavenArgs = @(
            '--batch-mode',
            '--no-transfer-progress',
            '-Dstyle.color=never',
            "-Dtest=E3PerformanceExperimentHarness#$method",
            '-De3.output=../evidence/e3',
            'test'
        )
        & .\mvnw.cmd @mavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "E3 method '$method' failed with Maven exit code $LASTEXITCODE"
        }
    }
}
finally {
    Pop-Location
}
