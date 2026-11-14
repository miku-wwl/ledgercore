param([Parameter(Mandatory)] [string] $RunId, [switch] $AfterRestore)
& (Join-Path $PSScriptRoot '..\e6-lab.ps1') -Action grade -Case precommit-crash -RunId $RunId -AfterRestore:$AfterRestore
