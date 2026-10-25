# Privileged account operations for AppBlocker's "admin lockdown" (Option A).
# Called ONLY from an elevated context: the SYSTEM helper, or the one-time
# elevated setup. It never runs from the standard-user app.
#
# What it does (all idempotent, all fail-safe):
#   * lockdown : create/update a sealed Administrator account, seal its password
#                in a SYSTEM-only file, grant standard users access to the normal
#                config dir, then remove the daily account from Administrators -
#                but NEVER if that would leave the machine with no administrator.
#   * setpw    : change the sealed admin account's password (re-seal it).
#   * promote  : put an account back into Administrators (recovery / uninstall).
#
# The password is passed via the ABK_PW environment variable, never on the
# command line, so it isn't visible in the process list.
param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$Account = "AppBlockerAdmin",
  [string]$DemoteUser = "",
  [string]$DemoteSid = "",
  [string]$User = "",
  [string]$ConfigDir = ""
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_admin.ps1")
Assert-Admin

# Built-in Administrators group, resolved by SID so it works on any locale.
function Get-AdminGroupName { (Get-LocalGroup -SID "S-1-5-32-544").Name }

function Get-PwSecure {
  $p = $env:ABK_PW
  if ([string]::IsNullOrEmpty($p)) { throw "no password supplied" }
  return (ConvertTo-SecureString $p -AsPlainText -Force)
}

function Is-Member([string]$group, [string]$name) {
  try { return [bool](Get-LocalGroupMember -Group $group -Member $name -ErrorAction Stop) }
  catch { return $false }
}

function Seal-Secret {
  param([string]$dir, [string]$account)
  if ($dir -eq "") { return }
  $secureDir = Join-Path $dir "secure"
  New-Item -ItemType Directory -Force -Path $secureDir | Out-Null
  # Lock the secure folder down: only SYSTEM + Administrators, no inheritance,
  # so a standard user can't read the sealed password.
  & icacls $secureDir /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" | Out-Null
  $payload = @{ account = $account; password = $env:ABK_PW } | ConvertTo-Json -Compress
  Set-Content -Path (Join-Path $secureDir "secret.json") -Value $payload -Encoding UTF8 -Force
}

switch ($Action) {

  "lockdown" {
    $sec = Get-PwSecure
    $adminGroup = Get-AdminGroupName

    # 1. Create or update the sealed admin account.
    if (Get-LocalUser -Name $Account -ErrorAction SilentlyContinue) {
      Set-LocalUser -Name $Account -Password $sec -PasswordNeverExpires $true
    } else {
      New-LocalUser -Name $Account -Password $sec -PasswordNeverExpires -AccountNeverExpires `
        -FullName "AppBlocker Admin" -Description "Sealed administrator managed by AppBlocker" | Out-Null
    }

    # 2. Make sure it is an administrator.
    if (-not (Is-Member $adminGroup $Account)) { Add-LocalGroupMember -Group $adminGroup -Member $Account }

    # 3. Seal the password where standard users can't read it, and make sure the
    #    standard user can still read/write the ordinary config dir (blocks, queue).
    Seal-Secret -dir $ConfigDir -account $Account
    if ($ConfigDir -ne "") { & icacls $ConfigDir /grant:r "*S-1-5-32-545:(OI)(CI)M" | Out-Null }  # Users: Modify

    # 4. Demote the daily account - but only once the sealed admin really is an
    #    administrator, so we can never strand the PC with zero admins. Prefer the
    #    SID (matches Microsoft accounts too), fall back to the username.
    if ((Is-Member $adminGroup $Account) -and ($DemoteUser -ne $Account)) {
      $target = $null
      if ($DemoteSid -ne "") { try { $target = [System.Security.Principal.SecurityIdentifier]$DemoteSid } catch { $target = $null } }
      if ($null -eq $target -and $DemoteUser -ne "") { $target = $DemoteUser }
      if ($null -ne $target) {
        try { Remove-LocalGroupMember -Group $adminGroup -Member $target -ErrorAction Stop } catch {}
      }
    }
    Write-Output "ok"
  }

  "setpw" {
    $sec = Get-PwSecure
    if (-not (Get-LocalUser -Name $Account -ErrorAction SilentlyContinue)) { throw "account $Account does not exist" }
    Set-LocalUser -Name $Account -Password $sec -PasswordNeverExpires $true
    Seal-Secret -dir $ConfigDir -account $Account
    Write-Output "ok"
  }

  "promote" {
    $adminGroup = Get-AdminGroupName
    # Prefer the SID (passed as -DemoteSid), fall back to the username.
    $target = $null
    if ($DemoteSid -ne "") { try { $target = [System.Security.Principal.SecurityIdentifier]$DemoteSid } catch { $target = $null } }
    if ($null -eq $target -and $User -ne "") { $target = $User }
    if ($null -ne $target) {
      try { if (-not (Is-Member $adminGroup $target)) { Add-LocalGroupMember -Group $adminGroup -Member $target } } catch {}
    }
    Write-Output "ok"
  }

  default { throw "unknown action: $Action" }
}
