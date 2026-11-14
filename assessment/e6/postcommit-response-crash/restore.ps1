param([Parameter(Mandatory)] [string] $RunId)
& (Join-Path $PSScriptRoot '..\e6-lab.ps1') -Action restore -Case postcommit-response-crash -RunId $RunId
