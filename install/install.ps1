# Installs the ChurchPresenter signage player on a Windows 10/11 mini PC. In PowerShell opened with
# "Run as administrator":
#
#   irm https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.ps1 | iex
#
# It installs the agent with its own Java runtime as a task that starts with Windows, and Microsoft
# Edge in kiosk mode as a task that starts when the signage user signs in. Set that user to sign in
# automatically (the script explains how). Run it again to update; pairing and content are kept.
# Set $env:SIGNAGE_ADDRESS = "host:port" first to skip the address question.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = 'ChurchPresenter/ChurchPresenter-Signage'
$BaseUrl = if ($env:SIGNAGE_BASE_URL) { $env:SIGNAGE_BASE_URL } else { "https://github.com/$Repo/releases/latest/download" }
$Prefix = Join-Path $env:ProgramFiles 'ChurchPresenterSignage'
$Data = Join-Path $env:ProgramData 'ChurchPresenterSignage'
$AgentTask = 'ChurchPresenter Signage Agent'
$KioskTask = 'ChurchPresenter Signage Kiosk'
$KioskPort = 8090

function Say($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run PowerShell as administrator, then run the install command again.'
}
if (-not [Environment]::Is64BitOperatingSystem) { throw 'A 64-bit Windows is needed.' }

$address = $env:SIGNAGE_ADDRESS
if ($null -eq $address) {
    Write-Host ''
    Write-Host 'ChurchPresenter is found automatically on most networks.'
    Write-Host 'If the TVs are on a different network from the ChurchPresenter PC, enter its address'
    Write-Host '(for example 192.168.1.20:8765). Otherwise just press Enter.'
    $address = Read-Host 'ChurchPresenter address'
}
$kioskUser = Read-Host "Windows user the TV signs in as (Enter for $env:USERNAME)"
if ([string]::IsNullOrWhiteSpace($kioskUser)) { $kioskUser = $env:USERNAME }

Say 'Downloading the signage player (windows-x64)'
$work = Join-Path $env:TEMP ("signage-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $bundle = 'churchpresenter-signage-windows-x64.zip'
    Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/$bundle" -OutFile (Join-Path $work $bundle)
    Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/SHA256SUMS" -OutFile (Join-Path $work 'SHA256SUMS')
    $expected = (Get-Content (Join-Path $work 'SHA256SUMS') | Where-Object { $_ -match " $([regex]::Escape($bundle))$" }) -split ' ' | Select-Object -First 1
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $work $bundle)).Hash.ToLower()
    if ($expected -ne $actual) { throw 'The download does not match its checksum.' }

    Say "Installing to $Prefix"
    Stop-ScheduledTask -TaskName $AgentTask -ErrorAction SilentlyContinue
    Stop-ScheduledTask -TaskName $KioskTask -ErrorAction SilentlyContinue
    Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Prefix*" } | Stop-Process -Force
    Expand-Archive -Path (Join-Path $work $bundle) -DestinationPath $work -Force
    if (Test-Path $Prefix) { Remove-Item -Recurse -Force $Prefix }
    Move-Item (Join-Path $work 'churchpresenter-signage') $Prefix
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $Data | Out-Null

# Small launchers in a folder without spaces, so the agent can run the kiosk restart as one command.
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw 'Microsoft Edge was not found.' }

$agentArgs = "--data `"$Data`" --kiosk-port $KioskPort --no-cec --browser-restart `"$Data\restart-kiosk.cmd`""
if (-not [string]::IsNullOrWhiteSpace($address)) { $agentArgs += " --address $address" }
Set-Content -Encoding ASCII -Path "$Data\run-agent.cmd" -Value @"
@echo off
set "JAVA_HOME=$Prefix\runtime"
set "JAVA_OPTS=-Xmx256m"
"$Prefix\bin\churchpresenter-signage.bat" $agentArgs >> "$Data\agent.log" 2>&1
"@
Set-Content -Encoding ASCII -Path "$Data\run-kiosk.cmd" -Value @"
@echo off
start "" "$edge" --kiosk http://127.0.0.1:$KioskPort --edge-kiosk-type=fullscreen --no-first-run --autoplay-policy=no-user-gesture-required --user-data-dir="$Data\browser"
"@
Set-Content -Encoding ASCII -Path "$Data\restart-kiosk.cmd" -Value @"
@echo off
taskkill /f /im msedge.exe >nul 2>&1
schtasks /run /tn "$KioskTask" >nul
"@

Say 'Creating the start-up tasks'
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -Force -TaskName $AgentTask -Settings $settings `
    -Action (New-ScheduledTaskAction -Execute "$Data\run-agent.cmd") `
    -Trigger (New-ScheduledTaskTrigger -AtStartup) `
    -Principal (New-ScheduledTaskPrincipal -UserId 'SYSTEM' -RunLevel Highest) | Out-Null
Register-ScheduledTask -Force -TaskName $KioskTask -Settings $settings `
    -Action (New-ScheduledTaskAction -Execute "$Data\run-kiosk.cmd") `
    -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $kioskUser) `
    -Principal (New-ScheduledTaskPrincipal -UserId $kioskUser -LogonType Interactive) | Out-Null

Say 'Keeping the screen on'
powercfg /change monitor-timeout-ac 0
powercfg /change standby-timeout-ac 0

Start-ScheduledTask -TaskName $AgentTask
Say 'Installed'
Write-Host "The TV will show a pairing code. Approve it in ChurchPresenter's Signage tab."
Write-Host ''
Write-Host "Last step: make Windows sign in as $kioskUser by itself, so the kiosk starts after a power cut."
Write-Host 'Microsoft explains how: https://learn.microsoft.com/troubleshoot/windows-server/user-profiles-and-logon/turn-on-automatic-logon'
Write-Host 'Then restart the PC.'
