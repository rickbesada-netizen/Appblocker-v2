const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { Store } = require('./store');
const { Engine } = require('./engine');
const strict = require('./strict');
const apps = require('./apps');
const lock = require('../shared/lock');
const guard = require('../shared/scheduleGuard');
const { rangeActive } = require('../shared/schedule');
const weekly = require('../shared/weekly');
const extserver = require('./extserver');
const browserwatch = require('./browserwatch');
const policy = require('./policy');
const bitlocker = require('./bitlocker');
const sync = require('./sync');

// Only ever run ONE instance. A second launch just focuses the first window,
// instead of a second copy fighting over the hosts file (causes EBUSY).
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
  });
}

let win = null;
let tray = null;
let store = null;
let engine = null;

// One central Strict Mode governs everything on this device.
function anyLockOn() {
  return lock.isOn(store.get().strict);
}

// Names of what's currently locked, for error messages.
function lockBlockers() {
  return lock.isOn(store.get().strict) ? ['Strict Mode (Strict Mode tab)'] : [];
}

// ---- Cloud sync helpers (block definitions + remote Strict commands) ----
let applyingRemote = false;
let lastPushedJson = '';

// The slice of config that syncs across devices: the BLOCKS themselves, not the
// per-device lock state (each device arms Strict itself).
function syncableConfig(s) {
  return {
    lists: (s.lists || []).map(l => ({
      id: l.id, name: l.name, websites: l.websites || [], apps: l.apps || [],
      exceptions: l.exceptions || [], scheduleEnabled: !!l.scheduleEnabled, ranges: l.ranges || []
    })),
    adultFilter: !!s.adultFilter,
    webFilter: { enabled: !!(s.webFilter && s.webFilter.enabled), allowedSites: (s.webFilter && s.webFilter.allowedSites) || [] }
  };
}

function applyRemoteConfig(data) {
  if (!data) return;
  // Safety: never let a remote edit weaken a device that is currently locked.
  if (anyLockOn()) return;
  const s = store.get();
  const byId = new Map((s.lists || []).map(l => [l.id, l]));
  s.lists = (data.lists || []).map(rl => {
    const o = byId.get(rl.id) || {};
    return {
      id: rl.id, name: rl.name, websites: rl.websites || [], apps: rl.apps || [],
      exceptions: rl.exceptions || [], scheduleEnabled: !!rl.scheduleEnabled, ranges: rl.ranges || [],
      enabled: (o.enabled !== undefined) ? o.enabled : true,
      strict: lock.normalize(o.strict)
    };
  });
  if (typeof data.adultFilter === 'boolean') s.adultFilter = data.adultFilter;
  if (data.webFilter) s.webFilter = { enabled: !!data.webFilter.enabled, allowedSites: data.webFilter.allowedSites || [] };
  applyingRemote = true;
  store.set(s); try { engine.tick(); } catch (e) {}
  applyingRemote = false;
  lastPushedJson = JSON.stringify(syncableConfig(store.get()));
  if (win && !win.isDestroyed()) win.webContents.send('cloud:changed');
}

// A command from another device (e.g. your phone telling this laptop to lock).
function handleCommand(c) {
  if (!c || !c.type) return;
  const s = store.get();
  if (c.type === 'strict_on' || c.type === 'lock_all') {
    if (!lock.isOn(s.strict)) { s.strict = lock.activate(s.strict, {}); store.set(s); try { engine.tick(); } catch (e) {} }
  } else if (c.type === 'strict_off') {
    // Still obeys this device's unlock method - remote can't bypass a real lock.
    const res = lock.attemptUnlock(s.strict, {}); s.strict = res.lock; store.set(s); try { engine.tick(); } catch (e) {}
  }
  if (win && !win.isDestroyed()) win.webContents.send('cloud:changed');
}

// Is a particular list locked (its own strict, or the global strict)?
function listLocked(list) {
  // Central Strict Mode locks every list.
  return lock.isOn(store.get().strict);
}

