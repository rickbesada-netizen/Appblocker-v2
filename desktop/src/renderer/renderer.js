(function(){
if (window.__appblockerInit) return;
window.__appblockerInit = true;

const api = window.api;
let state = { lists: [], strict: {}, adultStrict: {}, webFilter: {} };
let editing = null;      // list being edited (a copy)
let editorSnapshot = ''; // form state when the editor opened / last saved
let globalCfg = null;    // renderer-held unlock config for the global strict

const DAYS = ['S','M','T','W','T','F','S']; // 0=Sun..6=Sat

function $(id){ return document.getElementById(id); }
function uuid(){ return 'xxxxxxxx'.replace(/x/g,()=>((Math.random()*16)|0).toString(16)) + Date.now().toString(16); }
function defaultRange(){ return { days:[1,2,3,4,5], start:'09:00', end:'17:00' }; }

async function refresh() {
  state = await api.getState();
  renderLists();
  renderGlobalStrict();
  renderFilter();
  renderExtStatus();
  $('startupToggle').checked = state.launchOnStartup !== false;
  $('enforcementToggle').checked = state.strictBrowserEnforcement !== false;
  renderExtEnabled();
  const err = state.lastError;
  const bar = $('errorBar');
  if (err) { bar.textContent = err; bar.classList.remove('hidden'); } else bar.classList.add('hidden');
  if (editing) renderListStrict();
  renderConsent();
  if (typeof renderBlKey === 'function') renderBlKey();
  if (typeof renderAdminPw === 'function') renderAdminPw();
  if (typeof renderWeekly === 'function') renderWeekly();
  if (typeof renderFrozen === 'function') renderFrozen();
}

// ---- First-run consent gate ----
function renderConsent(){
  const m=$('consentModal'); if(!m) return;
  if(state.consentAccepted===false) m.classList.remove('hidden'); else m.classList.add('hidden');
}
if($('consentCheck')) $('consentCheck').onchange=(e)=>{ $('consentAgree').disabled=!e.target.checked; };
if($('consentAgree')) $('consentAgree').onclick=async()=>{ state=await api.acceptConsent(); $('consentModal').classList.add('hidden'); };
if($('makeStandardUserBtn')) $('makeStandardUserBtn').onclick=()=>{ api.openUserSettings(); };

// Does the config have any website blocking that NEEDS the browser extension?
function needsExtension() {
  const anyListSite = (state.lists || []).some(l => l.enabled && (l.websites || []).length > 0);
  const webOn = state.webFilter && state.webFilter.enabled;
  return anyListSite || webOn;
}

const BROWSER_LABEL = {
  chrome: 'Chrome', edge: 'Edge', brave: 'Brave', vivaldi: 'Vivaldi',
  opera: 'Opera', firefox: 'Firefox', chromium: 'Chromium'
};
// Extension is treated as "alive" in a browser up to this age (matches the app's
// STALE_MS); older than this and that browser is on track to be closed.
const EXT_STALE_MS = 180000;

function agoText(ms) {
  if (ms < 2000) return 'just now';
  const s = Math.round(ms / 1000);
  if (s < 90) return s + 's ago';
  const m = Math.round(s / 60);
  if (m < 90) return m + ' min ago';
  return Math.round(m / 60) + ' hr ago';
}

// ---- When will a lock unlock? (human-readable timing for each method) ----
function toMinHM(hhmm){ const [h,m]=String(hhmm).split(':').map(Number); return (h||0)*60+(m||0); }
function rangeActiveAt(r,d){
  const day=d.getDay(), prev=(day+6)%7, now=d.getHours()*60+d.getMinutes();
  const s=toMinHM(r.start), e=toMinHM(r.end), days=r.days||[];
  if(s===e) return days.includes(day);
  if(s<e) return days.includes(day)&&now>=s&&now<e;
  return (days.includes(day)&&now>=s)||(days.includes(prev)&&now<e);
}
function anyRangeActive(ranges,d){ return (ranges||[]).some(r=>rangeActiveAt(r,d)); }
function nextFreeTime(ranges){
  const d=new Date(); d.setSeconds(0,0);
  for(let i=0;i<7*24*60;i++){ if(!anyRangeActive(ranges,d)) return new Date(d); d.setMinutes(d.getMinutes()+1); }
  return null;
}
function fmtWhen(d){
  const now=new Date();
  const t=d.toLocaleTimeString([], {hour:'numeric', minute:'2-digit'});
  if(d.toDateString()===now.toDateString()) return 'today '+t;
  const tm=new Date(now); tm.setDate(now.getDate()+1);
  if(d.toDateString()===tm.toDateString()) return 'tomorrow '+t;
  return d.toLocaleDateString([], {weekday:'short'})+' '+t;
}
function fmtDur(sec){
  sec=Math.round(sec||0);
  if(sec<60) return sec+'s';
  const m=Math.round(sec/60); if(m<60) return m+' min';
  const h=Math.floor(m/60), mm=m%60; return h+'h'+(mm?(' '+mm+'m'):'');
}
function fmtLeft(ms){ const s=Math.max(0,Math.round(ms/1000)); return fmtDur(s)+' left'; }
// Returns e.g. "unlocks today 3:45 PM (12 min left)" for a locked lock, or '' if
// it opens instantly / needs only a password.
function lockTiming(lk){
  if(!lk) return '';
  const m=lk.methods||[]; const now=Date.now(); const out=[];
  if(m.includes('timer') && (lk.waitMinutes||0)>0){
    const ua=(lk.activatedAt||0)+lk.waitMinutes*60000;
    out.push(now<ua?('timer unlocks '+fmtWhen(new Date(ua))+' ('+fmtLeft(ua-now)+')'):'timer done — press Unlock');
  }
  if(m.includes('cooldown') && (lk.cooldownSeconds||0)>0){
    if(lk.cooldownStartedAt){ const ua=lk.cooldownStartedAt+lk.cooldownSeconds*1000;
      out.push(now<ua?('cooldown unlocks '+fmtWhen(new Date(ua))+' ('+fmtLeft(ua-now)+')'):'cooldown done — press Unlock'); }
    else out.push('press Unlock to start a '+fmtDur(lk.cooldownSeconds)+' cooldown');
  }
  if(m.includes('schedule')){
    const ranges=lk.ranges||[];
    if(!ranges.length) out.push('unlockable now');
    else if(!anyRangeActive(ranges,new Date())) out.push('unlockable now (outside your schedule)');
    else { const f=nextFreeTime(ranges); out.push(f?('unlockable '+fmtWhen(f)):'no free time in your schedule'); }
  }
  if(m.includes('password')) out.push('needs password');
  return out.join(' · ');
}

function renderExtStatus() {
  const connected = !!state.extConnected;
  const line = $('extStatusLine');
  if (line) {
    line.textContent = connected ? '✅ Extension connected and enforcing.' : '⚠ Extension NOT detected.';
    line.style.color = connected ? '#36c17a' : '#e5a53b';
  }

  // Per-browser last-seen. Explains why a browser (e.g. Brave) closed: its
  // extension stopped checking in, so the app closed it to stop the bypass.
  const box = $('extPerBrowser');
  if (box) {
    const beats = state.extBeats || {};
    const ids = Object.keys(beats).sort((a, b) => beats[a] - beats[b]);
    if (!ids.length) {
      box.innerHTML = connected
        ? '<span class="muted">Connected. Per-browser details appear once the updated extension is in use (the current Web Store version doesn\'t report which browser). No browser will be closed while it\'s connected.</span>'
        : '<span class="muted">No browser has connected the extension yet.</span>';
    } else {
      box.innerHTML = ids.map(id => {
        const ms = beats[id];
        const live = ms < EXT_STALE_MS;
        const label = BROWSER_LABEL[id] || (id.charAt(0).toUpperCase() + id.slice(1));
        const dot = live ? '#36c17a' : '#e5a53b';
        const note = live ? '' : ' — not checking in; will be closed';
        return `<div class="ext-row"><span class="ext-dot" style="background:${dot}"></span>` +
          `<b>${label}</b><span class="muted">last seen ${agoText(ms)}${note}</span></div>`;
      }).join('');
    }
  }
  const banner = $('extBanner');
  if (!banner) return;
  if (needsExtension() && !connected) {
    banner.textContent = '⚠ Browser extension not installed — websites won\'t block until you install it once (Settings → Browser extension → Load unpacked).';
    banner.classList.remove('hidden');
  } else {
    banner.classList.add('hidden');
  }
}

// ---- Tabs ----
document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
  document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(x => x.classList.remove('active'));
  t.classList.add('active');
  $(t.dataset.tab).classList.add('active');
});

