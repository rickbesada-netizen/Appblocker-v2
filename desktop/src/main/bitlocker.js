// BitLocker: DETECT status and GUIDE the user to Windows' own encryption wizard.
// We never silently encrypt a drive - a lost recovery key means permanent data
// loss, so key backup must go through Windows' UI with the user's eyes open.
//
// Reading BitLocker status (Get-BitLockerVolume) needs admin. The app now runs
// as a STANDARD user, so it can't read it directly - it would wrongly show
// "OFF". Instead the SYSTEM helper refreshes the status into a shared file and
// the app reads that. A direct check is only a fallback for an elevated run.
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const { configDir } = require('../shared/paths');

function statusFile() { return path.join(configDir(), 'bitlocker-status.json'); }

// Raw PowerShell check (only succeeds when the calling process is elevated / SYSTEM).
function runCheck(cb) {
  if (process.platform !== 'win32') return cb({ supported: false });
  const script =
    "try{$v=Get-BitLockerVolume -MountPoint $env:SystemDrive;" +
    "@{supported=$true;on=($v.ProtectionStatus -eq 'On');status=[string]$v.VolumeStatus}|ConvertTo-Json -Compress}" +
    "catch{@{supported=$false}|ConvertTo-Json -Compress}";
  execFile('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script],
    { windowsHide: true, timeout: 15000 }, (err, out) => {
      if (err) return cb({ supported: false });
      try { cb(JSON.parse(String(out).trim())); } catch { cb({ supported: false }); }
    });
}

function readStatusFile() {
  try { return JSON.parse(fs.readFileSync(statusFile(), 'utf8')); } catch { return null; }
}

// Called by the SYSTEM helper on a throttle: read BitLocker status (it can,
// being elevated) and cache it where the standard-user app can read it.
function refreshStatusFile(cb) {
  runCheck((res) => {
    if (res && res.supported) {
      res.at = Date.now();
      try { fs.mkdirSync(configDir(), { recursive: true }); } catch (e) {}
      try { fs.writeFileSync(statusFile(), JSON.stringify(res)); } catch (e) {}
    }
    if (cb) cb(res);
  });
}

// What the app shows. Prefer the helper's cached status; fall back to a direct
// check (works only if elevated); otherwise report "unknown" rather than a
// misleading "OFF".
function status(cb) {
  if (process.platform !== 'win32') return cb({ supported: false });
  const cached = readStatusFile();
  if (cached && (Date.now() - (cached.at || 0) < 15 * 60 * 1000)) return cb(cached);
  runCheck((res) => {
    if (res && res.supported) return cb(res);
    if (cached) return cb(cached);                        // stale, but real
    cb({ supported: true, on: null, unknown: true });     // can't verify without the helper/admin
  });
}

// Opens the Windows BitLocker control panel so the user can turn it on and save
// their recovery key the proper way.
function openSettings() {
  if (process.platform !== 'win32') return;
  execFile('control', ['/name', 'Microsoft.BitLockerDriveEncryption'], { windowsHide: true }, () => {});
}

module.exports = { status, openSettings, refreshStatusFile };
