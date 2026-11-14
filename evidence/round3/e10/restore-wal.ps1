param(
    [Parameter(Mandatory=$true)][string]$ArchiveSegment,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
$source = [System.IO.Path]::GetFullPath($ArchiveSegment)
$destination = [System.IO.Path]::GetFullPath($Destination)
$log = Join-Path $PSScriptRoot 'logs\restore-wal-commands.log'
$segment = [System.IO.Path]::GetFileName($source)
if ($segment -notmatch '^[0-9A-F]{24}$' -or -not $source.StartsWith((Join-Path $root 'bank-wal\'), [StringComparison]::OrdinalIgnoreCase) -or -not $destination.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
    Add-Content -LiteralPath $log -Value "$(Get-Date -Format o)|rejected|$segment"
    exit 2
}
try {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Archived WAL segment not found.' }
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $bytes = (Get-Item -LiteralPath $destination).Length
    if ($bytes -ne (Get-Item -LiteralPath $source).Length) { throw 'Restored WAL segment length differs from archive.' }
    Add-Content -LiteralPath $log -Value "$(Get-Date -Format o)|restored|$segment|$bytes"
    exit 0
}
catch {
    Add-Content -LiteralPath $log -Value "$(Get-Date -Format o)|failed|$segment|$($_.Exception.GetType().Name)"
    exit 1
}
