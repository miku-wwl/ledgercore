[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $SourcePath,

    [Parameter(Mandatory = $true, Position = 1)]
    [string] $FileName,

    [Parameter(Mandatory = $true, Position = 2)]
    [string] $ArchiveDirectory
)

$ErrorActionPreference = 'Stop'

try {
    if ([string]::IsNullOrWhiteSpace($FileName) -or
        [IO.Path]::GetFileName($FileName) -cne $FileName -or
        $FileName -match '[\\/]') {
        throw 'WAL archive file name must be a single path component.'
    }

    $source = (Resolve-Path -LiteralPath $SourcePath -ErrorAction Stop).Path
    $archiveRoot = (Resolve-Path -LiteralPath $ArchiveDirectory -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or
        -not (Test-Path -LiteralPath $archiveRoot -PathType Container)) {
        throw 'WAL archive source or target directory is invalid.'
    }

    $destination = Join-Path $archiveRoot $FileName
    $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash

    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
        if ($existingHash -ceq $sourceHash) {
            exit 0
        }
        throw "Refusing to overwrite a different archived WAL segment: $FileName"
    }

    $staging = Join-Path $archiveRoot ('.' + $FileName + '.' + [Guid]::NewGuid().ToString('N') + '.partial')
    Copy-Item -LiteralPath $source -Destination $staging -ErrorAction Stop
    $stagedHash = (Get-FileHash -LiteralPath $staging -Algorithm SHA256).Hash
    if ($stagedHash -cne $sourceHash) {
        Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
        throw "WAL archive copy failed SHA-256 verification: $FileName"
    }

    try {
        Move-Item -LiteralPath $staging -Destination $destination -ErrorAction Stop
    }
    catch {
        if ((Test-Path -LiteralPath $destination -PathType Leaf) -and
            ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ceq $sourceHash)) {
            Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
            exit 0
        }
        Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
        throw
    }

    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $sourceHash) {
        throw "Archived WAL segment failed final SHA-256 verification: $FileName"
    }
    exit 0
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
