'use strict';
/* global sublens */
const $ = (id) => document.getElementById(id);
const COLOURS = ['#5EEAD4', '#60A5FA', '#A78BFA', '#F472B6', '#F87171', '#FB923C', '#FACC15', '#4ADE80', '#F8FAFC'];
let T = {};
let info = null;
let settings = null;
let onlineState = null;
let expanded = null;
const cards = new Map();

const fmt = (s, ...a) => String(s).replace(/\{(\d)\}/g, (_, i) => a[+i] ?? '');
function langName(code) {
  try {
    return new Intl.DisplayNames([T._lang || 'en'], { type: 'language' }).of(code) || code;
  } catch {
    return code;
  }
}

(async function init() {
  info = await sublens.info();
  T = info.texts;
  settings = await sublens.getSettings();
  onlineState = await sublens.online.state();
  texts();
  fillLanguages();
  wire();
  buildCatalog();
  render();
  renderModel(await sublens.modelState());
  sublens.onSettings(async (s) => {
    settings = s;
    onlineState = await sublens.online.state();
    render();
  });
  sublens.onModel(renderModel);
  sublens.onUpdate((u) => {
    $('update').hidden = false;
    $('updateText').textContent = fmt(T.update, u.version);
    $('updateBtn').onclick = () => sublens.openExternal(u.url);
  });
})();

// ---------------------------------------------------------------- Home

function texts() {
  document.documentElement.lang = T._lang;
  $('tagline').textContent = T.tagline;
  $('version').textContent = `v${info.version}`;
  $('hotkey').textContent = fmt(T.hotkey, info.hotkey);
  $('hLanguage').textContent = T.hLanguage;
  $('lFrom').textContent = T.from;
  $('lTo').textContent = T.to;
  $('lReplyTo').textContent = T.replyTo;
  $('swap').title = T.swap;
  $('hTranslation').textContent = T.hTranslation;
  $('engineOffline').querySelector('b').textContent = T.engineOffline;
  $('engineOffline').querySelector('small').textContent = T.engineOfflineHint;
  $('engineOnline').querySelector('b').textContent = T.engineOnline;
  $('engineOnline').querySelector('small').textContent = T.engineOnlineHint;
  $('hLens').textContent = T.hLens;
  $('lMode').textContent = T.mode;
  $('modeChat').querySelector('b').textContent = T.modeChat;
  $('modeChat').querySelector('small').textContent = T.modeChatHint;
  $('modeVideo').querySelector('b').textContent = T.modeVideo;
  $('modeVideo').querySelector('small').textContent = T.modeVideoHint;
  $('lColour').textContent = T.colour;
  $('lReply').textContent = T.reply;
  $('replyOn').textContent = T.on;
  $('replyOff').textContent = T.off;
  $('hModel').textContent = T.model;
  $('ocrText').textContent = T.ocrData;
  $('deleteBtn').textContent = T.deleteData;
  $('updateBtn').textContent = T.updateGet;
  if (info.platform === 'darwin' && info.screenPermission !== 'granted') {
    $('perm').hidden = false;
    $('permText').textContent = T.permMac;
    $('permBtn').textContent = T.permOpen;
    $('permBtn').onclick = () => sublens.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture');
  }
  // Catalog
  $('back').title = T.back;
  $('cTitle').textContent = T.onlineTitle;
  $('cIntro').textContent = T.onlineIntro;
  $('cSwitchLabel').textContent = T.onlineSwitch;
  $('pasteAny').textContent = T.onlinePasteAny;
  $('pasteHint').textContent = T.onlinePasteHint;
  $('cServices').textContent = T.onlineServices;
  $('cPrivacyTitle').textContent = T.onlinePrivacyTitle;
  $('cPrivacy').textContent = T.onlinePrivacy;
}

function fillLanguages() {
  const sorted = [...info.languages].sort((a, b) => langName(a.code).localeCompare(langName(b.code), T._lang));
  const from = $('from');
  from.innerHTML = '';
  from.add(new Option(T.autoLatin, 'auto'));
  for (const l of sorted) from.add(new Option(langName(l.code), l.code));
  for (const id of ['to', 'replyTo']) {
    const sel = $(id);
    sel.innerHTML = '';
    if (id === 'replyTo') sel.add(new Option(T.replyAuto, 'auto'));
    for (const l of sorted) sel.add(new Option(langName(l.code), l.code));
  }
  const ui = $('uiLang');
  ui.title = T.appLanguage;
  ui.innerHTML = '';
  for (const [code, name] of [['', T.appLanguageSystem], ['en', 'English'], ['de', 'Deutsch'], ['zh-Hans', '简体中文'], ['zh-Hant', '繁體中文']]) {
    ui.add(new Option(name, code));
  }
  $('swatches').innerHTML = COLOURS.map((c) => `<button data-c="${c}" style="background:${c}" title="${c}"></button>`).join('');
}

