// Persistent config in the OS user-data dir. Plain JSON, no external deps.
const fs = require('fs');
const path = require('path');
const { configFile } = require('../shared/paths');
const { defaultLock, normalize } = require('../shared/lock');

function defaults() {
  return {
    lists: [],            // [{id,name,enabled,apps:[],websites:[],scheduleEnabled,ranges:[],strict:lock}]
    adultFilter: false,   // adult-site block via hosts (system-wide)
    adultStrict: defaultLock(),   // adult/web filter's OWN Strict Mode
    webFilter: { enabled: false, allowedSites: [] }, // browser allow-list (enforced by the extension)
    appFilter: { enabled: false, allowedApps: [] },  // app allow-list: close every app except these
    strict: defaultLock(),        // the big (global) Strict Mode, now method-based
    launchOnStartup: true,
    // Close browsers that can bypass web blocking (no extension / can't run it)
    // while any website blocking is active. The "Cold Turkey" layer.
    strictBrowserEnforcement: true,
    // Set true the first time the extension ever connects. Until then we DON'T
    // close browsers (otherwise you'd be trapped in a close-loop while setting
    // the extension up).
    extEverConnected: false,
    // First-run disclosure: the user must accept how the app protects itself
    // (Safe-Mode lockout, file protection, weekly-buffer removal window) before
    // Strict Mode can be turned on.
    consentAccepted: false,
    // The browser extension is force-installed while this is true. It's the ONLY
    // way to remove the extension (browsers give no Remove button for a
    // policy-installed extension), and it can't be turned off while anything is
    // locked - so removal is only possible in the weekly buffer window.
    extensionEnabled: true,
    // Optional: the user's BitLocker recovery key, kept so it can't be lost.
    // Hidden in the UI while Strict Mode is ON; shown only when it's off.
    bitlockerKey: '',
    // Optional: the separate Administrator account's password. Same idea - set a
    // strong one you don't memorise, stash it here; it's hidden while Strict Mode
    // is on, so you can't elevate to admin to kill the app mid-lock.
    adminPassword: '',
    // Optional cloud sync (Supabase). Blank = fully local. These are public
    // client values, safe to store. Rick can also hardcode them in sync.js.
    cloudUrl: '',
    cloudAnonKey: '',
    // The single weekly window when Strict Mode / a frozen account can be turned
    // off. Everything is locked the rest of the week. Minimum 1 minute.
    weeklyWindow: { enabled: false, day: 0, start: '03:00', minutes: 1 },
    // "Frozen Turkey" for a specific Windows account: during a freeze schedule
    // that user is signed out on sight (except during the weekly window).
    // [{ username, enabled, ranges:[{days:[0-6],start,end}] }]
    frozenAccounts: []
  };
}

class Store {
  constructor() {
    this.file = configFile();
    this.data = this._migrate(this._load());
  }

  _load() {
    try {
      return JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch {
      return defaults();
    }
  }

  // Fill in any missing fields so older configs keep working.
  _migrate(data) {
    const d = Object.assign(defaults(), data || {});
    // Old global strict was { active, waitMinutes, unlockAt, passwordHash }.
    const s = data && data.strict ? data.strict : {};
    if (s && (s.active !== undefined) && s.enabled === undefined) {
      d.strict = normalize({
        enabled: !!s.active,
        methods: [].concat(s.passwordHash ? ['password'] : [], (s.waitMinutes > 0) ? ['timer'] : []),
        passwordHash: s.passwordHash || '',
        waitMinutes: s.waitMinutes || 0,
        activatedAt: s.active ? (Date.now()) : 0
      });
    } else {
      d.strict = normalize(d.strict);
    }
    d.adultStrict = normalize(d.adultStrict);
    d.webFilter = Object.assign({ enabled: false, allowedSites: [] }, d.webFilter || {});
    if (d.strictBrowserEnforcement === undefined) d.strictBrowserEnforcement = true;
    if (d.extEverConnected === undefined) d.extEverConnected = false;
    if (d.consentAccepted === undefined) d.consentAccepted = false;
    if (d.extensionEnabled === undefined) d.extensionEnabled = true;
    if (d.bitlockerKey === undefined) d.bitlockerKey = '';
    if (d.adminPassword === undefined) d.adminPassword = '';
    if (d.cloudUrl === undefined) d.cloudUrl = '';
    if (d.cloudAnonKey === undefined) d.cloudAnonKey = '';
    if (!d.weeklyWindow) d.weeklyWindow = { enabled: false, day: 0, start: '03:00', minutes: 1 };
    if (!Array.isArray(d.frozenAccounts)) d.frozenAccounts = [];
    d.lists = (d.lists || []).map(l => Object.assign({ exceptions: [] }, l, { strict: normalize(l.strict), exceptions: l.exceptions || [] }));
    return d;
  }

  save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2));
    } catch (e) {
      console.error('store save failed', e);
    }
  }

  get() { return this.data; }
  set(next) { this.data = next; this.save(); }
}

module.exports = { Store };
