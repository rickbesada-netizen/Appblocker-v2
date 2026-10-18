# Exit 1 if AppBlocker's central Strict Mode is currently ON, else exit 0.
# Used by the uninstaller to refuse removal while Strict Mode is on.
$ErrorActionPreference = 'SilentlyContinue'
try {
  $cfg = Join-Path $env:ProgramData 'AppBlocker\config.json'
  $j = Get-Content $cfg -Raw | ConvertFrom-Json
  if ($j.strict.enabled) { exit 1 } else { exit 0 }
} catch { exit 0 }
