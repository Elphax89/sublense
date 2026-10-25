'use strict';
/**
 * SubLens desktop – main process.
 * Two windows: the settings panel and the lens (transparent, always on top, excluded from screen capture).
 * The lens window captures the screen region under itself and asks the engine (core.js) to read and translate it.
 */
const {
  app, BrowserWindow, ipcMain, screen, desktopCapturer, session, Tray, Menu, nativeImage,
  globalShortcut, clipboard, shell, systemPreferences, net,
} = require('electron');
const path = require('path');
const fs = require('fs');
const core = require('./core');
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
  broadcast('settings', publicSettings());
  if (before.reply !== settings.reply) layoutLensForComposer(before.reply);
}

function publicSettings() {
  return { ...settings, lastSource };
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
  if (modelState.status !== 'ready') {
    if (!core.modelStatus().ready && modelState.status !== 'downloading') return { blocks, source, translations: null, needModel: true };
    await ensureModel();
  }
  const translations = await core.translate(blocks.map((b) => b.text), source, target);
  return { blocks, source, translations };
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
    if (newer) broadcast('update', { version: latest.join('.'), url: DOWNLOAD_PAGE });
  } catch {
    // offline – no problem
  }
}

// ---------------------------------------------------------------- App lifecycle

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showPanel());

  app.whenReady().then(() => {
    loadSettings();
    texts = i18n.table(app.getLocale());
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
