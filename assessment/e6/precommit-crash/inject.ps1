param([string] $RunId)
& (Join-Path $PSScriptRoot '..\e6-lab.ps1') -Action inject -Case precommit-crash -RunId $RunId