function wire() {
  $('lensBtn').onclick = () => sublens.showLens(!settings.lensVisible);
  $('uiLang').onchange = (e) => sublens.setSettings({ uiLang: e.target.value });
  $('from').onchange = (e) => sublens.setSettings({ from: e.target.value });
  $('to').onchange = (e) => sublens.setSettings({ to: e.target.value });
  $('replyTo').onchange = (e) => sublens.setSettings({ replyTo: e.target.value });
  $('swap').onclick = () => {
    const src = settings.from === 'auto' ? settings.lastSource : settings.from;
    if (!src || src === settings.to) return;
    sublens.setSettings({ from: settings.to, to: src });
  };
  $('engineOffline').onclick = () => setOnline(false);
  $('engineOnline').onclick = () => {
    setOnline(true);
    // Not set up yet → straight to the catalog.
    const p = onlineState.providers.find((x) => x.id === onlineState.provider);
    if (!p || !p.configured) showCatalog(true);
  };
  $('onlineBtn').onclick = () => showCatalog(true);
  $('modeChat').onclick = () => sublens.setSettings({ mode: 'chat' });
  $('modeVideo').onclick = () => sublens.setSettings({ mode: 'video' });
  $('replyOn').onclick = () => sublens.setSettings({ reply: true });
  $('replyOff').onclick = () => sublens.setSettings({ reply: false });
  $('swatches').onclick = (e) => {
    const c = e.target.dataset && e.target.dataset.c;
    if (c) sublens.setSettings({ color: c });
  };
  $('modelBtn').onclick = () => sublens.downloadModel().catch(() => {});
  $('deleteBtn').onclick = async () => {
    await sublens.deleteDownloads();
    $('ocrText').textContent = T.deleted;
  };
  $('gh').onclick = (e) => {
    e.preventDefault();
    sublens.openExternal('https://github.com/Elphax89/sublense');
  };
  // Catalog
  $('back').onclick = () => showCatalog(false);
  $('cSwitch').onclick = () => setOnline(!onlineState.enabled);
  $('pasteAny').onclick = pasteAny;
}

async function setOnline(on) {
  onlineState = await sublens.online.set({ enabled: on });
  render();
}

function showCatalog(show) {
  $('home').hidden = show;
  $('catalog').hidden = !show;
  window.scrollTo(0, 0);
  if (show) renderCatalog();
}

function render() {
  const on = settings.lensVisible;
  $('lensBtn').textContent = on ? T.stopLens : T.startLens;
  $('lensBtn').classList.toggle('stop', on);
  $('uiLang').value = settings.uiLang || '';
  $('from').value = settings.from;
  $('to').value = settings.to;
  $('replyTo').value = settings.replyTo || 'auto';
  $('modeChat').classList.toggle('on', settings.mode !== 'video');
  $('modeVideo').classList.toggle('on', settings.mode === 'video');
  $('replyOn').classList.toggle('on', settings.reply);
  $('replyOff').classList.toggle('on', !settings.reply);
  for (const b of $('swatches').children) b.classList.toggle('on', b.dataset.c.toLowerCase() === settings.color.toLowerCase());
  document.documentElement.style.setProperty('--accent', settings.color);

  const current = onlineState.providers.find((p) => p.id === onlineState.provider);
  const configured = !!(current && current.configured);
  $('engineOffline').classList.toggle('on', !onlineState.enabled);
  $('engineOnline').classList.toggle('on', onlineState.enabled);
  $('engineStatus').textContent = !onlineState.enabled
    ? T.engineStatusOffline
    : configured ? fmt(T.engineStatusOnline, onlineState.summary || current.name) : T.engineStatusNotSet;
  $('onlineBtn').textContent = configured ? T.engineManage : T.engineSetup;
  $('footer').textContent = onlineState.enabled && configured ? fmt(T.footerOnline, current.name) : T.footer;
  renderCatalog();
}