// ---- Shared range editor ----
function buildRangeCard(ranges, idx, onChange){
  const r = ranges[idx];
  const c = document.createElement('div'); c.className='range-card';
  const days = document.createElement('div'); days.className='days';
  DAYS.forEach((letter,dayIdx)=>{
    const el=document.createElement('div');
    el.className='day'+((r.days||[]).includes(dayIdx)?' on':'');
    el.textContent=letter;
    el.onclick=()=>{ r.days=r.days||[]; const i=r.days.indexOf(dayIdx); if(i>=0)r.days.splice(i,1); else r.days.push(dayIdx); onChange(); };
    days.appendChild(el);
  });
  c.appendChild(days);
  const times=document.createElement('div'); times.className='times';
  const from=document.createElement('input'); from.type='time'; from.value=r.start; from.onchange=()=>{r.start=from.value;};
  const to=document.createElement('input'); to.type='time'; to.value=r.end; to.onchange=()=>{r.end=to.value;};
  times.appendChild(from); times.appendChild(to); c.appendChild(times);
  if(ranges.length>1){
    const rm=document.createElement('button'); rm.className='btn-outline small'; rm.textContent='Remove';
    rm.onclick=()=>{ ranges.splice(idx,1); onChange(); }; c.appendChild(rm);
  }
  return c;
}
function renderRangesInto(box, ranges, onChange){
  box.innerHTML='';
  ranges.forEach((_,idx)=> box.appendChild(buildRangeCard(ranges, idx, onChange)));
}

