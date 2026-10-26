'use strict';
/**
 * SubLens desktop – main process.
 * Two windows: the settings panel and the lens (transparent, always on top, excluded from screen capture).
 * The lens window captures the screen region under itself and asks the engine (core.js) to read and translate it –
 * offline, or with the online AI service the user has set up (online.js; keys encrypted with safeStorage).
 */
const {
  app, BrowserWindow, ipcMain, screen, desktopCapturer, session, Tray, Menu, nativeImage,
  globalShortcut, clipboard, shell, systemPreferences, net, safeStorage,
} = require('electron');
const path = require('path');
const fs = require('fs');
const core = require('./core');
const online = require('./online');
const { LANGS, byCode, fromLocale } = require('./languages');
const i18n = require('./i18n');

const REPO = 'Elphax89/sublense';
const DOWNLOAD_PAGE = 'https://elphax89.github.io/sublense/#desktop';
const HOTKEY = 'CommandOrControl+Shift+L';
const COMPOSER_H = 64;

let panel = null;
let lens = null;
let tray = null;
let settings = null;
let texts = null;
let lastSource = null;
let modelState = { status: 'unknown' };
let quitting = false;
let updateInfo = null; // newer version found – shown again when the panel reloads

// ---------------------------------------------------------------- Settings

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');

function defaults() {
  return {
    from: 'auto',
    to: fromLocale(app.getLocale()),
    replyTo: 'auto',
    mode: 'chat',
    color: '#5EEAD4',
    reply: true,
    replyAbove: false,
    lensVisible: false,
    lensBounds: null,
    uiLang: '', // '' = system language
    // Online translation (off by default; needs the user's own key)
    online: false,
    provider: 'zai',
    models: {},
    urls: {},
    verified: {},
  };
}

function loadSettings() {
  try {
    settings = { ...defaults(), ...JSON.parse(fs.readFileSync(settingsFile(), 'utf8')) };
  } catch {
    settings = defaults();
  }
  if (!byCode(settings.to)) settings.to = 'en';
  if (settings.from !== 'auto' && !byCode(settings.from)) settings.from = 'auto';
  for (const k of ['models', 'urls', 'verified']) if (!settings[k] || typeof settings[k] !== 'object') settings[k] = {};
  if (!online.byId(settings.provider)) settings.provider = 'zai';
}

let saveTimer = null;
function saveSettings() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      fs.mkdirSync(app.getPath('userData'), { recursive: true });
      fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2));
    } catch (e) {
      console.warn('saving settings failed', e);
    }
  }, 300);
}

function updateSettings(patch) {
  const before = { ...settings };
  settings = { ...settings, ...patch };
  saveSettings();
  if (before.uiLang !== settings.uiLang) {
    // New app language: reload both windows with the new texts.
    texts = i18n.table(settings.uiLang || app.getLocale());
    for (const w of [panel, lens]) if (w && !w.isDestroyed()) w.webContents.reload();
    updateTray();
    return;
  }
  broadcast('settings', publicSettings());
  if (before.reply !== settings.reply) layoutLensForComposer(before.reply);
}

function publicSettings() {
  return { ...settings, lastSource, onlineActive: onlineActive(), onlineSummary: onlineSummary() };
}

function broadcast(channel, payload) {
  for (const w of [panel, lens]) if (w && !w.isDestroyed()) w.webContents.send(channel, payload);
}

// ---------------------------------------------------------------- Windows

const preload = path.join(__dirname, 'preload.js');

