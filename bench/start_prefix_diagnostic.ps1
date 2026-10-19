param(
    [int]$Seconds = 300,
    [ValidateRange(1, 16)][int]$Workers = 16,
    [ValidateRange(1, 4005)][int]$PrefixSize = 3000,
    [string]$Python = 'C:\Users\gudil\AppData\Local\Programs\Python\Python310\python.exe'
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$stem = Join-Path $workspace "target\diagnostic-prefix$PrefixSize"
$pidPath = "$stem.pid"
if (Test-Path -LiteralPath $pidPath) {
    $previousPid = [int](Get-Content -LiteralPath $pidPath)
    if (Get-Process -Id $previousPid -ErrorAction SilentlyContinue) {
        throw "Diagnostic process $previousPid already exists. Inspect $stem.log."
    }
}
if (Test-Path -LiteralPath "$stem.stop") {
    throw "Stop file exists: $stem.stop. Remove it to explicitly restart."
}
$arguments = @('-B', '-u', 'bench/solve_test11_lazy.py',
    '--prefix-size', "$PrefixSize", '--diagnostic-relax-prefix',
    '--seconds', "$Seconds", '--slice', '120', '--core-seconds', '15',
    '--workers', "$Workers", '--initial-free', 'bottom', '--release', '0',
    '--max-disjunctions', '60000', '--clique-times', '9818')
$process = Start-Process -FilePath $Python -ArgumentList $arguments `
    -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$stem.log" -RedirectStandardError "$stem.err"
$process.Id | Set-Content -LiteralPath $pidPath
Write-Output "Started TEST 11 ONLY diagnostic: PID=$($process.Id), limit=$Seconds seconds, workers=$Workers"
Write-Output "Log: $stem.log"
Write-Output "Stop: New-Item -ItemType File '$stem.stop'"
Write-Output 'A result from this run still requires Test 15 repair and joint verification.'
