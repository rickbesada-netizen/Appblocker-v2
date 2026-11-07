# Admin PowerShell. Removes the helper task AND releases every protection it
# applied, so uninstall leaves the machine completely normal.
$ErrorActionPreference = 'SilentlyContinue'

# 1) Stop/remove the SYSTEM helper task.
Unregister-ScheduledTask -TaskName "AppBlockerHelper" -Confirm:$false

# 2) Lift the Safe-Mode lockout.
Remove-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System' `
  -Name 'SafeModeBlockNonAdmins' -ErrorAction SilentlyContinue

# 3) Release the install-dir ACLs (take ownership + reset inheritance).
$dir = Join-Path $env:ProgramFiles 'AppBlocker'
if (Test-Path $dir) {
  takeown /f $dir /r /d y | Out-Null
  icacls $dir /reset /t /c | Out-Null
}

# 4) Remove the browser force-install + DNS policies.
foreach ($k in @(
  'HKCU\Software\Policies\Google\Chrome',
  'HKCU\Software\Policies\Microsoft\Edge',
  'HKCU\Software\Policies\BraveSoftware\Brave')) {
  reg delete "$k\ExtensionInstallForcelist" /v 1 /f 2>$null | Out-Null
}
reg delete "HKLM\SOFTWARE\Policies\Mozilla\Firefox\ExtensionSettings\appblocker@local" /f 2>$null | Out-Null

# 5) Undo the admin lockdown: put the previous administrator back and delete the
#    sealed AppBlockerAdmin account, so the machine returns to how it was before.
$cfgDir = Join-Path $env:ProgramData 'AppBlocker'
$stateFile = Join-Path $cfgDir 'lockdown-state.json'
if (Test-Path $stateFile) {
  try {
    $st = Get-Content $stateFile -Raw | ConvertFrom-Json
    $adminGroup = (Get-LocalGroup -SID 'S-1-5-32-544').Name
    # Re-promote whoever we demoted (prefer SID, so Microsoft accounts match too).
    $target = $null
    if ($st.demotedSid) { try { $target = [System.Security.Principal.SecurityIdentifier]$st.demotedSid } catch { $target = $null } }
    if (-not $target -and $st.demotedUser) { $target = $st.demotedUser }
    if ($target) { try { Add-LocalGroupMember -Group $adminGroup -Member $target -ErrorAction SilentlyContinue } catch {} }
    # Delete the sealed admin account we created.
    if ($st.sealedAdmin) { Remove-LocalUser -Name $st.sealedAdmin -ErrorAction SilentlyContinue }
  } catch {}
}

# 6) Remove all AppBlocker data (sealed password, config) so nothing is left behind.
if (Test-Path $cfgDir) {
  takeown /f $cfgDir /r /d y 2>$null | Out-Null
  icacls $cfgDir /reset /t /c 2>$null | Out-Null
  Remove-Item -Path $cfgDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "AppBlocker helper removed, admin lockdown reverted, and all protections released."