// ---- Lists ----
function activeNow(list){
  if(!list.enabled) return false;
  if(!list.scheduleEnabled) return true;
  const d=new Date(), day=d.getDay(), now=d.getHours()*60+d.getMinutes(), prev=(day+6)%7;
  return (list.ranges||[]).some(r=>{
    const [sh,sm]=r.start.split(':').map(Number), [eh,em]=r.end.split(':').map(Number);
    const s=sh*60+sm, e=eh*60+em, days=r.days||[];
    if(s===e) return days.includes(day);
    if(s<e) return days.includes(day)&&now>=s&&now<e;
    return (days.includes(day)&&now>=s)||(days.includes(prev)&&now<e);
  });
}
function lockedList(l){ return !!(state.strict&&state.strict.enabled); }

function renderLists() {
  const wrap=$('lists'); wrap.innerHTML='';
  const lists=state.lists||[];
  $('emptyLists').classList.toggle('hidden', lists.length>0);
  let activeCount=0;
  for(const l of lists){
    if(activeNow(l)) activeCount++;
    const card=document.createElement('div'); card.className='list-card';
    const sites=(l.websites||[]).length, apps=(l.apps||[]).length;
    let status='<span class="status-off">Off</span>';
    if(l.enabled && activeNow(l)) status='<span class="status-on">● Blocking now</span>';
    else if(l.enabled) status='<span class="status-sched">Scheduled - not active now</span>';
    const lockBadge = '';
    card.innerHTML=`<div class="grow">
        <div class="name"></div>
        <div class="sub">${apps} app(s) · ${sites} site(s)${l.scheduleEnabled?' · scheduled':''}${lockBadge}</div>
        <div style="margin-top:6px">${status}</div>
      </div>`;
    card.querySelector('.name').textContent=l.name||'Untitled list';
    const tgl=document.createElement('input'); tgl.type='checkbox'; tgl.checked=!!l.enabled;
    tgl.onclick=async(e)=>{ e.stopPropagation(); const r=await api.toggleList(l.id,tgl.checked); if(r.error){alert(r.error);tgl.checked=!tgl.checked;} else {state=r;renderLists();} };
    card.appendChild(tgl);
    card.onclick=()=>openEditor(l);
    wrap.appendChild(card);
  }
  $('statusChip').textContent=`${activeCount} active`;
}

// ---- List editor ----
function openEditor(list){
  editing = list ? JSON.parse(JSON.stringify(list))
                 : { id:uuid(), name:'', enabled:true, apps:[], websites:[], scheduleEnabled:false, ranges:[defaultRange()] };
  $('editorTitle').textContent=list?'Edit block list':'New block list';
  $('listName').value=editing.name||'';
  $('listSites').value=(editing.websites||[]).join('\n');
  if($('listExceptions')) $('listExceptions').value=(editing.exceptions||[]).join('\n');
  $('listApps').value=(editing.apps||[]).join('\n');
  $('schedEnabled').checked=!!editing.scheduleEnabled;
  $('schedBox').classList.toggle('hidden', !editing.scheduleEnabled);
  $('deleteList').classList.toggle('hidden', !list || lockedList(editing));
  $('runningApps').classList.add('hidden');
  if(!editing.ranges||!editing.ranges.length) editing.ranges=[defaultRange()];
  renderEditorRanges();
  renderListStrict();
  $('editor').classList.remove('hidden');
  editorSnapshot=editorFormState();
}
// A stable string of everything the editor can change, to detect unsaved edits.
function editorFormState(){
  if(!editing) return '';
  return JSON.stringify({
    name:($('listName').value||'').trim(),
    websites:$('listSites').value.split('\n').map(s=>s.trim()).filter(Boolean),
    exceptions:($('listExceptions')?$('listExceptions').value:'').split('\n').map(s=>s.trim()).filter(Boolean),
    apps:$('listApps').value.split('\n').map(s=>s.trim()).filter(Boolean),
    scheduleEnabled:$('schedEnabled').checked,
    ranges:editing.ranges||[]
  });
}
function reallyCloseEditor(){ $('editor').classList.add('hidden'); editing=null; }
async function closeEditor(){
  if(editing && editorFormState()!==editorSnapshot){
    const ok=window.confirm('You have unsaved changes to this list.\n\nOK = Save and close\nCancel = Discard changes');
    if(ok){ if(!(await doEditorSave())) return; } // save failed (e.g. 24/7 schedule) -> stay open
  }
  reallyCloseEditor();
}
function renderEditorRanges(){ renderRangesInto($('ranges'), editing.ranges, renderEditorRanges); }

function liveList(){ return (state.lists||[]).find(l=>editing && l.id===editing.id); }

// Per-list locks were removed in favour of one central Strict Mode (Strict tab).
function renderListStrict(){}