function createPanel() {
  panel = new BrowserWindow({
    width: 440,
    height: 720,
    minWidth: 380,
    minHeight: 520,
    title: 'SubLens',
    backgroundColor: '#0E1116',
    show: false,
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    webPreferences: { preload, contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  panel.loadFile(path.join(__dirname, 'ui', 'panel.html'));
  panel.once('ready-to-show', () => panel.show());
  panel.webContents.on('did-finish-load', () => {
    if (updateInfo) panel.webContents.send('update', updateInfo);
  });
  panel.on('close', (e) => {
    if (quitting) return;
    if (lens && lens.isVisible()) {
      // The lens keeps working; SubLens stays in the tray / menu bar.
      e.preventDefault();
      panel.hide();
    }
  });
  panel.on('closed', () => {
    panel = null;
    if (!quitting && !(lens && lens.isVisible())) app.quit();
  });
}

function showPanel() {
  if (!panel) createPanel();
  else {
    panel.show();
    panel.focus();
  }
}

function defaultLensBounds() {
  const d = screen.getPrimaryDisplay().workArea;
  const w = Math.min(760, d.width - 80);
  const h = Math.min(320, d.height - 200) + (settings.reply ? COMPOSER_H : 0);
  return { x: Math.round(d.x + (d.width - w) / 2), y: Math.round(d.y + d.height * 0.45), width: w, height: h };
}

function sanitizeBounds(b) {
  const area = screen.getDisplayMatching(b).workArea;
  const w = Math.max(MIN_W, Math.min(b.width, area.width));
  const h = Math.max(MIN_H, Math.min(b.height, area.height));
  const x = Math.min(Math.max(b.x, area.x - w + 120), area.x + area.width - 120);
  const y = Math.min(Math.max(b.y, area.y), area.y + area.height - 60);
  return { x: Math.round(x), y: Math.round(y), width: Math.round(w), height: Math.round(h) };
}

const MIN_W = 260;
const MIN_H = 150;

function createLens() {
  const bounds = sanitizeBounds(settings.lensBounds || defaultLensBounds());
  lens = new BrowserWindow({
    ...bounds,
    frame: false,
    transparent: true,
    resizable: false, // resized by our own handles (transparent windows have no native border)
    movable: true,
    hasShadow: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    fullscreenable: false,
    minimizable: false,
    maximizable: false,
    show: false,
    backgroundColor: '#00000000',
    title: 'SubLens Lens',
    webPreferences: { preload, contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false },
  });
  lens.setAlwaysOnTop(true, 'screen-saver');
  if (process.platform === 'darwin') lens.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  // The lens must not see itself: excluded from screen capture (Windows 10 2004+, macOS).
  lens.setContentProtection(true);
  lens.setIgnoreMouseEvents(true, { forward: true });
  lens.loadFile(path.join(__dirname, 'ui', 'lens.html'));
  if (process.env.SUBLENS_DEBUG) {
    lens.webContents.on('console-message', (e, ...rest) => console.log('[lens]', e.message !== undefined ? e.message : rest[1]));
  }
  lens.once('ready-to-show', () => {
    lens.showInactive();
    sendGeometry();
  });
  lens.on('moved', sendGeometry);
  lens.on('resized', sendGeometry);
  lens.on('closed', () => {
    lens = null;
    updateTray();
  });
}

function geometry() {
  if (!lens || lens.isDestroyed()) return null;
  const b = lens.getBounds();
  const d = screen.getDisplayMatching(b);
  return { bounds: b, display: { id: d.id, bounds: d.bounds, scaleFactor: d.scaleFactor } };
}

function sendGeometry() {
  const g = geometry();
  if (g) lens.webContents.send('lens:geometry', g);
}
ipcMain.handle('lens:get-geometry', () => geometry());

function showLens(show) {
  if (show) {
    if (!lens) createLens();
    else lens.showInactive();
  } else if (lens) {
    lens.close();
  }
  settings.lensVisible = !!show;
  saveSettings();
  broadcast('settings', publicSettings());
  updateTray();
}

/** Turning the reply line on or off keeps the lens frame where it is and grows/shrinks the window. */
function layoutLensForComposer(hadReply) {
  if (!lens) return;
  const b = lens.getBounds();
  const delta = (settings.reply ? COMPOSER_H : 0) - (hadReply ? COMPOSER_H : 0);
  const nb = { ...b, height: Math.max(MIN_H, b.height + delta) };
  if (settings.replyAbove) nb.y = b.y - delta;
  lens.setBounds(nb);
  settings.lensBounds = lens.getBounds();
  saveSettings();
}

// ---------------------------------------------------------------- Move / resize (our own handles)

let drag = null;

ipcMain.on('lens:drag', (e, kind) => {
  if (!lens) return;
  const start = screen.getCursorScreenPoint();
  const b = lens.getBounds();
  clearInterval(drag?.timer);
  drag = {
    timer: setInterval(() => {
      if (!lens) return endDrag();
      const p = screen.getCursorScreenPoint();
      const dx = p.x - start.x;
      const dy = p.y - start.y;
      if (kind === 'move') {
        lens.setBounds({ x: b.x + dx, y: b.y + dy, width: b.width, height: b.height });
      } else if (kind === 'resize') {
        lens.setBounds({ x: b.x, y: b.y, width: Math.max(MIN_W, b.width + dx), height: Math.max(MIN_H, b.height + dy) });
      }
    }, 16),
  };
});

function endDrag() {
  if (!drag) return;
  clearInterval(drag.timer);
  drag = null;
  if (lens) {
    settings.lensBounds = sanitizeBounds(lens.getBounds());
    lens.setBounds(settings.lensBounds);
    saveSettings();
    sendGeometry();
  }
}
ipcMain.on('lens:drag-end', endDrag);

ipcMain.on('lens:hit', (e, hit) => {
  if (!lens) return;
  if (hit) lens.setIgnoreMouseEvents(false);
  else lens.setIgnoreMouseEvents(true, { forward: true });
});

ipcMain.on('lens:focus', () => {
  if (lens) lens.focus();
});

// ---------------------------------------------------------------- Screen capture source

function setupCapture() {
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    try {
      const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
      const target = lens ? screen.getDisplayMatching(lens.getBounds()) : screen.getPrimaryDisplay();
      const src = sources.find((s) => String(s.display_id) === String(target.id)) || sources[0];
      callback(src ? { video: src } : {});
    } catch (e) {
      console.warn('screen source failed', e);
      callback({});
    }
  }, { useSystemPicker: false });
}

// ---------------------------------------------------------------- Engine

function tessFor(code) {
  if (code === 'auto') return 'eng';
  const l = byCode(code);
  if (!l) return 'eng';
  // Latin languages also read English words (names, brands) well with the English data added.
  return l.tess === 'eng' ? 'eng' : `${l.tess}+eng`;
}

let modelLoading = null;
function ensureModel() {
  if (modelState.status === 'ready') return Promise.resolve();
  if (!modelLoading) {
    modelState = { status: core.modelStatus().ready ? 'loading' : 'downloading', loaded: 0, total: 0 };
    broadcast('model', modelState);
    modelLoading = core.getTranslator()
      .then(() => {
        modelState = { status: 'ready', bytes: core.modelStatus().bytes };
        broadcast('model', modelState);
      })
      .catch((e) => {
        modelState = { status: 'error', message: String(e.message || e) };
        broadcast('model', modelState);
        throw e;
      })
      .finally(() => {
        modelLoading = null;
      });
  }
  return modelLoading;
}

ipcMain.handle('engine:process', async (e, { png }) => {
  const from = settings.from;
  const tess = tessFor(from);
  const blocks = await core.ocr(Buffer.from(png), tess);
  if (!blocks.length) return { blocks: [], source: null, translations: [] };
  const joined = blocks.map((b) => b.text).join('\n');
  let source = from === 'auto' ? core.detect(joined, tess) : from;
  if (!source && from === 'auto') source = lastSource || 'en';
  if (source !== lastSource) {
    lastSource = source;
    broadcast('settings', publicSettings());
  }
  const target = settings.to;
  if (source === target) return { blocks, source, translations: blocks.map((b) => b.text) };
  const texts = blocks.map((b) => b.text);
  let notice = null;
  if (onlineActive()) {
    try {
      const translations = await translateScreenOnline(texts, from === 'auto' ? null : source, target);
      remember(texts);
      return { blocks, source, translations, engine: 'online' };
    } catch (e) {
      // Not reachable, limit reached, key rejected … → this screen (and the next ones for a while) offline.
      notice = pauseOnline(e);
    }
  }
  remember(texts);
  if (modelState.status !== 'ready') {
    if (!core.modelStatus().ready && modelState.status !== 'downloading') return { blocks, source, translations: null, needModel: true, notice };
    await ensureModel();
  }
  const translations = await core.translate(texts, source, target);
  return { blocks, source, translations, notice };
});

// Development only: SUBLENS_FAKE_TRANSLATE=1 marks text instead of translating (no model download needed).
if (process.env.SUBLENS_FAKE_TRANSLATE) {
  core.translate = async (list, src, tgt) => list.map((t) => `[${tgt}] ${t}`);
  core.modelStatus = () => ({ ready: true, bytes: 0 });
  core.getTranslator = async () => null;
}

ipcMain.handle('engine:reply', async (e, { text }) => {
  const own = settings.to;
  const to = settings.replyTo === 'auto' ? (lastSource || (settings.from !== 'auto' ? settings.from : 'en')) : settings.replyTo;
  if (!text.trim()) return { text: '', lang: to };
  if (own !== to && onlineActive()) {
    try {
      // The chat on screen as context, so the reply matches its tone.
      const [out] = await translateOnline([text], own, to, recent.slice(-online.MAX_CONTEXT), true);
      return { text: out, lang: to, engine: 'online' };
    } catch (err) {
      pauseOnline(err);
    }
  }
  await ensureModel();
  const [out] = own === to ? [text] : await core.translate([text], own, to);
  return { text: out, lang: to };
});

ipcMain.handle('engine:download-model', async () => {
  await ensureModel();
  return modelState;
});

ipcMain.handle('engine:model-state', () => {
  if (modelState.status === 'unknown' || modelState.status === 'missing') {
    const st = core.modelStatus();
    modelState = st.ready ? { status: 'downloaded', bytes: st.bytes } : { status: 'missing' };
  }
  return modelState;
});

ipcMain.handle('engine:delete', async () => {
  await core.deleteDownloads();
  modelState = { status: 'missing' };
  broadcast('model', modelState);
  return true;
});

// ---------------------------------------------------------------- Online translation

const client = online.createClient((url, opts) => net.fetch(url, opts));
const SAMPLE_ZH = '哈哈，你今天晚上有空吗？我们去吃火锅吧，我请客！';
const SAMPLE_EN = "Haha, are you free tonight? Let's go for hotpot, my treat!";
let keys = null; // provider id → key, decrypted on first use (macOS may ask for Keychain access once)
let pausedUntil = 0;
let pauseStep = 0;
let lastNotice = null;
const onlineCache = new Map();
const recent = []; // recently seen lines – context for the next screen and for your reply

const keysFile = () => path.join(app.getPath('userData'), 'keys.json');

function loadKeys() {
  if (keys) return keys;
  keys = {};
  try {
    const raw = JSON.parse(fs.readFileSync(keysFile(), 'utf8'));
    for (const [id, v] of Object.entries(raw)) {
      try {
        keys[id] = v.enc === 'safe' ? safeStorage.decryptString(Buffer.from(v.data, 'base64')) : Buffer.from(v.data, 'base64').toString('utf8');
      } catch (e) {
        console.warn('stored key unreadable', id);
      }
    }
  } catch {
    // no keys yet
  }
  return keys;
}

function saveKeys() {
  const out = {};
  const safe = safeStorage.isEncryptionAvailable();
  for (const [id, k] of Object.entries(keys || {})) {
    if (!k) continue;
    // Encrypted with the system's key store (Windows DPAPI / macOS Keychain). Only on Linux without a key ring as plain base64.
    out[id] = safe
      ? { enc: 'safe', data: safeStorage.encryptString(k).toString('base64') }
      : { enc: 'plain', data: Buffer.from(k, 'utf8').toString('base64') };
  }
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  fs.writeFileSync(keysFile(), JSON.stringify(out), { mode: 0o600 });
}

function keyFor(id) {
  return loadKeys()[id] || '';
}

function conn(id = settings.provider) {
  const p = online.byId(id);
  if (!p) return null;
  return {
    provider: p,
    url: String(settings.urls[id] || p.baseUrl).trim().replace(/\/+$/, ''),
    key: keyFor(id),
    model: settings.models[id] || p.defaultModel,
  };
}

function configured(id) {
  const c = conn(id);
  if (!c) return false;
  if (id === 'custom') return !!c.url && !!settings.models.custom;
  return !!c.key;
}

function onlineActive() {
  return !!settings && !!settings.online && Date.now() >= pausedUntil && configured(settings.provider);
}

function onlineSummary() {
  if (!settings || !settings.online || !configured(settings.provider)) return null;
  const c = conn();
  return c.model ? `${c.provider.name} · ${c.model}` : c.provider.name;
}

function resumeOnline() {
  pausedUntil = 0;
  pauseStep = 0;
  lastNotice = null;
}

/** Pause after an error, like on Android. Returns the notice for the lens (only when it changed). */
function pauseOnline(e) {
  const kind = (e && e.kind) || 'SERVER';
  const now = Date.now();
  if (kind === 'LIMIT') pausedUntil = now + Math.min(15 * 60000, 60000 * 2 ** Math.min(pauseStep++, 4));
  else if (kind === 'NETWORK') pausedUntil = now + 20000;
  else if (kind === 'AUTH' || kind === 'NO_KEY') pausedUntil = now + 10 * 60000;
  else pausedUntil = now + 30000;
  console.warn('online translation paused:', kind, e && e.message);
  const key = { LIMIT: 'onlineFallbackLimit', NETWORK: 'onlineFallbackNetwork', AUTH: 'onlineFallbackAuth', NO_KEY: 'onlineFallbackAuth' }[kind] || 'onlineFallbackOther';
  if (key === lastNotice) return null;
  lastNotice = key;
  return texts[key];
}

function remember(lines) {
  for (const l of lines) {
    const i = recent.indexOf(l);
    if (i >= 0) recent.splice(i, 1);
    recent.push(l);
  }
  while (recent.length > online.MAX_CONTEXT * 2) recent.shift();
}

/** With the chosen provider; a renamed or retired model is replaced by a current one once. */
async function translateOnline(list, from, to, context, reply) {
  const c = conn();
  try {
    const out = await client.translate(c, list, from, to, context, reply);
    pauseStep = 0;
    lastNotice = null;
    return out;
  } catch (e) {
    if (e.kind !== 'MODEL') throw e;
    const fresh = online.pickModel(c.provider, await client.listModels(c.url, c.key));
    if (!fresh || fresh === c.model) throw e;
    settings.models[c.provider.id] = fresh;
    saveSettings();
    return client.translate({ ...c, model: fresh }, list, from, to, context, reply);
  }
}

/** Only new or changed lines are sent; the rest of the screen goes along as context. */
async function translateScreenOnline(list, from, to) {
  const prefix = `${settings.from}>${to}|`;
  const todo = [...new Set(list.filter((t) => !onlineCache.has(prefix + t)))];
  if (todo.length) {
    const context = [...new Set(recent.concat(list))].filter((t) => !todo.includes(t)).slice(-online.MAX_CONTEXT);
    const out = await translateOnline(todo, from, to, context, false);
    todo.forEach((t, i) => onlineCache.set(prefix + t, out[i] || t));
    while (onlineCache.size > 1500) onlineCache.delete(onlineCache.keys().next().value);
  }
  return list.map((t) => onlineCache.get(prefix + t) || t);
}

function onlineState() {
  loadKeys();
  return {
    enabled: !!settings.online,
    provider: settings.provider,
    active: onlineActive(),
    summary: onlineSummary(),
    providers: online.PROVIDERS.map((p) => ({
      id: p.id,
      name: p.name,
      keyUrl: p.keyUrl,
      baseUrl: p.baseUrl,
      badges: p.badges,
      editableUrl: !!p.editableUrl,
      url: settings.urls[p.id] || p.baseUrl,
      model: settings.models[p.id] || p.defaultModel,
      hasKey: !!keyFor(p.id),
      keyTail: keyFor(p.id).slice(-4),
      verified: !!settings.verified[p.id] && configured(p.id),
      configured: configured(p.id),
    })),
  };
}

function setKey(id, key) {
  loadKeys();
  const k = online.cleanKey(key);
  if (k) keys[id] = k;
  else delete keys[id];
  saveKeys();
  settings.verified[id] = false;
  saveSettings();
  resumeOnline();
}

function errorInfo(e) {
  return { kind: (e && e.kind) || 'SERVER', message: online.shortError(e && e.message) };
}

/** Reads the model list (that also checks the key), picks a model and translates a sample. */
async function testProvider(id) {
  const p = online.byId(id);
  const c = conn(id);
  let models = [];
  let model = null;
  try {
    if (!c.key && id !== 'custom') throw new online.OnlineError('NO_KEY', 'no key');
    if (!c.url) throw new online.OnlineError('NETWORK', 'no address');
    try {
      models = await client.listModels(c.url, c.key);
    } catch (e) {
      if (e.kind === 'AUTH' || e.kind === 'NETWORK') throw e;
      models = []; // some services have no model list – then the default is used
    }
    const auto = online.pickModel(p, models) || (id === 'custom' ? models[0] : null) || p.defaultModel || null;
    model = settings.models[id] || auto;
    if (!model) throw new online.OnlineError('MODEL', 'no model');
    const zh = settings.to.startsWith('zh');
    const sample = zh ? SAMPLE_EN : SAMPLE_ZH;
    let out;
    try {
      out = await client.translate({ ...c, model }, [sample], zh ? 'en' : 'zh-Hans', settings.to);
    } catch (e) {
      // The saved model is gone (renamed or retired): use the recommended one.
      if (e.kind !== 'MODEL' || !auto || auto === model) throw e;
      model = auto;
      out = await client.translate({ ...c, model }, [sample], zh ? 'en' : 'zh-Hans', settings.to);
    }
    // Tested successfully = that's the service you want to use.
    settings.models[id] = model;
    settings.verified[id] = true;
    settings.provider = id;
    settings.online = true;
    onlineCache.clear();
    resumeOnline();
    saveSettings();
    broadcast('settings', publicSettings());
    return { ok: true, model, models: online.usableModels(p, models), sampleIn: sample, sampleOut: out[0] };
  } catch (e) {
    settings.verified[id] = false;
    saveSettings();
    return { ok: false, model, models: online.usableModels(p, models), error: errorInfo(e) };
  }
}

ipcMain.handle('online:state', () => onlineState());
ipcMain.handle('online:set', (e, patch) => {
  if ('enabled' in patch) {
    settings.online = !!patch.enabled;
    if (settings.online) resumeOnline();
  }
  if (patch.provider && online.byId(patch.provider)) settings.provider = patch.provider;
  if (patch.model) {
    const [id, m] = patch.model;
    if (m) settings.models[id] = String(m).trim();
    else delete settings.models[id];
    settings.verified[id] = false;
  }
  if (patch.url) {
    const [id, u] = patch.url;
    const p = online.byId(id);
    const v = String(u || '').trim().replace(/\/+$/, '');
    if (!v || (p && v === p.baseUrl)) delete settings.urls[id];
    else settings.urls[id] = v;
    settings.verified[id] = false;
  }
  onlineCache.clear();
  saveSettings();
  broadcast('settings', publicSettings());
  return onlineState();
});
ipcMain.handle('online:set-key', (e, { id, key }) => {
  if (online.byId(id)) setKey(id, key);
  return onlineState();
});
ipcMain.handle('online:test', (e, id) => testProvider(id));
ipcMain.handle('online:models', async (e, id) => {
  const p = online.byId(id);
  const c = conn(id);
  try {
    return { models: online.usableModels(p, await client.listModels(c.url, c.key)) };
  } catch (err) {
    return { error: errorInfo(err) };
  }
});
/**
 * Paste from the clipboard. With an id: into that service. Without: recognise the service by the key's
 * shape and test it (DeepSeek and Qwen keys look alike – the other one is tried when the first rejects it).
 */
ipcMain.handle('online:paste', async (e, id) => {
  const key = online.cleanKey(clipboard.readText());
  if (!key) return { error: 'empty' };
  const candidates = id ? [online.byId(id)].filter(Boolean) : online.guess(key);
  if (!candidates.length) return { error: 'unknown' };
  let result = null;
  for (const p of candidates) {
    setKey(p.id, key);
    result = await testProvider(p.id);
    if (result.ok || result.error.kind !== 'AUTH' || p === candidates[candidates.length - 1]) return { provider: p.id, result };
    setKey(p.id, ''); // not this one
  }
  return { provider: candidates[0].id, result };
});

// ---------------------------------------------------------------- Other IPC

ipcMain.handle('app:info', () => ({
  version: app.getVersion(),
  platform: process.platform,
  hotkey: HOTKEY.replace('CommandOrControl', process.platform === 'darwin' ? '⌘' : 'Ctrl'),
  texts,
  languages: LANGS.map((l) => ({ code: l.code, name: l.name })),
  screenPermission: process.platform === 'darwin' ? systemPreferences.getMediaAccessStatus('screen') : 'granted',
}));
ipcMain.handle('settings:get', () => publicSettings());
ipcMain.handle('settings:set', (e, patch) => {
  updateSettings(patch);
  return publicSettings();
});
ipcMain.handle('lens:show', (e, show) => showLens(show));
ipcMain.on('panel:show', () => showPanel());
ipcMain.on('clipboard:write', (e, text) => clipboard.writeText(String(text)));
ipcMain.on('open:external', (e, url) => {
  if (/^https:\/\//.test(url) || url.startsWith('x-apple.systempreferences:')) shell.openExternal(url);
});

// ---------------------------------------------------------------- Tray, shortcut, updates

function trayImage() {
  const file = path.join(__dirname, '..', 'assets', process.platform === 'darwin' ? 'trayTemplate.png' : 'tray.png');
  const img = nativeImage.createFromPath(file);
  if (process.platform === 'darwin') img.setTemplateImage(true);
  return img;
}

function updateTray() {
  if (!tray) return;
  const visible = !!(lens && lens.isVisible());
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: visible ? texts.stopLens : texts.startLens, click: () => showLens(!visible) },
    { label: texts.settings, click: showPanel },
    { type: 'separator' },
    { label: 'Quit SubLens', role: 'quit' },
  ]));
}

