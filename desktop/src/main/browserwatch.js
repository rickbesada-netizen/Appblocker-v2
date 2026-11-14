// Enforces the web allow-list at the process level, the "Cold Turkey" way:
//
//  - While the web filter is ON, any browser we CAN'T protect with the
//    companion extension (Firefox, Safari, Opera, Brave, Vivaldi, ...) is
//    closed on sight - it would bypass the allow-list.
//  - A browser we DO protect (Chrome, Edge) is left alone while the extension
//    is alive (fresh heartbeats). If the extension is removed or disabled the
//    heartbeats stop, and after a short grace period we close that browser.
//    On next launch the force-install policy reinstalls the extension.
//
// Honest limit: a browser launched with policies/extensions forcibly stripped,
// or a portable/unknown browser, can still bypass until it's closed on the next
// tick. See README.
const apps = require('./apps');
const extserver = require('./extserver');
const { listActive } = require('../shared/schedule');

// The extension heartbeats every ~30s (alarm wakes the worker even when idle),
// plus on every navigation. Tolerate a couple of missed beats before acting.
const GRACE_MS = 150000;      // a NEVER-seen browser gets time to AUTO-INSTALL the extension
const QUICK_GRACE_MS = 45000; // a browser that HAD the extension and lost it is closed after this
// A browser's heartbeat older than this = its extension is really gone. Set high
// on purpose: Brave/Chrome MV3 service workers sleep when the browser is idle and
// stop beating for a while, so a short window would close a browser that still
// HAS the extension. Better to wait than to close a browser you're using.
const STALE_MS = 180000;      // 3 minutes

// Map a running browser process to the id its extension reports in heartbeats.
const WIN_ID = {
  'chrome.exe': 'chrome', 'msedge.exe': 'edge', 'brave.exe': 'brave',
  'vivaldi.exe': 'vivaldi', 'opera.exe': 'opera', 'opera_gx.exe': 'opera',
  'chromium.exe': 'chrome', 'firefox.exe': 'firefox'
};
const MAC_ID = {
  'google chrome': 'chrome', 'microsoft edge': 'edge', 'brave browser': 'brave',
  'vivaldi': 'vivaldi', 'opera': 'opera', 'chromium': 'chrome', 'firefox': 'firefox'
};
function idOf(b) {
  const k = String(b).toLowerCase();
  return (process.platform === 'win32' ? WIN_ID : MAC_ID)[k] || k;
}
// Browser ids that have proven the extension can run there (so re-closing fast is safe).
const seenFreshIds = new Set();

// NOTE on Trickster (the antidetect browser): it bundles and force-loads the
// SAME AppBlocker extension, enforces it with its own tamper monitor, and that
// extension reads the allow-list from our local server (127.0.0.1:47113) - so
// Trickster is already in sync. It is deliberately in NEITHER list below, so we
// never close it: it protects itself, and closing it would kill the user's
// antidetect profiles mid-session.

// Protectable = Chromium-based browsers that CAN run the companion extension
// (so they're left alone while the extension is alive). Unprotectable = can't
// run it, so they're closed whenever web blocking is active.
const BROWSERS = process.platform === 'win32'
  ? {
      protectable: ['chrome.exe', 'msedge.exe', 'brave.exe', 'vivaldi.exe',
        'opera.exe', 'opera_gx.exe', 'chromium.exe', 'firefox.exe'],
      unprotectable: ['librewolf.exe', 'waterfox.exe', 'tor.exe', 'iexplore.exe']
    }
  : {
      protectable: ['Google Chrome', 'Microsoft Edge', 'Brave Browser', 'Vivaldi', 'Opera', 'Chromium', 'firefox'],
      unprotectable: ['LibreWolf', 'Waterfox', 'Tor Browser', 'Safari']
    };

// Web blocking is "active" (so browsers can bypass and must be watched) when the
// allow-list is on, OR any currently-active block list has websites.
function enforcementActive(cfg) {
  if (cfg.strictBrowserEnforcement === false) return false;
  if (cfg.webFilter && cfg.webFilter.enabled) return true;
  return (cfg.lists || []).some(l => listActive(l) && (l.websites || []).length > 0);
}

const firstSeen = new Map(); // browser name -> first time we saw it running while protected-but-stale

let timer = null;

function start(store) {
  stop();
  tick(store);
  timer = setInterval(() => tick(store), 4000);
}

function stop() {
  if (timer) clearInterval(timer);
  timer = null;
}

async function tick(store) {
  const cfg = store.get();
  if (!enforcementActive(cfg)) { firstSeen.clear(); return; }
  // Don't close anything until the extension has connected at least once -
  // otherwise, if auto-install is blocked by the browser, you'd be trapped in a
  // loop with no way to load it. Once it's proven to work here, enforce hard.
  if (!cfg.extEverConnected) { firstSeen.clear(); return; }

  let running;
  try { running = await apps.listProcesses(); } catch { return; }
  const isRunning = (name) => running.includes(String(name).toLowerCase());
  const now = Date.now();

  // Browsers we can't protect: close whenever the filter is on.
  for (const b of BROWSERS.unprotectable) {
    if (isRunning(b)) apps.killByName(b);
  }

  // Can the extension tell us WHICH browser it's in? Older/published builds send
  // untagged heartbeats, so we only have the global "is any extension alive" signal.
  // In that case we must NOT single out a browser for closing (we'd close one that
  // actually has the extension). Only use per-browser closing once beats are tagged.
  const taggedBeats = Object.keys(extserver.beatAges()).length > 0;
  const globalFresh = extserver.isFresh(STALE_MS);

  // Protectable browsers: a browser whose extension is live is left alone; one
  // whose beats went stale (extension removed/disabled) is closed. On relaunch the
  // force-install policy puts the extension back.
  for (const b of BROWSERS.protectable) {
    if (!isRunning(b)) { firstSeen.delete(b); continue; }
    const id = idOf(b);
    const protectedNow = taggedBeats ? extserver.isFreshFor(id, STALE_MS) : globalFresh;
    if (protectedNow) {
      seenFreshIds.add(id);
      firstSeen.delete(b);
      continue;
    }
    if (!firstSeen.has(b)) firstSeen.set(b, now);
    const grace = seenFreshIds.has(id) ? QUICK_GRACE_MS : GRACE_MS;
    if (now - firstSeen.get(b) >= grace) apps.killByName(b);
  }
}

module.exports = { start, stop };