$('newListBtn').onclick=()=>openEditor(null);
$('editorClose').onclick=closeEditor;
$('schedEnabled').onchange=(e)=>{ editing.scheduleEnabled=e.target.checked; if(!editing.ranges||!editing.ranges.length)editing.ranges=[defaultRange()]; $('schedBox').classList.toggle('hidden',!e.target.checked); renderEditorRanges(); };
$('addRange').onclick=()=>{ editing.ranges.push(defaultRange()); renderEditorRanges(); };
$('deleteList').onclick=async()=>{ const r=await api.deleteList(editing.id); if(r.error)alert(r.error); else {state=r;reallyCloseEditor();renderLists();} };
$('saveList').onclick=()=>doEditorSave();
async function doEditorSave(){
  editing.name=$('listName').value.trim()||'Block list';
  editing.websites=$('listSites').value.split('\n').map(s=>s.trim()).filter(Boolean);
  editing.exceptions=($('listExceptions')?$('listExceptions').value:'').split('\n').map(s=>s.trim()).filter(Boolean);
  editing.apps=$('listApps').value.split('\n').map(s=>s.trim()).filter(Boolean);
  editing.scheduleEnabled=$('schedEnabled').checked;
  if(!editing.websites.length && !editing.apps.length){ alert('Add at least one app or website.'); return false; }
  const r=await api.saveList(editing);
  if(r.error){ alert(r.error); return false; }
  state=r; editorSnapshot=editorFormState(); renderLists(); renderListStrict();
  return true;
}
$('pickApps').onclick=async()=>{
  const procs=await api.listProcesses();
  const box=$('runningApps'); box.innerHTML=''; box.classList.remove('hidden');
  [...new Set(procs)].sort().forEach(p=>{
    const el=document.createElement('span'); el.className='pick'; el.textContent=p;
    el.onclick=()=>{ const ta=$('listApps'); const set=new Set(ta.value.split('\n').map(s=>s.trim()).filter(Boolean)); set.add(p); ta.value=[...set].join('\n'); };
    box.appendChild(el);
  });
};

// ---- Unlock-method modal ----
let ucState=null; // { cfg, onSave }
let ucSnapshot=''; // cfg when the modal opened / last saved
function toCfg(lockObj){
  lockObj=lockObj||{};
  return {
    methods:(lockObj.methods||[]).slice(),
    waitMinutes:lockObj.waitMinutes||30,
    cooldownSeconds:lockObj.cooldownSeconds||15,
    ranges:JSON.parse(JSON.stringify(lockObj.ranges||[])),
    passwordSet:!!lockObj.passwordHash,
    password:'', clearPassword:false
  };
}
const UC_METHODS=[
  {key:'password',label:'Password'},
  {key:'timer',label:'Timer (wait after activating)'},
  {key:'cooldown',label:'Cooldown (wait after pressing unlock)'},
  {key:'schedule',label:'Follow schedules (locked during set times)'}
];
function openUnlock(cfg, onSave){ ucState={cfg,onSave}; ucSnapshot=JSON.stringify(cfg); renderUc(); $('ucModal').classList.remove('hidden'); }
function reallyCloseUc(){ $('ucModal').classList.add('hidden'); ucState=null; }
async function closeUc(){
  if(ucState && JSON.stringify(ucState.cfg)!==ucSnapshot){
    const ok=window.confirm('You have unsaved unlock-method changes.\n\nOK = Save and close\nCancel = Discard changes');
    if(ok){ if(!(await doUcSave())) return; } // save failed (e.g. 24/7 schedule) -> stay open
  }
  reallyCloseUc();
}
function renderUc(){
  const box=$('ucMethods'); box.innerHTML=''; const cfg=ucState.cfg;
  UC_METHODS.forEach(m=>{
    const on=cfg.methods.includes(m.key);
    const row=document.createElement('div'); row.className='uc-row';
    const head=document.createElement('label'); head.className='switch-row';
    const sp=document.createElement('span'); sp.textContent=m.label;
    const cb=document.createElement('input'); cb.type='checkbox'; cb.checked=on;
    cb.onchange=()=>{ if(cb.checked){ if(!cfg.methods.includes(m.key))cfg.methods.push(m.key);} else { cfg.methods=cfg.methods.filter(x=>x!==m.key);} renderUc(); };
    head.appendChild(sp); head.appendChild(cb); row.appendChild(head);
    if(on){
      const det=document.createElement('div'); det.className='uc-detail';
      if(m.key==='password'){
        const inp=document.createElement('input'); inp.type='password';
        inp.placeholder=cfg.passwordSet?'Password is set - type to change':'Set a password';
        inp.oninput=()=>{ cfg.password=inp.value; };
        det.appendChild(inp);
        if(cfg.passwordSet){ const clr=document.createElement('button'); clr.className='btn-outline small'; clr.textContent='Clear password'; clr.onclick=()=>{ cfg.clearPassword=true; cfg.passwordSet=false; renderUc(); }; det.appendChild(clr); }
      } else if(m.key==='timer'){
        const inp=document.createElement('input'); inp.type='number'; inp.min='0'; inp.value=cfg.waitMinutes; inp.oninput=()=>cfg.waitMinutes=Number(inp.value)||0;
        const lab=document.createElement('span'); lab.className='muted'; lab.textContent=' minutes';
        det.appendChild(inp); det.appendChild(lab);
      } else if(m.key==='cooldown'){
        const inp=document.createElement('input'); inp.type='number'; inp.min='0'; inp.value=cfg.cooldownSeconds; inp.oninput=()=>cfg.cooldownSeconds=Number(inp.value)||0;
        const lab=document.createElement('span'); lab.className='muted'; lab.textContent=' seconds';
        det.appendChild(inp); det.appendChild(lab);
      } else if(m.key==='schedule'){
        if(!cfg.ranges.length) cfg.ranges.push(defaultRange());
        const rb=document.createElement('div');
        const reRender=()=>renderRangesInto(rb, cfg.ranges, reRender);
        reRender();
        const add=document.createElement('button'); add.className='btn-outline small'; add.textContent='+ Add time';
        add.onclick=()=>{ cfg.ranges.push(defaultRange()); reRender(); };
        const note=document.createElement('p'); note.className='muted'; note.textContent='Locked while inside these times. Must leave at least one free minute a week.';
        det.appendChild(rb); det.appendChild(add); det.appendChild(note);
      }
      row.appendChild(det);
    }
    box.appendChild(row);
  });
}
$('ucClose').onclick=closeUc;
$('ucSave').onclick=async()=>{ if(await doUcSave()) reallyCloseUc(); };
async function doUcSave(){
  const cfg=ucState.cfg;
  const payload={ methods:cfg.methods, waitMinutes:cfg.waitMinutes, cooldownSeconds:cfg.cooldownSeconds, ranges:cfg.ranges };
  if(cfg.password) payload.password=cfg.password;
  if(cfg.clearPassword) payload.clearPassword=true;
  const ok=await ucState.onSave(payload);
  if(ok===false) return false;       // onSave rejected (e.g. 24/7 schedule) -> keep editing
  ucSnapshot=JSON.stringify(ucState.cfg);
  return true;
}