function createTray() {
  tray = new Tray(trayImage());
  tray.setToolTip('SubLens');
  tray.on('click', () => showLens(!(lens && lens.isVisible())));
  updateTray();
}

async function checkForUpdate() {
  try {
    const res = await net.fetch(`https://api.github.com/repos/${REPO}/releases/tags/desktop-latest`, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'SubLens-Desktop' },
    });
    if (!res.ok) return;
    const rel = await res.json();
    const m = /(\d+)\.(\d+)\.(\d+)/.exec(rel.name || '');
    if (!m) return;
    const latest = m.slice(1).map(Number);
    const mine = app.getVersion().split('.').map(Number);
    const newer = latest[0] > mine[0] || (latest[0] === mine[0] && (latest[1] > mine[1] || (latest[1] === mine[1] && latest[2] > mine[2])));
    if (newer) {
      updateInfo = { version: latest.join('.'), url: DOWNLOAD_PAGE };
      broadcast('update', updateInfo);
    }
  } catch {
    // offline – no problem
  }
}

// ---------------------------------------------------------------- App lifecycle

/**
 * `SubLens --selftest`: checks that the packaged app can load its engine (used by CI on Windows and macOS):
 * Transformers.js with the native ONNX runtime, and Tesseract reading a small picture.
 */
