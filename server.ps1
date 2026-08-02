# Second Brain - start / stop the server (Windows / PowerShell).
#
# The server must be running for the Telegram bot to work at all: the notes
# live in a local database and the model runs on this machine, so there is
# nobody else to process an incoming message. Phone sends the thought, this
# computer stores it.
#
# Usage:
#   .\server.ps1 start     start in the background, log to logs\server.log
#   .\server.ps1 stop      stop it
#   .\server.ps1 status    is it running, since when
#   .\server.ps1 log       show the tail of the log, follow it live
#
# Starting twice is refused on purpose. The app itself would survive it -
# the second copy cannot bind port 8080 and dies - but during the second or
# two before it dies its Telegram poller is alive and may pick up a pending
# message, then die without confirming it. The message is then delivered to
# the surviving copy as well, and the same thought lands in the database
# twice. Cheaper to refuse than to explain the duplicate later.

[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status', 'log')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'

$jar = Join-Path $PSScriptRoot 'target\second-brain.jar'
$logDir = Join-Path $PSScriptRoot 'logs'
$outLog = Join-Path $logDir 'server.log'
$errLog = Join-Path $logDir 'server.err.log'

function Get-ServerProcess {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine -like '*second-brain.jar*' -and
            $_.CommandLine -like '*--serve*'
        }
}

function Show-Status {
    $running = @(Get-ServerProcess)
    if ($running.Count -eq 0) {
        Write-Host 'Server: STOPPED' -ForegroundColor Yellow
        Write-Host 'Start it with:  .\server.ps1 start'
        return $false
    }
    foreach ($p in $running) {
        Write-Host ("Server: RUNNING  (PID {0}, since {1})" -f $p.ProcessId, $p.CreationDate) -ForegroundColor Green
    }
    if ($running.Count -gt 1) {
        Write-Host ("WARNING: {0} copies are running. Run .\server.ps1 stop, then start." -f $running.Count) -ForegroundColor Red
    }
    Write-Host 'API docs:  http://localhost:8080/swagger-ui.html'
    return $true
}

switch ($Action) {

    'status' {
        Show-Status | Out-Null
    }

    'start' {
        if (@(Get-ServerProcess).Count -gt 0) {
            Write-Host 'Already running - not starting a second copy.' -ForegroundColor Yellow
            Show-Status | Out-Null
            exit 0
        }
        if (-not (Test-Path $jar)) {
            Write-Error "Jar not found: $jar`nBuild it first:  .\mvnw clean package"
            exit 1
        }
        if (-not (Test-Path $logDir)) {
            New-Item -ItemType Directory -Path $logDir | Out-Null
        }

        Start-Process -FilePath 'java' `
            -ArgumentList @('-jar', $jar, '--serve') `
            -WorkingDirectory $PSScriptRoot `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden

        # Spring needs a few seconds. Poll instead of guessing.
        $deadline = 20
        for ($i = 0; $i -lt $deadline; $i++) {
            Start-Sleep -Seconds 1
            if (Test-Path $outLog) {
                # -Encoding UTF8 matters: java writes UTF-8, but PowerShell 5.1
                # reads files in the system codepage by default, which turns
                # every Russian log line into mojibake.
                $text = Get-Content $outLog -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
                if ($text -match 'Tomcat started on port') {
                    Write-Host 'Server started.' -ForegroundColor Green
                    $bot = ($text -split "`n" | Select-String 'TelegramBot' | Select-Object -Last 1)
                    if ($bot) { Write-Host ("  " + $bot.ToString().Trim()) }
                    Write-Host 'Log:  .\server.ps1 log'
                    exit 0
                }
                if ($text -match 'APPLICATION FAILED TO START') {
                    Write-Host 'Server failed to start. Last lines of the log:' -ForegroundColor Red
                    Get-Content $outLog -Tail 15 -Encoding UTF8
                    exit 1
                }
            }
        }
        Write-Host "Started, but no confirmation after $deadline s. Check:  .\server.ps1 log" -ForegroundColor Yellow
    }

    'stop' {
        $running = @(Get-ServerProcess)
        if ($running.Count -eq 0) {
            Write-Host 'Server is not running - nothing to stop.'
            exit 0
        }
        foreach ($p in $running) {
            Stop-Process -Id $p.ProcessId -Force
            Write-Host ("Stopped PID {0}." -f $p.ProcessId) -ForegroundColor Green
        }
    }

    'log' {
        if (-not (Test-Path $outLog)) {
            Write-Host "No log yet: $outLog"
            exit 0
        }
        Write-Host "Following $outLog - press Ctrl+C to stop watching (the server keeps running)."
        Get-Content $outLog -Tail 30 -Wait -Encoding UTF8
    }
}