// ---- Deactivate (shared) ----
function lockFor(kind, id){
  if(kind==='global') return state.strict||{};
  if(kind==='adult') return state.adultStrict||{};
  return ((state.lists||[]).find(l=>l.id===id)||{}).strict||{};
}
async function doDeactivate(kind, id){
  // Only ask for a password when this lock actually uses one. (Electron doesn't
  // support window.prompt reliably, so never block on it for non-password locks.)
  const lk=lockFor(kind,id);
  const needsPw=(lk.methods||[]).includes('password') && lk.passwordHash;
  let pw='';
  if(needsPw){ try{ pw=window.prompt('Enter password to unlock:')||''; }catch(e){ pw=''; } }
  let r;
  if(kind==='global') r=await api.deactivateStrict(pw);
  else if(kind==='adult') r=await api.adultStrictDeactivate(pw);
  else r=await api.listStrictDeactivate(id,pw);
  if(r.state) state=r.state;
  if(!r.ok) alert(r.reason||'Locked.');
  await refresh();
}

// ---- Global Strict ----
function renderGlobalStrict(){
  const st=state.strict||{};
  // Always derive from the SAVED lock (now persisted), so a refresh can't wipe it.
  globalCfg=toCfg(st);
  const methods=(st.methods||[]);
  if(st.enabled){
    { const t=lockTiming(st); $('strictStatus').textContent='On - everything is locked.' + (methods.length?(' Unlock: '+methods.join(' + ')):'') + (t?(' · '+t):''); }
    $('strictToggle').textContent='Deactivate Strict Mode';
    $('strictUnlockBtn').disabled=true;
  } else {
    $('strictStatus').textContent='Off.' + (methods.length?(' Unlock method: '+methods.join(' + ')):' No unlock method set (would turn off instantly).');
    $('strictToggle').textContent='Activate Strict Mode';
    $('strictUnlockBtn').disabled=false;
  }
}
$('strictUnlockBtn').onclick=()=>openUnlock(toCfg(state.strict), async(cfg)=>{
  const r=await api.strictUnlockConfig(cfg);
  if(r.error){ alert(r.error); return false; }
  state=r; renderGlobalStrict(); return true;
});
$('strictToggle').onclick=async()=>{
  const st=state.strict||{};
  if(!st.enabled){
    // The unlock method is already saved; activate uses the stored config.
    const r=await api.activateStrict({});
    if(r.error){ alert(r.error); return; }
    state=r;
  } else {
    await doDeactivate('global');
    return;
  }
  renderGlobalStrict();
};