// Would `after` block less than `before`? (add-only guard for locked lists.)
function weakens(before, after) {
  if (!before) return false;
  const inAll = (a, b) => (a || []).every(x => (b || []).includes(x));
  if (!inAll(before.apps, after.apps)) return true;
  if (!inAll(before.websites, after.websites)) return true;
  // Adding a Website Exception loosens the block, so it counts as weakening
  // (can't add exceptions while the list is locked).
  const exBefore = new Set(before.exceptions || []);
  if ((after.exceptions || []).some(x => !exBefore.has(x))) return true;
  if (!before.scheduleEnabled && after.scheduleEnabled) return true; // all-day -> scheduled = weaker
  if (before.scheduleEnabled && after.scheduleEnabled) {
    // Every day/half-hour the old config blocked must still be blocked.
    // Build a clean date for each (weekday, minute) from a fixed Sunday.
    const sunday = new Date();
    sunday.setHours(0, 0, 0, 0);
    sunday.setDate(sunday.getDate() - sunday.getDay()); // this week's Sunday
    for (let day = 0; day < 7; day++) {
      for (let mins = 0; mins < 1440; mins += 30) {
        const d = new Date(sunday);
        d.setDate(sunday.getDate() + day);
        d.setHours(Math.floor(mins / 60), mins % 60, 0, 0);
        const beforeOn = (before.ranges || []).some(r => rangeActive(r, d));
        const afterOn = (after.ranges || []).some(r => rangeActive(r, d));
        if (beforeOn && !afterOn) return true;
      }
    }
  }
  return false;
}

// Keep the companion extension force-installed in every Chromium browser, and
// re-assert it so deleting the policy only lasts until the next check. The
// extension does nothing unless there's something to block, so it's safe to
// keep installed always.
function ensureExtension() {
  // Single source of truth: install while enabled, actively remove while not.
  // Re-asserted every 10s, so a user can't delete the policy by hand - it only
  // comes off via the in-app toggle (which is itself locked while any lock is on).
  try { policy.apply(store.get().extensionEnabled !== false); } catch {}
}
function applyWebPolicy() { ensureExtension(); }

function createWindow() {
  win = new BrowserWindow({
    width: 980, height: 740, minWidth: 820, minHeight: 580,
    backgroundColor: '#0A0A12', title: 'AppBlocker',
    icon: path.join(__dirname, '..', '..', 'build',
      process.platform === 'win32' ? 'icon.ico' : 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false
    }
  });
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.on('close', (e) => {
    if (anyLockOn() && !app.isQuitting) { e.preventDefault(); win.hide(); }
  });
}

function buildTray() {
  const img = nativeImage.createFromPath(
    path.join(__dirname, '..', '..', 'build', process.platform === 'win32' ? 'tray.png' : 'trayTemplate.png')
  );
  tray = new Tray(img.isEmpty() ? nativeImage.createEmpty() : img);
  tray.setToolTip('AppBlocker');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open AppBlocker', click: () => win.show() },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        if (anyLockOn()) { win.show(); return; } // locked -> can't just quit
        app.isQuitting = true; app.quit();
      }
    }
  ]));
  tray.on('click', () => win.show());
}

