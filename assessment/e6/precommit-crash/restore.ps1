param([Parameter(Mandatory)] [string] $RunId)
& (Join-Path $PSScriptRoot '..\e6-lab.ps1') -Action restore -Case precommit-crash -RunId $RunId