async function selfTest() {
  const os = require('os');
  // GUI apps on Windows have no console: the result also goes to a file the CI can read.
  const out = process.env.SUBLENS_SELFTEST_OUT || path.join(os.tmpdir(), 'sublens-selftest.txt');
  try {
    fs.writeFileSync(out, '');
  } catch {
    // ignore
  }
  const log = (m) => {
    process.stdout.write(`[selftest] ${m}\n`);
    try {
      fs.appendFileSync(out, `${m}\n`);
    } catch {
      // ignore
    }
  };
  try {
    let t = Date.now();
    const tf = await import('@huggingface/transformers');
    log(`transformers ${tf.env.version} + onnxruntime loaded in ${Date.now() - t} ms`);
    core.init({ dataDir: path.join(os.tmpdir(), 'sublens-selftest'), onProgress: () => {} });
    t = Date.now();
    const blocks = await core.ocr(fs.readFileSync(path.join(__dirname, '..', 'assets', 'selftest.png')), 'eng');
    const text = blocks.map((b) => b.text).join(' ');
    log(`OCR in ${Date.now() - t} ms: ${text}`);
    await core.shutdown();
    if (!/self\s*test/i.test(text)) throw new Error('OCR result unexpected');
    log('OK');
    app.exit(0);
  } catch (e) {
    log(`FAILED: ${e && e.stack}`);
    app.exit(1);
  }
}

if (process.argv.includes('--selftest')) {
  app.whenReady().then(selfTest);
} else if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showPanel());

  app.whenReady().then(() => {
    loadSettings();
    texts = i18n.table(settings.uiLang || app.getLocale());
    core.init({
      dataDir: app.getPath('userData'),
      onProgress: (p) => {
        if (p.kind === 'model' && p.status === 'downloading') {
          modelState = { status: 'downloading', loaded: p.loaded, total: p.total };
          broadcast('model', modelState);
        }
      },
    });
    setupCapture();
    createPanel();
    createTray();
    globalShortcut.register(HOTKEY, () => showLens(!(lens && lens.isVisible())));
    if (settings.lensVisible) showLens(true);
    setTimeout(checkForUpdate, 4000);
    screen.on('display-metrics-changed', sendGeometry);
  });

  app.on('before-quit', () => {
    quitting = true;
  });
  app.on('will-quit', () => {
    globalShortcut.unregisterAll();
    core.shutdown();
  });
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !tray) app.quit();
  });
  app.on('activate', () => showPanel());
}
