// App blocking: poll running processes and terminate any that match a blocked
// app. Cross-platform via built-in OS commands (no native deps).
const { execFile } = require('child_process');

function listProcesses() {
  return new Promise(resolve => {
    if (process.platform === 'win32') {
      // CSV: "Image Name","PID",...
      execFile('tasklist', ['/fo', 'csv', '/nh'], { maxBuffer: 1 << 24 }, (err, out) => {
        if (err) return resolve([]);
        const names = out.split(/\r?\n/).map(line => {
          const m = line.match(/^"([^"]+)"/);
          return m ? m[1].toLowerCase() : null;
        }).filter(Boolean);
        resolve(names);
      });
    } else {
      execFile('ps', ['-axco', 'comm'], { maxBuffer: 1 << 24 }, (err, out) => {
        if (err) return resolve([]);
        resolve(out.split(/\r?\n/).map(s => s.trim().toLowerCase()).filter(Boolean));
      });
    }
  });
}

function killByName(name) {
  const n = String(name).trim();
  if (!n) return;
  if (process.platform === 'win32') {
    const image = n.toLowerCase().endsWith('.exe') ? n : n + '.exe';
    execFile('taskkill', ['/IM', image, '/F', '/T'], () => {});
  } else {
    // match the process "comm" name exactly
    execFile('pkill', ['-x', n.replace(/\.app$/i, '')], () => {});
  }
}

// blockedNames: array of executable names ("instagram.exe", "Discord").
async function enforce(blockedNames) {
  if (!blockedNames.length) return;
  const running = await listProcesses();
  const wanted = blockedNames.map(s => {
    const x = s.toLowerCase();
    return process.platform === 'win32' && !x.endsWith('.exe') ? x + '.exe' : x;
  });
  for (const proc of running) {
    if (wanted.includes(proc)) killByName(proc);
  }
}

// Processes we must NEVER close (the desktop shell + AppBlocker itself),
// regardless of the allow-list. Everything else not explicitly allowed is fair
// game. (As a standard user, taskkill on system/other-user processes just fails,
// so this only ever closes the user's own apps.)
const WIN_ESSENTIALS = new Set([
  'explorer.exe', 'dwm.exe', 'sihost.exe', 'taskhostw.exe', 'ctfmon.exe', 'searchhost.exe', 'searchapp.exe',
  'startmenuexperiencehost.exe', 'shellexperiencehost.exe', 'runtimebroker.exe', 'applicationframehost.exe',
  'textinputhost.exe', 'systemsettings.exe', 'fontdrvhost.exe', 'csrss.exe', 'winlogon.exe', 'wininit.exe',
  'services.exe', 'lsass.exe', 'svchost.exe', 'smss.exe', 'conhost.exe', 'dllhost.exe', 'taskmgr.exe',
  'lockapp.exe', 'logonui.exe', 'userinit.exe', 'spoolsv.exe', 'audiodg.exe', 'wmiprvse.exe',
  'backgroundtaskhost.exe', 'securityhealthsystray.exe', 'msedgewebview2.exe',
  'appblocker.exe', 'appblocker-helper.exe', 'node.exe', 'electron.exe'
]);
const MAC_ESSENTIALS = new Set([
  'finder', 'dock', 'systemuiserver', 'windowserver', 'loginwindow', 'controlcenter', 'notificationcenter',
  'spotlight', 'coreservicesuiagent', 'universalcontrol', 'appblocker', 'electron'
]);

// App allow-list: close every running app EXCEPT the allowed ones (and the
// essentials above). allowedNames: array of executable names.
async function enforceAllowOnly(allowedNames) {
  const win = process.platform === 'win32';
  const essentials = win ? WIN_ESSENTIALS : MAC_ESSENTIALS;
  const allowed = new Set((allowedNames || []).map(s => {
    let x = String(s).trim().toLowerCase();
    if (!x) return '';
    if (win && !x.endsWith('.exe')) x += '.exe';
    if (!win) x = x.replace(/\.app$/i, '');
    return x;
  }).filter(Boolean));
  const running = await listProcesses();
  for (const proc of running) {
    if (essentials.has(proc) || allowed.has(proc)) continue;
    killByName(proc);
  }
}

module.exports = { listProcesses, enforce, enforceAllowOnly, killByName };
