// Admin-lockdown worker, run by the SYSTEM helper (so it has the rights to
// change local accounts and to read the sealed password file that standard
// users can't).
//
// Two jobs, every helper tick:
//   1. Reveal maintenance - copy the sealed password into a user-readable
//      reveal.json ONLY while Strict Mode is off; delete it while locked.
//   2. Queue processing - run account changes the app requested, but ONLY while
//      Strict Mode is off. This is the key rule: a standard user can write to
//      the queue, so if we ran it during a lock they could re-promote
//      themselves and uninstall. Refusing while locked closes that hole.
const { execFile } = require('child_process');
const fs = require('fs');
const path = require('path');
const lock = require('../shared/lock');

const SCRIPT = path.join(__dirname, 'scripts', 'accounts.ps1');

function readJson(p) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return null; } }
function writeJson(p, o) { try { fs.writeFileSync(p, JSON.stringify(o)); return true; } catch (e) { return false; } }
function rm(p) { try { fs.unlinkSync(p); } catch (e) {} }

function run(action, args, pw) {
  return new Promise(resolve => {
    const psArgs = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', SCRIPT, '-Action', action, ...args];
    const env = Object.assign({}, process.env);
    if (pw != null) env.ABK_PW = String(pw);
    execFile('powershell.exe', psArgs, { windowsHide: true, timeout: 40000, env }, (err, stdout, stderr) => {
      resolve({ ok: !err, out: String(stdout || '').trim(), err: err ? String((stderr || err.message || '')).trim() : '' });
    });
  });
}

function refreshReveal(dir, locked) {
  const revealFile = path.join(dir, 'reveal.json');
  if (locked) { rm(revealFile); return; }
  const secret = readJson(path.join(dir, 'secure', 'secret.json'));
  if (secret && secret.password) writeJson(revealFile, { account: secret.account, password: secret.password });
  else rm(revealFile);
}

let busy = false;

// cfg may be null (config briefly unreadable) - treat that as LOCKED so we never
// leak the password or run a mutation during that window.
async function tick(cfg, dir) {
  if (process.platform !== 'win32') return;
  // Heartbeat: lets the app show whether the protection service is alive.
  writeJson(path.join(dir, 'helper-alive.json'), { t: Date.now() });

  const locked = cfg ? lock.isOn(cfg.strict) : true;
  refreshReveal(dir, locked);

  if (busy) return;
  const queueFile = path.join(dir, 'admin-queue.json');
  const q = readJson(queueFile);
  if (!Array.isArray(q) || q.length === 0) return;

  if (locked) {
    // Never change accounts during a lock.
    writeJson(path.join(dir, 'admin-result.json'), { id: q[0] && q[0].id, ok: false, err: 'Strict Mode is on - unlock first', ts: Date.now() });
    rm(queueFile);
    return;
  }

  busy = true;
  try {
    const cmd = q[0];
    const cfgArg = ['-ConfigDir', dir];
    let res;
    if (cmd.type === 'lockdown') {
      res = await run('lockdown', ['-Account', cmd.account || 'AppBlockerAdmin', '-DemoteUser', cmd.demoteUser || '', '-DemoteSid', cmd.demoteSid || '', ...cfgArg], cmd.password);
    } else if (cmd.type === 'setpw') {
      res = await run('setpw', ['-Account', cmd.account || 'AppBlockerAdmin', ...cfgArg], cmd.password);
    } else if (cmd.type === 'promote') {
      res = await run('promote', ['-User', cmd.user || '', '-DemoteSid', cmd.userSid || ''], null);
    } else {
      res = { ok: false, err: 'unknown command: ' + cmd.type };
    }
    writeJson(path.join(dir, 'admin-result.json'), { id: cmd.id, ok: res.ok, err: res.err, ts: Date.now() });
    refreshReveal(dir, false); // reflect the new sealed password right away
  } finally {
    rm(queueFile);
    busy = false;
  }
}

module.exports = { tick };
