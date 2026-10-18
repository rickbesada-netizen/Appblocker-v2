// Self-defense, driven by the SYSTEM helper. Every protection here is:
//   * conditional on a lock being active (released automatically in the buffer), and
//   * reversible by an administrator (take-ownership) with the Windows Recovery
//     Environment left fully intact.
// It deliberately does NOT touch WinRE/boot configuration - see SELF-DEFENSE.md.
const { execFile } = require('child_process');
const path = require('path');
const lock = require('../shared/lock');

// "Locked" = the one central Strict Mode is on. In the weekly buffer the user
// turns Strict Mode off, so this goes false and every protection below is
// released on the next tick.
function anyLockOn(cfg) {
  if (!cfg) return false;
  return lock.isOn(cfg.strict);
}

const SCRIPTS = path.join(__dirname, 'scripts');
const INSTALL_DIR = path.join(process.env['ProgramFiles'] || 'C:\\Program Files', 'AppBlocker');

function ps(script, args = []) {
  return new Promise(resolve => {
    execFile('powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(SCRIPTS, script), ...args],
      { windowsHide: true, timeout: 20000 }, () => resolve());
  });
}

let busy = false, lastOn = null, lastApply = 0;

// Call this every helper tick. Throttled, idempotent, and FAIL-OPEN: any error
// leaves the machine recoverable rather than locking into a state we can't undo.
async function reconcile(cfg) {
  if (process.platform !== 'win32') return;      // Windows-only self-defense
  const on = anyLockOn(cfg);
  const now = Date.now();
  if (busy) return;
  // Re-assert every 30s even if unchanged (counters tampering); act immediately on change.
  if (on === lastOn && now - lastApply < 30000) return;
  busy = true;
  try {
    if (on) {
      await ps('safemode.ps1', ['enable']);
      await ps('acl.ps1', ['lock', INSTALL_DIR]);
    } else {
      // BUFFER / unlocked: release everything so the app is removable and the
      // machine is fully recoverable.
      await ps('safemode.ps1', ['disable']);
      await ps('acl.ps1', ['unlock', INSTALL_DIR]);
    }
    lastOn = on;
    lastApply = now;
  } catch (e) {
    // swallow: never fail closed
  } finally {
    busy = false;
  }
}

module.exports = { reconcile, anyLockOn };
