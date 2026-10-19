param(
    [ValidateRange(30, 86400)][int]$Seconds = 3600,
    [ValidateRange(1, 16)][int]$Workers = 16,
    [ValidateRange(0, 10000)][int]$Release = 8,
    [ValidateRange(0, 10000)][int]$ExpandOnResume = 0,
    [switch]$Resume,
    [string]$Python = 'C:\Users\gudil\AppData\Local\Programs\Python\Python310\python.exe'
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$stem = Join-Path $workspace 'target\joint-transit'
$pidPath = "$stem.pid"
if (Test-Path -LiteralPath $pidPath) {
    $previousPid = [int](Get-Content -LiteralPath $pidPath)
    if (Get-Process -Id $previousPid -ErrorAction SilentlyContinue) {
        throw "Search process $previousPid already exists. Inspect $stem.log."
    }
}
if (Test-Path -LiteralPath "$stem.stop") {
    throw "Stop file exists: $stem.stop. Remove it to explicitly restart."
}
if ((Test-Path -LiteralPath "$stem-state.json") -and -not $Resume) {
    throw 'A checkpoint exists. Use -Resume to continue, or preserve and rename the existing checkpoint.'
}
$arguments = @('-B', '-u', 'bench/solve_joint_transit.py',
    '--seconds', "$Seconds", '--workers', "$Workers", '--release', "$Release")
if ($Resume) { $arguments += '--resume' }
if ($ExpandOnResume -gt 0) {
    if (-not $Resume) { throw '-ExpandOnResume requires -Resume.' }
    $arguments += @('--expand-on-resume', "$ExpandOnResume")
}
if (Test-Path -LiteralPath "$stem.log") {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Copy-Item -LiteralPath "$stem.log" -Destination "$stem-$stamp.log"
}
$process = Start-Process -FilePath $Python -ArgumentList $arguments `
    -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$stem.log" -RedirectStandardError "$stem.err"
$process.Id | Set-Content -LiteralPath $pidPath
Write-Output "Started joint search PID=$($process.Id), budget=$Seconds seconds, search workers=$Workers"
Write-Output "Log: $stem.log"
Write-Output "State: $stem-state.json"
Write-Output "Stop: New-Item -ItemType File '$stem.stop'"
Write-Output 'Success requires VERIFIED JOINT TARGET and joint-transit.success.json.'
Write-Output 'A checkpoint resumes constraints and coordinates, not the internal CP-SAT search tree.'
