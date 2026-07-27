# Second Brain - quick one-line thought capture (Windows / PowerShell).
#
# Usage:
#   .\sb.ps1 "nado kupit bilety #poezdka"      # quote the text if it has # or special chars
#   .\sb.ps1 pozvonit vrachu zavtra
#
# The text is piped to java via stdin as UTF-8, so Cyrillic is preserved
# (unlike passing it through command-line arguments on Windows).
#
# NOTE: in PowerShell '#' starts a comment, so wrap any thought containing
# a #tag in quotes: .\sb.ps1 "ideya temnaya tema #proekt"

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Words
)

$ErrorActionPreference = 'Stop'

$jar = Join-Path $PSScriptRoot 'target\second-brain.jar'
if (-not (Test-Path $jar)) {
    Write-Error "Jar not found: $jar. Build first: mvn clean package"
    exit 1
}

if (-not $Words -or $Words.Count -eq 0) {
    Write-Error 'Empty thought. Example: .\sb.ps1 pozvonit vrachu zavtra'
    exit 1
}

$text = ($Words -join ' ')

# Force UTF-8 for java stdin and console output.
$prevOut = [Console]::OutputEncoding
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try {
    $text | & java '-Dfile.encoding=UTF-8' -jar $jar --stdin
}
finally {
    [Console]::OutputEncoding = $prevOut
}
