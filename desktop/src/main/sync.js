// Optional cloud sync + remote control via Supabase.
//
// FAIL-SAFE: if the Supabase library isn't installed, or no project URL/key is
// set, every function here no-ops and the app works 100% locally as before.
//
// What it does when configured and signed in:
//   - pulls the user's block config from the cloud and pushes local changes,
//     so the same blocks appear on every device they log in on;
//   - registers this device, and listens for remote commands (e.g. your phone
//     sending "strict_on" to your laptop) in realtime;
//   - lets this device send commands to the user's other devices.
//
// Remote "strict_off" never bypasses the local unlock methods - it just asks the
// device to attempt an unlock, which still obeys password/timer/schedule/cooldown.
const fs = require('fs');
const path = require('path');
const os = require('os');

// Rick: paste your Supabase project here to ship it preconfigured for end users.
// (Both values are public client keys - safe to embed.) If left blank, they can
// be set from the app's Account settings instead.
const DEFAULT_URL = 'https://witygsuyvkxhmhbjtxnu.supabase.co';
const DEFAULT_ANON = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndpdHlnc3V5dmt4aG1oYmp0eG51Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE1ODUyMzEsImV4cCI6MjEwNzE2MTIzMX0.1dDJxkHTHAJPsw1OzbrnleeAIZ-Q-nWdSyad84mwaEA';

let supa = null;          // Supabase client
let libMissing = false;   // true if @supabase/supabase-js isn't installed
let lastErr = '';         // last real error, surfaced to the UI for diagnosis
let cfgStore = null;      // app config store
let handlers = {};        // { onRemoteConfig(data), onCommand(cmd) }
let session = null;       // { access_token, refresh_token, user }
let deviceId = null;
let channels = [];
let pushTimer = null;
let sessFile = null, idFile = null;

function creds() {
  const s = (cfgStore && cfgStore.get && cfgStore.get()) || {};
  return {
    url: (s.cloudUrl || DEFAULT_URL || '').trim(),
    anon: (s.cloudAnonKey || DEFAULT_ANON || '').trim()
  };
}
function configured() { const c = creds(); return !!(c.url && c.anon); }

function loadLib() {
  if (supa) return true;
  const c = creds();
  if (!c.url || !c.anon) return false;
  let createClient;
  try { ({ createClient } = require('@supabase/supabase-js')); libMissing = false; }
  catch (e) { libMissing = true; lastErr = 'require: ' + (e && e.message || e); return false; }
  if (typeof createClient !== 'function') { libMissing = true; lastErr = 'createClient export missing'; return false; }
  // Electron's Node (20) has no global WebSocket, which Supabase realtime needs.
  // Supply one from the 'ws' package so createClient doesn't throw.
  let WSImpl;
  try { WSImpl = require('ws'); } catch (e) { WSImpl = (typeof WebSocket !== 'undefined') ? WebSocket : undefined; }
  try {
    const opts = { auth: { persistSession: false, autoRefreshToken: true, detectSessionInUrl: false } };
    if (WSImpl) opts.realtime = { transport: WSImpl };
    supa = createClient(c.url, c.anon, opts);
    return true;
  } catch (e) { supa = null; lastErr = 'createClient: ' + (e && e.message || e); return false; }
}

function deviceName() {
  try { return os.hostname() || 'This device'; } catch (e) { return 'This device'; }
}
function platform() {
  return process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macos' : 'linux';
}

async function init(store, hdlrs, userDataDir) {
  cfgStore = store; handlers = hdlrs || {};
  sessFile = path.join(userDataDir, 'cloud-session.json');
  idFile = path.join(userDataDir, 'device-id.json');
  try { deviceId = JSON.parse(fs.readFileSync(idFile, 'utf8')).id; } catch (e) { deviceId = null; }
  if (!loadLib()) return;
  // Restore a saved session if present.
  try {
    const saved = JSON.parse(fs.readFileSync(sessFile, 'utf8'));
    if (saved && saved.refresh_token) {
      const { data } = await supa.auth.setSession({
        access_token: saved.access_token, refresh_token: saved.refresh_token
      });
      if (data && data.session) { session = data.session; await onSignedIn(); }
    }
  } catch (e) { /* no saved session */ }
}

function persistSession() {
  try {
    if (session) fs.writeFileSync(sessFile, JSON.stringify({
      access_token: session.access_token, refresh_token: session.refresh_token
    }));
    else fs.existsSync(sessFile) && fs.unlinkSync(sessFile);
  } catch (e) {}
}

async function onSignedIn() {
  try {
    await registerDevice();
    await pullConfig();
    subscribe();
  } catch (e) {}
}

function cloudErr(){
  if (libMissing) return 'Supabase library not installed — run "npm install" in the app folder (or use build-installer.cmd), then reopen AppBlocker.' + (lastErr ? ' ['+lastErr+']' : '');
  if (!configured()) return 'Cloud not configured.';
  return 'Could not start the cloud client' + (lastErr ? ': ' + lastErr : '.');
}

async function signIn(email, password) {
  if (!loadLib()) return { error: cloudErr() };
  try {
    const { data, error } = await supa.auth.signInWithPassword({ email, password });
    if (error) return { error: error.message };
    session = data.session; persistSession(); await onSignedIn();
    return { ok: true, email: data.user && data.user.email };
  } catch (e) { return { error: String(e && e.message || e) }; }
}

