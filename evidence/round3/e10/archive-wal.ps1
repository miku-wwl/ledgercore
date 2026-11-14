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

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string] $Path)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $digest = $algorithm.ComputeHash($stream)
        $builder = New-Object Text.StringBuilder
        foreach ($value in $digest) {
            [void] $builder.Append($value.ToString('x2', [Globalization.CultureInfo]::InvariantCulture))
        }
        return $builder.ToString()
    }
    finally {
        $stream.Dispose()
        $algorithm.Dispose()
    }
}

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
    $sourceHash = Get-Sha256Hex -Path $source

    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $existingHash = Get-Sha256Hex -Path $destination
        if ($existingHash -ceq $sourceHash) {
            exit 0
        }
        throw "Refusing to overwrite a different archived WAL segment: $FileName"
    }

    $staging = Join-Path $archiveRoot ('.' + $FileName + '.' + [Guid]::NewGuid().ToString('N') + '.partial')
    Copy-Item -LiteralPath $source -Destination $staging -ErrorAction Stop
    $stagedHash = Get-Sha256Hex -Path $staging
    if ($stagedHash -cne $sourceHash) {
        Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
        throw "WAL archive copy failed SHA-256 verification: $FileName"
    }

    try {
        Move-Item -LiteralPath $staging -Destination $destination -ErrorAction Stop
    }
    catch {
        if ((Test-Path -LiteralPath $destination -PathType Leaf) -and
            ((Get-Sha256Hex -Path $destination) -ceq $sourceHash)) {
            Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
            exit 0
        }
        Remove-Item -LiteralPath $staging -ErrorAction SilentlyContinue
        throw
    }

    if ((Get-Sha256Hex -Path $destination) -cne $sourceHash) {
        throw "Archived WAL segment failed final SHA-256 verification: $FileName"
    }
    exit 0
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
