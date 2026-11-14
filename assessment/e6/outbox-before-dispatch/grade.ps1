param([Parameter(Mandatory)] [string] $RunId, [switch] $AfterRestore)
& (Join-Path $PSScriptRoot '..\e6-lab.ps1') -Action grade -Case outbox-before-dispatch -RunId $RunId -AfterRestore:$AfterRestore