function renderModel(m) {
  const mb = (b) => Math.round((b || 0) / 1048576);
  $('bar').hidden = true;
  $('modelBtn').hidden = true;
  switch (m.status) {
    case 'ready':
    case 'downloaded':
      $('modelText').textContent = fmt(T.modelReady, mb(m.bytes));
      break;
    case 'downloading':
      $('modelText').textContent = fmt(T.modelDownloading, mb(m.loaded), m.total ? mb(m.total) : '…');
      $('bar').hidden = false;
      $('barFill').style.width = `${m.total ? (m.loaded / m.total) * 100 : 2}%`;
      break;
    case 'loading':
      $('modelText').textContent = T.modelLoading;
      break;
    case 'error':
      $('modelText').textContent = fmt(T.statusError, m.message);
      $('modelBtn').hidden = false;
      $('modelBtn').textContent = T.modelDownload;
      break;
    default:
      $('modelText').textContent = T.modelMissing;
      $('modelBtn').hidden = false;
      $('modelBtn').textContent = T.modelDownload;
  }
}

// ---------------------------------------------------------------- Online catalog

const PROV_TEXT = {
  zai: ['provZaiDesc', 'provZaiData'],
  deepseek: ['provDeepseekDesc', 'provDeepseekData'],
  qwen: ['provQwenDesc', 'provQwenData'],
  gemini: ['provGeminiDesc', 'provGeminiData'],
  groq: ['provGroqDesc', 'provGroqData'],
  openrouter: ['provOpenrouterDesc', 'provOpenrouterData'],
  custom: ['provCustomDesc', 'provCustomData'],
};

function buildCatalog() {
  const tpl = $('provTpl');
  const list = $('providers');
  for (const p of onlineState.providers) {
    const el = tpl.content.firstElementChild.cloneNode(true);
    const q = (sel) => el.querySelector(sel);
    const c = { el, q, testing: false };
    cards.set(p.id, c);
    q('.pname').textContent = p.name;
    q('.badges').innerHTML = '';
    for (const b of p.badges) {
      const s = document.createElement('span');
      s.textContent = T[b];
      q('.badges').appendChild(s);
    }
    q('.badges').hidden = !p.badges.length;
    q('.pdesc').textContent = T[PROV_TEXT[p.id][0]];
    q('.pdata').textContent = `ⓘ ${T[PROV_TEXT[p.id][1]]}`;
    q('.getkey').textContent = T.onlineGetKey;
    q('.getkey').hidden = !p.keyUrl;
    q('.paste').textContent = T.onlinePaste;
    q('.lurl').textContent = T.onlineUrl;
    q('.lkey').textContent = T.onlineKey;
    q('.lmodel').textContent = T.onlineModel;
    q('.model').placeholder = T.onlineModelHint;
    q('datalist').id = `models-${p.id}`;
    q('.model').setAttribute('list', `models-${p.id}`);
    q('.test').textContent = T.onlineTest;
    q('.remove').textContent = T.onlineRemove;
    q('.use').textContent = T.onlineUse;
    if (p.editableUrl) {
      q('.urlbox').hidden = false;
      q('.url').placeholder = p.id === 'custom' ? T.onlineUrlHintCustom : p.baseUrl;
      q('.nurl').textContent = p.id === 'custom' ? T.onlineUrlNoteCustom : T.onlineUrlNoteQwen;
      q('.url').value = p.url || '';
    }
    q('.model').value = p.model || '';

    q('.phead').onclick = () => {
      expanded = expanded === p.id ? null : p.id;
      renderCatalog();
    };
    q('.getkey').onclick = () => sublens.openExternal(p.keyUrl);
    q('.paste').onclick = async () => {
      const r = await sublens.online.paste(p.id);
      if (r.error) return status(c, r.error === 'empty' ? T.onlinePasteEmpty : T.onlinePasteUnknown, false);
      showResult(p.id, r.result);
    };
    q('.test').onclick = () => test(p.id);
    q('.remove').onclick = async () => {
      onlineState = await sublens.online.setKey(p.id, '');
      q('.key').value = '';
      q('.pstatus').hidden = true;
      render();
    };
    q('.use').onclick = async () => {
      await saveFields(p.id);
      const cur = onlineState.providers.find((x) => x.id === p.id);
      if (!cur.configured) return status(c, p.id === 'custom' ? T.onlineErrModelCustom : T.onlineErrNoKey, false);
      onlineState = await sublens.online.set({ provider: p.id, enabled: true });
      render();
      if (!cur.verified) test(p.id);
    };
    q('.model').onchange = async () => {
      onlineState = await sublens.online.set({ model: [p.id, q('.model').value] });
      test(p.id);
    };
    q('.model').onfocus = () => loadModels(p.id);
    list.appendChild(el);
  }
}