// ---- Weekly unlock window ----
const DAYNAMES=['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
function renderWeekly(){
  if(!$('wwEnabled')) return;
  const w=state.weeklyWindow||{enabled:false,day:0,start:'03:00',minutes:1};
  $('wwEnabled').checked=!!w.enabled;
  $('wwDay').value=String(w.day||0);
  $('wwStart').value=w.start||'03:00';
  $('wwMinutes').value=w.minutes||1;
  const s=$('wwStatus');
  s.textContent = w.enabled
    ? ('🔒 Locked all week except '+DAYNAMES[w.day||0]+' '+(w.start||'03:00')+' for '+(w.minutes||1)+' min — the only time anything can be turned off.')
    : 'Off — Strict Mode uses its own unlock method instead.';
}
if($('wwSave')) $('wwSave').onclick=async()=>{
  const win={enabled:$('wwEnabled').checked, day:Number($('wwDay').value), start:$('wwStart').value||'03:00', minutes:Math.max(1,Number($('wwMinutes').value)||1)};
  const r=await api.setWeeklyWindow(win);
  if(r.error){ alert(r.error); } else { state=r; renderWeekly(); alert('Weekly window saved.'); }
};

// ---- Frozen Turkey: freeze a Windows account ----
let frRanges=[];
let frUsersLoaded=false;
function renderFrRanges(){ if($('frRanges')) renderRangesInto($('frRanges'), frRanges, renderFrRanges); }
if($('frAddRangeBtn')) $('frAddRangeBtn').onclick=()=>{ frRanges.push(defaultRange()); renderFrRanges(); };
if($('frSave')) $('frSave').onclick=async()=>{
  const u=$('frUser').value;
  if(!u){ alert('Pick a Windows account to freeze.'); return; }
  const accounts = frRanges.length ? [{username:u, enabled:true, ranges:frRanges}] : [];
  const r=await api.setFrozenAccounts(accounts);
  if(r.error){ alert(r.error); } else { state=r; renderFrozen(); alert(frRanges.length?('Freeze saved for '+u):'Freeze cleared.'); }
};
async function renderFrozen(){
  if(!$('frUser')) return;
  if(!frUsersLoaded){
    frUsersLoaded=true;
    let users=[]; try{ users=await api.listUsers(); }catch(e){}
    const sel=$('frUser'); sel.innerHTML='';
    if(!users.length){ const o=document.createElement('option'); o.value=''; o.textContent='(Windows only)'; sel.appendChild(o); }
    users.forEach(u=>{ const o=document.createElement('option'); o.value=u; o.textContent=u; sel.appendChild(o); });
    const fa=(state.frozenAccounts||[])[0];
    if(fa){ sel.value=fa.username; frRanges=JSON.parse(JSON.stringify(fa.ranges||[])); }
    renderFrRanges();
  }
  const fa=(state.frozenAccounts||[])[0];
  $('frStatus').textContent = fa ? ('Freezing "'+fa.username+'" on '+(fa.ranges||[]).length+' schedule(s).') : 'No account frozen yet.';
}

// ---- Adult & Web filter ----
function renderFilter(){
  $('adultToggle').checked=!!state.adultFilter;

  const wf=state.webFilter||{enabled:false, allowedSites:[]};
  $('webToggle').checked=!!wf.enabled;
  if(document.activeElement!==$('allowedSites')) $('allowedSites').value=(wf.allowedSites||[]).join('\n');
  $('webHint').textContent = wf.enabled
    ? 'On: only the sites above can be opened. Install the AppBlocker extension in Chrome/Edge (see README).'
    : 'Off: all browsing allowed.';
}
$('adultToggle').onchange=async(e)=>{ const r=await api.setAdultFilter(e.target.checked); if(r.error){alert(r.error);e.target.checked=!e.target.checked;} else state=r; renderFilter(); };
$('webToggle').onchange=async(e)=>{
  const sites=$('allowedSites').value.split('\n').map(s=>s.trim()).filter(Boolean);
  const r=await api.setWebFilter(e.target.checked, sites);
  if(r.error){ alert(r.error); e.target.checked=!e.target.checked; } else state=r;
  renderFilter();
};
$('saveAllowed').onclick=async()=>{
  const sites=$('allowedSites').value.split('\n').map(s=>s.trim()).filter(Boolean);
  const wf=state.webFilter||{};
  const r=await api.setWebFilter(wf.enabled, sites);
  if(r.error){ alert(r.error); } else { state=r; renderFilter(); }
};

// ---- Settings ----
$('startupToggle').onchange=async(e)=>{ state=await api.setStartup(e.target.checked); };

// ---- BitLocker (detect + guide) ----
async function renderBitlocker(){
  if(!api.getBitlocker) return;
  let b; try{ b=await api.getBitlocker(); }catch{ return; }
  const card=$('bitlockerCard'); if(!card) return;
  if(!b || b.supported===false){ card.style.display='none'; return; }
  card.style.display='';
  const on=!!b.on;
  const s=$('bitlockerStatus');
  s.textContent = on ? '✅ BitLocker is ON for this PC.' : '⚠ BitLocker is OFF for this PC.';
  s.style.color = on ? '#36c17a' : '#e5a53b';
  $('bitlockerBtn').style.display = on ? 'none' : '';
}
if($('bitlockerBtn')) $('bitlockerBtn').onclick=async()=>{ await api.openBitlocker(); };
renderBitlocker();

// ---- BitLocker recovery-key vault ----
async function renderBlKey(){
  if(!api.getBitlockerKey || !$('blKeyStatus')) return;
  let r; try{ r=await api.getBitlockerKey(); }catch{ return; }
  $('blKeyStatus').textContent = r.has ? '🔑 A recovery key is saved.' : 'No key saved yet.';
  const reveal=$('blKeyReveal');
  // Hide any previously revealed key whenever we refresh/lock.
  if(r.locked){ reveal.textContent = r.has ? '🔒 Hidden while Strict Mode is on. Turn Strict Mode off to view it.' : ''; reveal.style.color='#e5a53b'; }
  else { reveal.style.color='#9a9ab0'; }
  if($('blPdfStatus')){
    $('blPdfStatus').textContent = r.hasPdf ? '📄 A recovery-key file is saved.' : 'No file uploaded yet.';
  }
}
if($('blKeySave')) $('blKeySave').onclick=async()=>{
  const v=$('blKeyInput').value.trim();
  if(!v){ alert('Paste your recovery key first.'); return; }
  await api.setBitlockerKey(v);
  $('blKeyInput').value='';
  await renderBlKey();
  alert('Recovery key saved.');
};
if($('blKeyShow')) $('blKeyShow').onclick=async()=>{
  const r=await api.getBitlockerKey();
  const reveal=$('blKeyReveal');
  if(!r.has){ reveal.textContent='No key saved yet.'; reveal.style.color='#9a9ab0'; return; }
  if(r.locked){ reveal.textContent='🔒 Hidden while Strict Mode is on. Turn Strict Mode off to view it.'; reveal.style.color='#e5a53b'; return; }
  reveal.textContent='Recovery key: '+r.key; reveal.style.color='#36c17a';
};
if($('blPdfUpload')) $('blPdfUpload').onclick=async()=>{
  const r=await api.uploadKeyPdf();
  if(r && r.ok){ await renderBlKey(); alert('Recovery-key file saved.'); }
  else if(r && r.error){ alert('Could not save: '+r.error); }
};
if($('blPdfOpen')) $('blPdfOpen').onclick=async()=>{
  const r=await api.openKeyPdf();
  if(r && r.locked){ alert('Hidden while Strict Mode is on. Turn Strict Mode off to open it.'); }
  else if(r && r.error){ alert(r.error); }
};
renderBlKey();

// ---- Admin → Standard-user setup advice ----
async function renderAdminCard(){
  if(!api.isAdmin || !$('adminCard')) return;
  let r; try{ r=await api.isAdmin(); }catch{ return; }
  $('adminCard').style.display = (r && r.admin) ? '' : 'none';
}
if($('adminOpenUsers')) $('adminOpenUsers').onclick=()=>{ api.openUserSettings(); };
renderAdminCard();

// ---- Admin password vault (shown only when Strict Mode is off) ----
async function renderAdminPw(){
  if(!api.getAdminPassword || !$('adminPwStatus')) return;
  let r; try{ r=await api.getAdminPassword(); }catch{ return; }
  $('adminPwStatus').textContent = r.has ? '🔑 An admin password is saved.' : 'No admin password saved yet.';
  const reveal=$('adminPwReveal');
  if(r.locked){ reveal.textContent = r.has ? '🔒 Hidden while Strict Mode is on. Turn Strict Mode off to view it.' : ''; reveal.style.color='#e5a53b'; }
}
if($('adminPwSave')) $('adminPwSave').onclick=async()=>{
  const v=$('adminPwInput').value;
  if(!v){ alert('Enter the admin password first.'); return; }
  await api.setAdminPassword(v);
  $('adminPwInput').value='';
  await renderAdminPw();
  alert('Admin password saved.');
};
if($('adminPwShow')) $('adminPwShow').onclick=async()=>{
  const r=await api.getAdminPassword();
  const reveal=$('adminPwReveal');
  if(!r.has){ reveal.textContent='No admin password saved yet.'; reveal.style.color='#9a9ab0'; return; }
  if(r.locked){ reveal.textContent='🔒 Hidden while Strict Mode is on. Turn Strict Mode off to view it.'; reveal.style.color='#e5a53b'; return; }
  reveal.textContent='Admin password: '+r.pw; reveal.style.color='#36c17a';
};
renderAdminPw();

// ---- Account & sync (Supabase) ----
const PLAT_ICON={ windows:'🪟', macos:'🍎', android:'📱', ios:'📱', linux:'🐧' };
async function renderCloud(){
  if(!api.cloudStatus || !$('cloudCard')) return;
  let st; try{ st=await api.cloudStatus(); }catch{ return; }
  const notCfg=$('cloudNotConfigured'), out=$('cloudSignedOut'), inn=$('cloudSignedIn');
  notCfg.style.display = st.configured ? 'none' : '';
  out.style.display    = (st.configured && !st.signedIn) ? '' : 'none';
  inn.style.display    = (st.configured && st.signedIn) ? '' : 'none';
  if(st.signedIn){
    $('cloudWho').textContent = st.email || '(account)';
    let devs=[]; try{ devs=await api.cloudDevices(); }catch{}
    const box=$('cloudDevices');
    box.innerHTML = devs.length ? devs.map(d=>{
      const icon=PLAT_ICON[d.platform]||'💻';
      const me = d.isThis ? ' <span class="muted">(this device)</span>' : '';
      const btns = d.isThis ? '' :
        `<button class="btn-outline small" data-strict="${d.id}">Strict on</button>
         <button class="btn-outline small" data-lock="${d.id}">Lock all</button>`;
      return `<div class="row-between" style="padding:6px 0;border-bottom:1px solid #191922">
        <span>${icon} <b>${d.name||'Device'}</b>${me}</span><span>${btns}</span></div>`;
    }).join('') : '<span class="muted">No other devices yet. Sign in on your phone to see it here.</span>';
    box.querySelectorAll('[data-strict]').forEach(b=> b.onclick=async()=>{ await api.cloudSend('strict_on', b.dataset.strict); alert('Strict Mode command sent.'); });
    box.querySelectorAll('[data-lock]').forEach(b=> b.onclick=async()=>{ await api.cloudSend('lock_all', b.dataset.lock); alert('Lock command sent.'); });
  }
}
if($('cloudSignIn')) $('cloudSignIn').onclick=async()=>{
  $('cloudMsg').textContent='Signing in…';
  const r=await api.cloudSignIn($('cloudEmail').value.trim(), $('cloudPassword').value);
  $('cloudMsg').textContent = r.error ? ('⚠ '+r.error) : '';
  if(r.ok){ $('cloudPassword').value=''; await renderCloud(); await refresh(); }
};
if($('cloudSignUp')) $('cloudSignUp').onclick=async()=>{
  $('cloudMsg').textContent='Creating account…';
  const r=await api.cloudSignUp($('cloudEmail').value.trim(), $('cloudPassword').value);
  if(r.error){ $('cloudMsg').textContent='⚠ '+r.error; return; }
  $('cloudMsg').textContent = r.needsConfirm ? 'Check your email to confirm, then sign in.' : '';
  if(r.ok && !r.needsConfirm){ await renderCloud(); await refresh(); }
};
if($('cloudSignOut')) $('cloudSignOut').onclick=async()=>{ await api.cloudSignOut(); await renderCloud(); };
if($('cloudSaveKeys')) $('cloudSaveKeys').onclick=async()=>{
  await api.cloudSetKeys($('cloudUrl').value.trim(), $('cloudAnon').value.trim());
  alert('Saved. Please close and reopen AppBlocker to connect.');
};
if(api.onCloudChanged) api.onCloudChanged(()=>{ refresh(); renderCloud(); });
renderCloud();
$('enforcementToggle').onchange=async(e)=>{ const r=await api.setEnforcement(e.target.checked); if(r.error){alert(r.error);e.target.checked=!e.target.checked;} else state=r; };

// Browser-extension install switch (the only way to remove it; locked while any lock is on).
// Which locks are currently on (so we can tell the user what blocks removal).
function activeLockNames(){
  const names=[];
  if(state.strict && state.strict.enabled) names.push('Strict Mode');
  return names;
}

function renderExtEnabled(){
  const t=$('extEnabledToggle'); if(!t) return;
  const on = state.extensionEnabled !== false;
  t.checked = on;
  const hint=$('extEnabledHint');
  if(!hint) return;
  if(!on){
    hint.textContent='⚠ Force-install lifted. Now FULLY RESTART your browser (close every window — check it\'s not still running in the tray — then reopen, or use brave://restart) for the extension to actually disappear. Re-check this to reinstall and protect it.';
    return;
  }
  const locks=activeLockNames();
  if(locks.length){
    hint.textContent='🔒 Protected and force-installed. This switch is locked by: '+locks.join(', ')+' (can\'t be turned off until those are off). If a browser still shows a "Remove" button, FULLY RESTART that browser so the install-lock applies.';
  } else {
    hint.textContent='🛡 Force-installing in Chrome, Edge & Brave. The extension can\'t be removed there — but a browser only applies the lock after a FULL RESTART. If you still see a "Remove" button, close every window of that browser (check the tray) and reopen it (or brave://restart).';
  }
}
if($('extEnabledToggle')) $('extEnabledToggle').onchange=async(e)=>{
  const want=e.target.checked;
  if(!want && !confirm('Remove the browser extension?\n\nThis lifts the force-install. You then need to FULLY RESTART your browser (close all windows) for the extension to disappear. Website blocking inside the browser stops until you turn this back on.')){
    e.target.checked=true; return;
  }
  const r=await api.setExtensionEnabled(want);
  if(r.error){ alert(r.error); e.target.checked=!want; } else { state=r; renderExtEnabled(); }
};

setInterval(()=>{ refresh(); }, 5000);
refresh();

})();