async function signUp(email, password) {
  if (!loadLib()) return { error: cloudErr() };
  try {
    const { data, error } = await supa.auth.signUp({ email, password });
    if (error) return { error: error.message };
    if (data.session) { session = data.session; persistSession(); await onSignedIn(); }
    return { ok: true, needsConfirm: !data.session, email };
  } catch (e) { return { error: String(e && e.message || e) }; }
}

async function signOut() {
  try { if (supa) await supa.auth.signOut(); } catch (e) {}
  unsubscribe(); session = null; persistSession();
  return { ok: true };
}

function uid() { return session && session.user && session.user.id; }

function status() {
  return {
    configured: configured(),
    signedIn: !!session,
    email: session && session.user && session.user.email || '',
    deviceId, deviceName: deviceName()
  };
}

async function registerDevice() {
  if (!supa || !uid()) return;
  try {
    if (deviceId) {
      await supa.from('devices').update({ last_seen: new Date().toISOString() }).eq('id', deviceId);
      return;
    }
    // No saved id (fresh install). Reuse an existing row for this same
    // machine+platform instead of piling up a new one every launch.
    try {
      const { data: existing } = await supa.from('devices')
        .select('id').eq('user_id', uid()).eq('name', deviceName()).eq('platform', platform()).limit(1);
      if (existing && existing.length) {
        deviceId = existing[0].id;
        await supa.from('devices').update({ last_seen: new Date().toISOString() }).eq('id', deviceId);
        try { fs.writeFileSync(idFile, JSON.stringify({ id: deviceId })); } catch (e) {}
        return;
      }
    } catch (e) {}
    const { data } = await supa.from('devices')
      .insert({ user_id: uid(), name: deviceName(), platform: platform() })
      .select().single();
    if (data) { deviceId = data.id; try { fs.writeFileSync(idFile, JSON.stringify({ id: deviceId })); } catch (e) {} }
  } catch (e) {}
}

// Remove one device row (can't remove this device from here).
async function removeDevice(id) {
  if (!supa || !uid()) return { error: 'Not signed in' };
  if (!id || id === deviceId) return { error: 'Can\'t remove this device' };
  try { await supa.from('devices').delete().eq('user_id', uid()).eq('id', id); return { ok: true }; }
  catch (e) { return { error: String(e && e.message || e) }; }
}

// Remove every device except this one (clears duplicate/old registrations).
async function removeOtherDevices() {
  if (!supa || !uid()) return { error: 'Not signed in' };
  try { await supa.from('devices').delete().eq('user_id', uid()).neq('id', deviceId || '00000000-0000-0000-0000-000000000000'); return { ok: true }; }
  catch (e) { return { error: String(e && e.message || e) }; }
}

// ---- Config sync ----
async function pullConfig() {
  if (!supa || !uid()) return;
  try {
    const { data } = await supa.from('configs').select('data,updated_by').eq('user_id', uid()).maybeSingle();
    if (data && data.data && handlers.onRemoteConfig) handlers.onRemoteConfig(data.data, data.updated_by);
  } catch (e) {}
}
function pushConfig(data) {
  if (!supa || !uid()) return;
  clearTimeout(pushTimer);
  pushTimer = setTimeout(async () => {
    try {
      await supa.from('configs').upsert({
        user_id: uid(), data, updated_at: new Date().toISOString(), updated_by: deviceId
      });
    } catch (e) {}
  }, 1200); // debounce
}

// ---- Realtime ----
function subscribe() {
  if (!supa || !uid()) return;
  unsubscribe();
  try {
    const cmdCh = supa.channel('cmd-' + uid())
      .on('postgres_changes',
        { event: 'INSERT', schema: 'public', table: 'commands', filter: 'user_id=eq.' + uid() },
        (p) => {
          const c = p.new;
          if (c.target_device && c.target_device !== deviceId) return; // not for me
          if (handlers.onCommand) handlers.onCommand(c);
          try { supa.from('commands').update({ consumed: true }).eq('id', c.id); } catch (e) {}
        })
      .subscribe();
    const cfgCh = supa.channel('cfg-' + uid())
      .on('postgres_changes',
        { event: '*', schema: 'public', table: 'configs', filter: 'user_id=eq.' + uid() },
        (p) => {
          const row = p.new;
          if (row && row.updated_by && row.updated_by === deviceId) return; // our own write
          if (row && row.data && handlers.onRemoteConfig) handlers.onRemoteConfig(row.data, row.updated_by);
        })
      .subscribe();
    channels = [cmdCh, cfgCh];
  } catch (e) {}
}
function unsubscribe() {
  for (const ch of channels) { try { supa.removeChannel(ch); } catch (e) {} }
  channels = [];
}

async function listDevices() {
  if (!supa || !uid()) return [];
  try {
    const { data } = await supa.from('devices').select('id,name,platform,last_seen').eq('user_id', uid());
    return (data || []).map(d => ({ ...d, isThis: d.id === deviceId }));
  } catch (e) { return []; }
}

async function sendCommand(type, targetDevice) {
  if (!supa || !uid()) return { error: 'Not signed in' };
  try {
    await supa.from('commands').insert({
      user_id: uid(), target_device: targetDevice || null, type, payload: {}
    });
    return { ok: true };
  } catch (e) { return { error: String(e && e.message || e) }; }
}

module.exports = {
  init, status, signIn, signUp, signOut,
  pushConfig, pullConfig, listDevices, sendCommand, removeDevice, removeOtherDevices, configured
};
