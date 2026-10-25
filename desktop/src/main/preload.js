const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getState: () => ipcRenderer.invoke('state:get'),

  // Lists
  saveList: (list) => ipcRenderer.invoke('lists:save', list),
  deleteList: (id) => ipcRenderer.invoke('lists:delete', id),
  toggleList: (id, enabled) => ipcRenderer.invoke('lists:toggle', { id, enabled }),
  listProcesses: () => ipcRenderer.invoke('processes:list'),

  // Per-list strict + its own unlock methods
  listUnlockConfig: (id, config) => ipcRenderer.invoke('list:unlock:config', { id, config }),
  listStrictActivate: (id) => ipcRenderer.invoke('list:strict:activate', { id }),
  listStrictDeactivate: (id, password) => ipcRenderer.invoke('list:strict:deactivate', { id, password }),

  // Global strict
  activateStrict: (opts) => ipcRenderer.invoke('strict:activate', opts),
  deactivateStrict: (pw) => ipcRenderer.invoke('strict:deactivate', pw),
  strictUnlockConfig: (config) => ipcRenderer.invoke('strict:unlock:config', config),

  // Weekly unlock window + Frozen Turkey accounts
  setWeeklyWindow: (win) => ipcRenderer.invoke('weeklywindow:set', win),
  setFrozenAccounts: (accounts) => ipcRenderer.invoke('frozen:set', accounts),
  listUsers: () => ipcRenderer.invoke('os:listUsers'),

  // Adult filter + its own strict
  setAdultFilter: (on) => ipcRenderer.invoke('adult:set', on),
  adultStrictActivate: () => ipcRenderer.invoke('adult:strict:activate'),
  adultStrictDeactivate: (pw) => ipcRenderer.invoke('adult:strict:deactivate', pw),
  adultUnlockConfig: (config) => ipcRenderer.invoke('adult:unlock:config', config),

  // Web allow-list filter
  setWebFilter: (enabled, allowedSites) => ipcRenderer.invoke('webfilter:set', { enabled, allowedSites }),

  setStartup: (on) => ipcRenderer.invoke('settings:startup', on),
  setEnforcement: (on) => ipcRenderer.invoke('settings:enforcement', on),
  setExtensionEnabled: (on) => ipcRenderer.invoke('extension:setEnabled', on),

  // Drive encryption (BitLocker)
  getBitlocker: () => ipcRenderer.invoke('bitlocker:get'),
  openBitlocker: () => ipcRenderer.invoke('bitlocker:open'),
  setBitlockerKey: (key) => ipcRenderer.invoke('bitlocker:setKey', key),
  getBitlockerKey: () => ipcRenderer.invoke('bitlocker:getKey'),
  uploadKeyPdf: () => ipcRenderer.invoke('bitlocker:uploadKeyPdf'),
  openKeyPdf: () => ipcRenderer.invoke('bitlocker:openKeyPdf'),
  isAdmin: () => ipcRenderer.invoke('os:isAdmin'),

  // Admin lockdown (Option A) - sealed admin account via the SYSTEM helper
  adminInfo: () => ipcRenderer.invoke('admin:info'),
  adminLockdown: (opts) => ipcRenderer.invoke('admin:lockdown', opts),
  adminSetPw: (opts) => ipcRenderer.invoke('admin:setpw', opts),
  adminRestore: () => ipcRenderer.invoke('admin:restore'),
  adminResult: (id) => ipcRenderer.invoke('admin:result', id),

  // First-run consent + standard-user setup
  acceptConsent: () => ipcRenderer.invoke('consent:accept'),
  openUserSettings: () => ipcRenderer.invoke('os:openUserSettings'),

  // Cloud account + sync + remote control
  cloudStatus: () => ipcRenderer.invoke('cloud:status'),
  cloudSignIn: (email, password) => ipcRenderer.invoke('cloud:signin', { email, password }),
  cloudSignUp: (email, password) => ipcRenderer.invoke('cloud:signup', { email, password }),
  cloudSignOut: () => ipcRenderer.invoke('cloud:signout'),
  cloudDevices: () => ipcRenderer.invoke('cloud:devices'),
  cloudSend: (type, target) => ipcRenderer.invoke('cloud:send', { type, target }),
  cloudSetKeys: (url, anonKey) => ipcRenderer.invoke('cloud:setkeys', { url, anonKey }),
  onCloudChanged: (cb) => ipcRenderer.on('cloud:changed', () => cb())
});
