'use strict';
/* global sublens */
const $ = (id) => document.getElementById(id);
const COLOURS = ['#5EEAD4', '#60A5FA', '#A78BFA', '#F472B6', '#F87171', '#FB923C', '#FACC15', '#4ADE80', '#F8FAFC'];
let T = {};
let info = null;
let settings = null;

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
  texts();
  fillLanguages();
  wire();
  render();
  renderModel(await sublens.modelState());
  sublens.onSettings((s) => {
    settings = s;
    render();
  });
  sublens.onModel(renderModel);
  sublens.onUpdate((u) => {
    $('update').hidden = false;
    $('updateText').textContent = fmt(T.update, u.version);
    $('updateBtn').onclick = () => sublens.openExternal(u.url);
  });
})();

function texts() {
  document.documentElement.lang = T._lang;
  $('tagline').textContent = T.tagline;
  $('version').textContent = `v${info.version}`;
  $('hotkey').textContent = fmt(T.hotkey, info.hotkey);
  $('lFrom').textContent = T.from;
  $('lTo').textContent = T.to;
  $('swap').title = T.swap;
  $('lMode').textContent = T.mode;
  $('modeChat').querySelector('b').textContent = T.modeChat;
  $('modeChat').querySelector('small').textContent = T.modeChatHint;
  $('modeVideo').querySelector('b').textContent = T.modeVideo;
  $('modeVideo').querySelector('small').textContent = T.modeVideoHint;
  $('lColour').textContent = T.colour;
  $('lReply').textContent = T.reply;
  $('replyOn').textContent = T.on;
  $('replyOff').textContent = T.off;
  $('lModel').textContent = T.model;
  $('ocrText').textContent = T.ocrData;
  $('deleteBtn').textContent = T.deleteData;
  $('footer').textContent = T.footer;
  $('updateBtn').textContent = T.updateGet;
  if (info.platform === 'darwin' && info.screenPermission !== 'granted') {
    $('perm').hidden = false;
    $('permText').textContent = T.permMac;
    $('permBtn').textContent = T.permOpen;
    $('permBtn').onclick = () => sublens.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture');
  }
}

function fillLanguages() {
  const sorted = [...info.languages].sort((a, b) => langName(a.code).localeCompare(langName(b.code), T._lang));
  const from = $('from');
  from.innerHTML = '';
  from.add(new Option(T.autoLatin, 'auto'));
  for (const l of sorted) from.add(new Option(langName(l.code), l.code));
  const to = $('to');
  to.innerHTML = '';
  for (const l of sorted) to.add(new Option(langName(l.code), l.code));
  $('swatches').innerHTML = COLOURS.map((c) => `<button data-c="${c}" style="background:${c}" title="${c}"></button>`).join('');
}

function wire() {
  $('lensBtn').onclick = () => sublens.showLens(!settings.lensVisible);
  $('from').onchange = (e) => sublens.setSettings({ from: e.target.value });
  $('to').onchange = (e) => sublens.setSettings({ to: e.target.value });
  $('swap').onclick = () => {
    const src = settings.from === 'auto' ? settings.lastSource : settings.from;
    if (!src || src === settings.to) return;
    sublens.setSettings({ from: settings.to, to: src });
  };
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
}

function render() {
  const on = settings.lensVisible;
  $('lensBtn').textContent = on ? T.stopLens : T.startLens;
  $('lensBtn').classList.toggle('stop', on);
  $('from').value = settings.from;
  $('to').value = settings.to;
  $('modeChat').classList.toggle('on', settings.mode !== 'video');
  $('modeVideo').classList.toggle('on', settings.mode === 'video');
  $('replyOn').classList.toggle('on', settings.reply);
  $('replyOff').classList.toggle('on', !settings.reply);
  for (const b of $('swatches').children) b.classList.toggle('on', b.dataset.c.toLowerCase() === settings.color.toLowerCase());
  document.documentElement.style.setProperty('--accent', settings.color);
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
