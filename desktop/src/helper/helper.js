// AppBlocker privileged helper.
//
// Runs as a background service (Windows Scheduled Task as SYSTEM) or launchd
// daemon (macOS root). Every few seconds it reads the shared config, works out
// which block-list sites should be blocked right now, and re-writes its small
// managed hosts section. It also keeps the adult filter's browser policies in
// place. Because it runs elevated and restarts on kill, blocking survives the
// UI being closed or the hosts file being hand-edited.
//
// It intentionally does NOT kill apps - that's the UI's job while it's open.
const fs = require('fs');
const path = require('path');
const { configFile } = require('../shared/paths');
const { listActive } = require('../shared/schedule');
const safedns = require('../shared/safedns');
const sites = require('../shared/sites');
const hosts = require('../main/hosts');
const bitlocker = require('../main/bitlocker');
const selfdefense = require('./selfdefense');
const frozen = require('./frozen');
const accounts = require('./accounts');

const CONFIG_DIR = path.dirname(configFile());

let lastBitlocker = 0;

function readConfig() {
  try { return JSON.parse(fs.readFileSync(configFile(), 'utf8')); }
  catch { return null; }
}

function desiredDomains(cfg) {
  const out = [];
  for (const l of (cfg.lists || [])) {
    if (!listActive(l)) continue;
    // Skip sites with a Website Exception (extension enforces those).
    for (const w of (l.websites || [])) {
      if (!sites.websiteHasException(w, l.exceptions)) out.push(w);
    }
  }
  return [...new Set(out)];
}

function tick() {
  try {
    const cfg = readConfig();
    // Admin-lockdown worker + heartbeat. Runs even when cfg is briefly null
    // (it treats that as locked, so it never leaks the password mid-write).
    accounts.tick(cfg, CONFIG_DIR).catch(() => {});
    if (!cfg) return; // config briefly unreadable (mid-write) - keep what's there
    hosts.applyDomains(desiredDomains(cfg));
    safedns.sync(!!cfg.adultFilter);
    // Self-defense (Windows): Safe-Mode lockout + install-dir ACLs while locked,
    // auto-released in the weekly buffer. Never touches WinRE/boot. Fail-open.
    selfdefense.reconcile(cfg);
    // Scheduled Sign-Out: sign out the chosen Windows account during its schedule
    // (lifted during the weekly window).
    frozen.reconcile(cfg);
    // Refresh BitLocker status (we're elevated; the standard-user app can't read
    // it) into a shared file the app reads. Throttled to once a minute.
    if (process.platform === 'win32' && Date.now() - lastBitlocker > 60000) {
      lastBitlocker = Date.now();
      try { bitlocker.refreshStatusFile(); } catch (e) {}
    }
  } catch (e) {
    // keep the loop alive no matter what
  }
}

console.log('AppBlocker helper started');
tick();
setInterval(tick, 3000);