if (gotLock) app.whenReady().then(() => {
  store = new Store();
  engine = new Engine(store);
  engine.start();
  extserver.start(store);
  browserwatch.start(store);
  ensureExtension();

  // Optional cloud sync (no-op unless a Supabase project is configured).
  try { sync.init(store, { onRemoteConfig: applyRemoteConfig, onCommand: handleCommand }, app.getPath('userData')); } catch (e) {}
  setInterval(() => {
    try {
      if (!sync.configured() || applyingRemote) return;
      const j = JSON.stringify(syncableConfig(store.get()));
      if (j !== lastPushedJson) { lastPushedJson = j; sync.pushConfig(JSON.parse(j)); }
    } catch (e) {}
  }, 5000);
  setInterval(() => {
    ensureExtension(); // re-assert the force-install policy every 10s
    // Remember the first time the extension connects (unlocks hard enforcement).
    if (extserver.isFresh(180000) && !store.get().extEverConnected) {
      const s = store.get(); s.extEverConnected = true; store.set(s);
    }
  }, 10000);

  createWindow();
  buildTray();
  app.setLoginItemSettings({ openAtLogin: store.get().launchOnStartup !== false });

  // ---------- IPC ----------
  ipcMain.handle('state:get', () => ({
    ...store.get(),
    lastError: engine.lastError,
    extConnected: extserver.isFresh(180000), // is any browser extension alive?
    extBeats: extserver.beatAges()          // per-browser: ms since last heartbeat
  }));

  ipcMain.handle('settings:enforcement', (_e, on) => {
    // Can't turn OFF browser enforcement while the global Strict Mode is on.
    if (!on && lock.isOn(store.get().strict)) return { error: 'Locked by Strict Mode' };
    const s = store.get(); s.strictBrowserEnforcement = !!on; store.set(s);
    return store.get();
  });

  // The ONLY sanctioned way to remove the browser extension. Turning it off
  // lifts the force-install policy (so the browser lets it be removed); turning
  // it on reinstalls it. Removal is blocked while anything is locked, so it can
  // only happen in the weekly buffer window - never as a casual bypass.
  ipcMain.handle('extension:setEnabled', (_e, on) => {
    if (!on && anyLockOn()) {
      const who = lockBlockers();
      return { error: 'Still locked by: ' + who.join('; ') +
        '.\n\nTurn these off first, then you can remove the extension. (This is the weekly-buffer rule — removal is only allowed when every lock is off.)' };
    }
    const s = store.get(); s.extensionEnabled = !!on; store.set(s);
    ensureExtension(); // apply immediately (install or remove now)
    return store.get();
  });

  // ----- Lists -----
  ipcMain.handle('lists:save', (_e, list) => {
    const s = store.get();
    const prev = (s.lists || []).find(l => l.id === list.id);
    // Add-only while locked.
    if (prev && listLocked(prev) && weakens(prev, list)) {
      return { error: 'Locked: you can only add to this list, not remove from it' };
    }
    // Schedule 24/7 guard.
    const err = guard.scheduleError(list.scheduleEnabled, list.ranges);
    if (err) return { error: err };
    // Preserve the stored strict lock (it's managed via its own IPC).
    list.strict = lock.normalize(prev ? prev.strict : list.strict);
    const i = (s.lists || []).findIndex(l => l.id === list.id);
    if (i >= 0) s.lists[i] = list; else (s.lists = s.lists || []).push(list);
    store.set(s); engine.tick();
    return store.get();
  });

  ipcMain.handle('lists:delete', (_e, id) => {
    const l = (store.get().lists || []).find(x => x.id === id);
    if (l && listLocked(l)) return { error: 'Locked by Strict Mode' };
    const s = store.get();
    s.lists = (s.lists || []).filter(x => x.id !== id);
    store.set(s); engine.tick();
    return store.get();
  });

  ipcMain.handle('lists:toggle', (_e, { id, enabled }) => {
    const l = (store.get().lists || []).find(x => x.id === id);
    if (!enabled && l && listLocked(l)) return { error: 'Locked by Strict Mode' };
    const s = store.get();
    const t = (s.lists || []).find(x => x.id === id);
    if (t) t.enabled = enabled;
    store.set(s); engine.tick();
    return store.get();
  });

  // Per-list strict: configure unlock methods (only while not locked).
  ipcMain.handle('list:unlock:config', (_e, { id, config }) => {
    const s = store.get();
    const l = (s.lists || []).find(x => x.id === id);
    if (!l) return { error: 'No such list' };
    if (lock.isOn(l.strict)) return { error: 'Unlock methods are locked while this list is locked' };
    const err = configError(config);
    if (err) return { error: err };
    l.strict = applyConfig(l.strict, config);
    store.set(s); return store.get();
  });

  ipcMain.handle('list:strict:activate', (_e, { id }) => {
    if (!store.get().consentAccepted) return { error: 'Please accept the setup disclosure first.' };
    const s = store.get();
    const l = (s.lists || []).find(x => x.id === id);
    if (!l) return { error: 'No such list' };
    l.strict = lock.activate(l.strict, {});
    store.set(s); return store.get();
  });

  ipcMain.handle('list:strict:deactivate', (_e, { id, password }) => {
    const s = store.get();
    const l = (s.lists || []).find(x => x.id === id);
    if (!l) return { error: 'No such list' };
    const res = lock.attemptUnlock(l.strict, { password });
    l.strict = res.lock; store.set(s);
    return { ok: res.ok, reason: res.ok ? undefined : lock.reasonText(res), unlockAt: res.unlockAt, state: store.get() };
  });

  // ----- Running processes -----
  ipcMain.handle('processes:list', async () => apps.listProcesses());

  // ----- Global Strict Mode -----
  ipcMain.handle('strict:activate', (_e, opts) => {
    if (!store.get().consentAccepted) return { error: 'Please accept the setup disclosure first.' };
    const err = opts && opts.methods && opts.methods.includes('schedule')
      ? guard.scheduleError(true, opts.ranges) : null;
    if (err) return { error: err };
    return strict.activate(store, opts || {});
  });
  ipcMain.handle('strict:deactivate', (_e, password) => {
    // The weekly window, if on, is the ONLY time Strict Mode can be turned off.
    const w = store.get().weeklyWindow;
    if (w && w.enabled && !weekly.withinWeeklyWindow(w)) {
      const at = weekly.nextWindowStart(w);
      return { ok: false, reason: 'Locked until your weekly window — opens ' + at.toLocaleString(), state: store.get() };
    }
    const r = strict.tryDeactivate(store, password);
    return { ...r, state: store.get() };
  });

  // Weekly unlock window (the one time per week things can be turned off).
  ipcMain.handle('weeklywindow:set', (_e, win) => {
    // Can't change the window while Strict Mode is locked outside the window.
    const cur = store.get();
    if (lock.isOn(cur.strict) && cur.weeklyWindow && cur.weeklyWindow.enabled
        && !weekly.withinWeeklyWindow(cur.weeklyWindow)) {
      return { error: 'You can only change the weekly window during the window itself.' };
    }
    const s = store.get();
    s.weeklyWindow = {
      enabled: !!(win && win.enabled),
      day: Math.max(0, Math.min(6, Number(win && win.day) || 0)),
      start: (win && win.start) || '03:00',
      minutes: Math.max(1, Number(win && win.minutes) || 1)
    };
    store.set(s);
    return store.get();
  });

  // Frozen Turkey: freeze specific Windows accounts on a schedule.
  ipcMain.handle('frozen:set', (_e, accounts) => {
    // Add-only while Strict is locked outside the window (can't weaken a freeze).
    const cur = store.get();
    const locked = lock.isOn(cur.strict) && cur.weeklyWindow && cur.weeklyWindow.enabled
      && !weekly.withinWeeklyWindow(cur.weeklyWindow);
    if (locked) return { error: 'Frozen accounts can only be changed during your weekly window.' };
    const s = store.get();
    s.frozenAccounts = (accounts || []).map(a => ({
      username: String(a.username || '').trim(),
      enabled: a.enabled !== false,
      ranges: Array.isArray(a.ranges) ? a.ranges : []
    })).filter(a => a.username);
    store.set(s);
    return store.get();
  });
  // List local Windows user accounts, to pick who to freeze.
  ipcMain.handle('os:listUsers', () => new Promise((res) => {
    if (process.platform !== 'win32') return res([]);
    try {
      require('child_process').execFile('powershell', ['-NoProfile', '-Command',
        "Get-LocalUser | Where-Object {$_.Enabled} | Select-Object -ExpandProperty Name"],
        (err, out) => res(err ? [] : String(out).split(/\r?\n/).map(s => s.trim()).filter(Boolean)));
    } catch (e) { res([]); }
  }));
  // Persist the global Strict Mode's unlock method BEFORE it's activated, so the
  // choice survives (it used to live only in the window and got wiped on refresh,
  // which is why Strict Mode activated with "no unlock method").
  ipcMain.handle('strict:unlock:config', (_e, config) => {
    if (lock.isOn(store.get().strict)) return { error: 'Unlock methods are locked while Strict Mode is on' };
    const err = configError(config);
    if (err) return { error: err };
    const s = store.get();
    s.strict = applyConfig(s.strict, config); // sets methods/password/etc; stays disabled
    store.set(s);
    return store.get();
  });

  // ----- Adult filter (hosts) + its own strict -----
  ipcMain.handle('adult:set', (_e, on) => {
    if (!on && lock.isOn(store.get().strict)) return { error: 'Locked by Strict Mode - turn it off first (Strict Mode tab).' };
    const s = store.get(); s.adultFilter = !!on; store.set(s); engine.tick();
    return store.get();
  });

  ipcMain.handle('adult:strict:activate', (_e) => {
    if (!store.get().consentAccepted) return { error: 'Please accept the setup disclosure first.' };
    const s = store.get(); s.adultStrict = lock.activate(s.adultStrict, {}); store.set(s);
    return store.get();
  });
  ipcMain.handle('adult:strict:deactivate', (_e, password) => {
    const s = store.get();
    const res = lock.attemptUnlock(s.adultStrict, { password });
    s.adultStrict = res.lock; store.set(s);
    return { ok: res.ok, reason: res.ok ? undefined : lock.reasonText(res), unlockAt: res.unlockAt, state: store.get() };
  });
  ipcMain.handle('adult:unlock:config', (_e, config) => {
    const s = store.get();
    if (lock.isOn(s.adultStrict)) return { error: 'Unlock methods are locked while the filter is locked' };
    const err = configError(config);
    if (err) return { error: err };
    s.adultStrict = applyConfig(s.adultStrict, config); store.set(s);
    return store.get();
  });

  // ----- Web allow-list filter -----
  ipcMain.handle('webfilter:set', (_e, { enabled, allowedSites }) => {
    const s = store.get();
    const locked = lock.isOn(s.strict);
    const cur = s.webFilter || { enabled: false, allowedSites: [] };
    if (locked) {
      // Add-only while locked: can't turn off, can't add new allowed sites.
      if (enabled === false && cur.enabled) return { error: 'Locked by Strict Mode - turn it off first (Strict Mode tab).' };
      const curSet = new Set(cur.allowedSites || []);
      const added = (allowedSites || []).some(x => !curSet.has(x));
      if (added) return { error: 'Locked: you can remove allowed sites, but not add new ones' };
    }
    s.webFilter = {
      enabled: enabled === undefined ? cur.enabled : !!enabled,
      allowedSites: allowedSites === undefined ? (cur.allowedSites || []) : allowedSites
    };
    store.set(s);
    applyWebPolicy();
    return store.get();
  });

  // Drive encryption (BitLocker) — detect + guide only.
  ipcMain.handle('bitlocker:get', () => new Promise(res => bitlocker.status(res)));
  ipcMain.handle('bitlocker:open', () => { bitlocker.openSettings(); return true; });

  // Recovery-key vault: save it so it can't be lost; reveal it only when the
  // global Strict Mode is OFF (so it can't be used to wipe the app mid-lock).
  ipcMain.handle('bitlocker:setKey', (_e, key) => {
    const s = store.get(); s.bitlockerKey = String(key || '').trim(); store.set(s);
    return { ok: true, has: !!s.bitlockerKey };
  });
  const keyPdfPath = () => path.join(app.getPath('userData'), 'recovery-key.pdf');
  ipcMain.handle('bitlocker:getKey', () => {
    const s = store.get();
    const has = !!(s.bitlockerKey && s.bitlockerKey.length);
    const hasPdf = (() => { try { return fs.existsSync(keyPdfPath()); } catch (e) { return false; } })();
    if (lock.isOn(s.strict)) return { has, hasPdf, locked: true };
    return { has, hasPdf, locked: false, key: s.bitlockerKey || '' };
  });
  // Upload a recovery-key PDF (e.g. "BitLocker Recovery Key.pdf") into the vault.
  ipcMain.handle('bitlocker:uploadKeyPdf', async () => {
    try {
      const r = await dialog.showOpenDialog(win, {
        title: 'Choose your BitLocker recovery-key file',
        filters: [{ name: 'Recovery key', extensions: ['pdf', 'txt'] }],
        properties: ['openFile']
      });
      if (r.canceled || !r.filePaths[0]) return { ok: false };
      const src = r.filePaths[0];
      const dst = src.toLowerCase().endsWith('.txt')
        ? path.join(app.getPath('userData'), 'recovery-key.txt')
        : keyPdfPath();
      fs.copyFileSync(src, dst);
      return { ok: true };
    } catch (e) { return { ok: false, error: String(e && e.message || e) }; }
  });
  // Open the saved recovery-key file - only when Strict Mode is OFF.
  ipcMain.handle('bitlocker:openKeyPdf', () => {
    if (lock.isOn(store.get().strict)) return { locked: true };
    const pdf = keyPdfPath();
    const txt = path.join(app.getPath('userData'), 'recovery-key.txt');
    const f = fs.existsSync(pdf) ? pdf : (fs.existsSync(txt) ? txt : '');
    if (!f) return { ok: false, error: 'No file saved' };
    shell.openPath(f);
    return { ok: true };
  });

  // ----- Cloud account + sync + remote control -----
  ipcMain.handle('cloud:status', () => sync.status());
  ipcMain.handle('cloud:signin', (_e, { email, password }) => sync.signIn(email, password));
  ipcMain.handle('cloud:signup', (_e, { email, password }) => sync.signUp(email, password));
  ipcMain.handle('cloud:signout', () => sync.signOut());
  ipcMain.handle('cloud:devices', () => sync.listDevices());
  ipcMain.handle('cloud:send', (_e, { type, target }) => sync.sendCommand(type, target));
  ipcMain.handle('cloud:setkeys', (_e, { url, anonKey }) => {
    const s = store.get(); s.cloudUrl = (url || '').trim(); s.cloudAnonKey = (anonKey || '').trim(); store.set(s);
    return { ok: true, restart: true };
  });

  // Admin-password vault - same gating as the recovery key (shown only when
  // Strict Mode is off), so it can't be used to elevate and kill the app mid-lock.
  ipcMain.handle('admin:setPassword', (_e, pw) => {
    const s = store.get(); s.adminPassword = String(pw || ''); store.set(s);
    return { ok: true, has: !!s.adminPassword };
  });
  ipcMain.handle('admin:getPassword', () => {
    const s = store.get();
    const has = !!(s.adminPassword && s.adminPassword.length);
    if (lock.isOn(s.strict)) return { has, locked: true };
    return { has, locked: false, pw: s.adminPassword || '' };
  });

  // Is the current Windows user a local Administrator? (Drives the setup advice.)
  ipcMain.handle('os:isAdmin', () => new Promise((res) => {
    if (process.platform !== 'win32') return res({ admin: false, supported: false });
    try {
      require('child_process').execFile('net', ['session'], (err) => res({ admin: !err, supported: true }));
    } catch (e) { res({ admin: false, supported: true }); }
  }));

  // First-run disclosure / consent.
  ipcMain.handle('consent:accept', () => {
    const s = store.get(); s.consentAccepted = true; store.set(s); return store.get();
  });
  // Open Windows' "Other users" settings so the user can make a Standard account.
  ipcMain.handle('os:openUserSettings', () => {
    if (process.platform === 'win32') require('child_process').execFile('cmd', ['/c', 'start', 'ms-settings:otherusers'], { windowsHide: true }, () => {});
    return true;
  });

  ipcMain.handle('settings:startup', (_e, on) => {
    const s = store.get(); s.launchOnStartup = !!on; store.set(s);
    app.setLoginItemSettings({ openAtLogin: !!on });
    return store.get();
  });
});

// Shared helpers for unlock-method config coming from the renderer.
function configError(config) {
  if (!config) return null;
  const methods = config.methods || [];
  if (methods.includes('schedule')) {
    const active = (config.ranges || []).filter(r => (r.days || []).length > 0);
    if (!active.length) {
      return 'Add at least one weekly time window for "follow schedules" - with none set, the lock could never be turned off.';
    }
    const err = guard.scheduleError(true, config.ranges || []);
    if (err) return err;
  }
  return null;
}
function applyConfig(current, config) {
  const l = lock.normalize(current);
  l.methods = config.methods || [];
  l.waitMinutes = Number(config.waitMinutes) || 0;
  l.cooldownSeconds = Number(config.cooldownSeconds) || 0;
  l.ranges = config.ranges || [];
  if (config.password) l.passwordHash = lock.hash(config.password);
  if (config.clearPassword) l.passwordHash = '';
  return l;
}

app.on('window-all-closed', () => { /* stay alive in tray so blocking continues */ });

app.on('before-quit', () => {
  // Leave blocks in place while anything is locked; otherwise tidy up.
  if (!anyLockOn()) engine && engine.clearAll();
});