function renderCatalog() {
  if (!cards.size || !onlineState) return;
  const sw = $('cSwitch');
  sw.classList.toggle('on', onlineState.enabled);
  sw.setAttribute('aria-checked', String(onlineState.enabled));
  const current = onlineState.providers.find((p) => p.id === onlineState.provider);
  $('cState').textContent = !onlineState.enabled
    ? T.onlineStateOff
    : current && current.configured ? fmt(T.onlineStateOn, onlineState.summary || current.name) : T.onlineNeedSetup;
  for (const p of onlineState.providers) {
    const c = cards.get(p.id);
    const inUse = onlineState.enabled && onlineState.provider === p.id && p.configured;
    c.el.classList.toggle('inuse', inUse);
    const st = c.q('.pstate');
    st.className = 'pstate';
    if (inUse) {
      st.textContent = T.onlineInUse;
      st.classList.add('accent');
    } else if (p.verified) {
      st.textContent = T.onlineReady;
      st.classList.add('ok');
    } else if (p.hasKey || (p.id === 'custom' && p.configured)) {
      st.textContent = T.onlineUntested;
    } else {
      st.textContent = '';
    }
    const open = expanded === p.id;
    c.q('.pdetails').hidden = !open;
    c.el.classList.toggle('open', open);
    c.q('.key').placeholder = p.hasKey ? fmt(T.onlineKeySaved, p.keyTail) : (p.id === 'custom' ? T.onlineKeyHintOptional : T.onlineKeyHint);
    if (document.activeElement !== c.q('.model')) c.q('.model').value = p.model || '';
    c.q('.remove').hidden = !p.hasKey;
    c.q('.use').hidden = inUse;
  }
}

function status(c, text, ok) {
  const s = c.q('.pstatus');
  s.hidden = false;
  s.textContent = text;
  s.className = `pstatus${ok === true ? ' ok' : ok === false ? ' bad' : ''}`;
}

function errorText(id, e) {
  switch (e.kind) {
    case 'NO_KEY': return T.onlineErrNoKey;
    case 'AUTH': return id === 'qwen' ? T.onlineErrAuthQwen : T.onlineErrAuth;
    case 'LIMIT': return T.onlineErrLimit;
    case 'MODEL': return id === 'custom' ? T.onlineErrModelCustom : T.onlineErrModel;
    case 'NETWORK': return T.onlineErrNetwork;
    case 'FORMAT': return T.onlineErrFormat;
    default: return fmt(T.onlineErrServer, e.message);
  }
}

/** Writes the typed key and server address before testing. */
async function saveFields(id) {
  const c = cards.get(id);
  const p = onlineState.providers.find((x) => x.id === id);
  if (p.editableUrl && c.q('.url').value.trim() !== (p.url || '')) {
    onlineState = await sublens.online.set({ url: [id, c.q('.url').value] });
  }
  const typed = c.q('.key').value.trim();
  if (typed) {
    onlineState = await sublens.online.setKey(id, typed);
    c.q('.key').value = '';
  }
}

async function test(id) {
  const c = cards.get(id);
  if (c.testing) return;
  c.testing = true;
  c.q('.test').disabled = true;
  c.q('.test').textContent = T.onlineTesting;
  status(c, T.onlineTesting, null);
  try {
    await saveFields(id);
    showResult(id, await sublens.online.test(id));
  } finally {
    c.testing = false;
    c.q('.test').disabled = false;
    c.q('.test').textContent = T.onlineTest;
  }
}

async function showResult(id, r) {
  const c = cards.get(id);
  expanded = id;
  if (r.models && r.models.length) fillModels(id, r.models);
  if (r.ok) status(c, `${fmt(T.onlineTestOk, r.model)}\n${r.sampleIn}\n→ ${r.sampleOut}`, true);
  else status(c, `✗ ${errorText(id, r.error)}`, false);
  onlineState = await sublens.online.state();
  render();
}

function fillModels(id, models) {
  const dl = cards.get(id).q('datalist');
  dl.innerHTML = '';
  for (const m of models) dl.appendChild(new Option(m, m));
}

async function loadModels(id) {
  const dl = cards.get(id).q('datalist');
  if (dl.children.length) return;
  const r = await sublens.online.models(id);
  if (r.models) fillModels(id, r.models);
}

async function pasteAny() {
  const msg = $('pasteMsg');
  msg.hidden = false;
  msg.textContent = T.onlineTesting;
  const r = await sublens.online.paste(null);
  if (r.error) {
    msg.hidden = false;
    msg.textContent = r.error === 'empty' ? T.onlinePasteEmpty : T.onlinePasteUnknown;
    return;
  }
  const p = onlineState.providers.find((x) => x.id === r.provider);
  msg.textContent = fmt(T.onlineRecognised, p ? p.name : r.provider);
  await showResult(r.provider, r.result);
  cards.get(r.provider).el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
