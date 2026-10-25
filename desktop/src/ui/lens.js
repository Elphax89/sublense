'use strict';
/* global sublens */
/**
 * The lens: captures the screen under itself, asks the engine to read and translate it,
 * and draws each translation over its original in the original's colours.
 * The lens window is excluded from screen capture, so it never reads its own translations.
 */
const $ = (id) => document.getElementById(id);
const INSET = 6; // frame line – text is read inside it
const TICK_MS = 400;
const SIG_X = 48;
const SIG_Y = 24;
const CELL = 22; // brightness difference that counts as "changed"
const SETTLE_MAX_MS = 2500;
const ANIM_HOLD_MS = 6000;

let T = {};
let info = null;
let settings = null;
let geom = null;
let stream = null;
let busy = false;
let dragging = false;
let lastHit = false;
let modelState = { status: 'unknown' };

const video = $('video');
const boxesEl = $('boxes');
const shown = new Map(); // key → element

// ---------------------------------------------------------------- Setup

(async function init() {
  info = await sublens.info();
  T = info.texts;
  settings = await sublens.getSettings();
  modelState = await sublens.modelState();
  applySettings();
  setupTexts();
  setupHitTesting();
  setupControls();
  setupComposer();
  new ResizeObserver(drawBorder).observe($('frame'));
  sublens.onSettings((s) => {
    const changedLangs = !settings || s.from !== settings.from || s.to !== settings.to;
    settings = s;
    applySettings();
    if (changedLangs) resetReading();
  });
  const onGeometry = (g) => {
    if (!g) return;
    const displayChanged = !geom || g.display.id !== geom.display.id || g.display.scaleFactor !== geom.display.scaleFactor;
    geom = g;
    resetReading();
    if (displayChanged) startCapture();
  };
  sublens.onGeometry(onGeometry);
  onGeometry(await sublens.getGeometry());
  sublens.onModel((m) => {
    modelState = m;
    showModelStatus();
    if (m.status === 'ready') resetReading();
  });
  setInterval(tick, TICK_MS);
})();

function fmt(s, ...args) {
  return String(s).replace(/\{(\d)\}/g, (_, i) => args[+i] ?? '');
}

function langName(code) {
  try {
    return new Intl.DisplayNames([T._lang || 'en'], { type: 'language' }).of(code) || code;
  } catch {
    return code;
  }
}

function setupTexts() {
  $('move').title = T.move;
  $('grip').title = T.resize;
  $('btnSettings').title = T.settings;
  $('btnClose').title = T.close;
  $('btnVideo').title = T.video;
  $('btnSwap').title = T.swapReply;
  $('copy').title = T.copy;
  $('peek').textContent = T.preview;
}

function applySettings() {
  document.documentElement.style.setProperty('--lens', settings.color);
  $('wrap').classList.toggle('above', !!settings.replyAbove);
  $('composer').hidden = !settings.reply;
  $('btnSwap').hidden = !settings.reply;
  $('btnVideo').className = settings.mode === 'video' ? 'on' : 'off';
  const reply = replyLang();
  $('chip').textContent = `${short(settings.to)} → ${short(reply)}`;
  const input = $('input');
  if (!input.classList.contains('done')) input.placeholder = fmt(T.replyPlaceholder, langName(settings.to));
  drawBorder();
}

function replyLang() {
  if (settings.replyTo && settings.replyTo !== 'auto') return settings.replyTo;
  return settings.lastSource || (settings.from !== 'auto' ? settings.from : 'en');
}

function short(code) {
  if (code === 'zh-Hans') return 'ZH简';
  if (code === 'zh-Hant') return 'ZH繁';
  return String(code || '').toUpperCase();
}

function drawBorder() {
  const f = $('frame');
  const w = f.clientWidth;
  const h = f.clientHeight;
  if (!w || !h) return;
  const s = 3;
  const r = 14;
  const len = 26;
  const l = s;
  const t = s;
  const rt = w - s;
  const b = h - s;
  const corners = `M${l},${t + len} L${l},${t + r} Q${l},${t} ${l + r},${t} L${l + len},${t}
    M${rt - len},${t} L${rt - r},${t} Q${rt},${t} ${rt},${t + r} L${rt},${t + len}
    M${rt},${b - len} L${rt},${b - r} Q${rt},${b} ${rt - r},${b} L${rt - len},${b}
    M${l + len},${b} L${l + r},${b} Q${l},${b} ${l},${b - r} L${l},${b - len}`;
  const c = settings ? settings.color : '#5EEAD4';
  $('border').innerHTML = `<rect x="${s}" y="${s}" width="${w - 2 * s}" height="${h - 2 * s}" rx="${r}" fill="none" stroke="${c}" stroke-opacity=".55" stroke-width="1.5"/>
    <path d="${corners}" fill="none" stroke="#000" stroke-opacity=".35" stroke-width="7" stroke-linecap="round"/>
    <path d="${corners}" fill="none" stroke="${c}" stroke-width="4" stroke-linecap="round"/>`;
}

// ---------------------------------------------------------------- Mouse: click-through except on controls

function setupHitTesting() {
  const update = (hit) => {
    if (hit === lastHit) return;
    lastHit = hit;
    sublens.setHit(hit);
  };
  document.addEventListener('mousemove', (e) => {
    if (dragging) return;
    update(!!e.target.closest('[data-hit]'));
  });
  document.addEventListener('mouseleave', () => {
    if (!dragging && document.activeElement !== $('input')) update(false);
  });
}

function startDrag(kind, e) {
  e.preventDefault();
  dragging = true;
  clearBoxes();
  sublens.drag(kind);
  const end = () => {
    dragging = false;
    sublens.dragEnd();
    window.removeEventListener('mouseup', end);
  };
  window.addEventListener('mouseup', end);
}

function setupControls() {
  $('move').addEventListener('mousedown', (e) => startDrag('move', e));
  $('grip').addEventListener('mousedown', (e) => startDrag('resize', e));
  $('btnSettings').addEventListener('click', () => sublens.showPanel());
  $('btnClose').addEventListener('click', () => sublens.showLens(false));
  $('btnVideo').addEventListener('click', () => {
    const mode = settings.mode === 'video' ? 'chat' : 'video';
    sublens.setSettings({ mode });
    flashStatus(mode === 'video' ? T.videoOn : T.videoOff);
  });
  $('btnSwap').addEventListener('click', () => sublens.setSettings({ replyAbove: !settings.replyAbove }));
  $('statusBtn').addEventListener('click', () => {
    sublens.downloadModel().catch(() => {});
  });
}

// ---------------------------------------------------------------- Status line

let statusTimer = null;
function setStatus(text, button) {
  const st = $('status');
  st.hidden = !text;
  $('statusText').textContent = text || '';
  const btn = $('statusBtn');
  btn.hidden = !button;
  if (button) btn.textContent = button;
}

function flashStatus(text) {
  setStatus(text);
  clearTimeout(statusTimer);
  statusTimer = setTimeout(() => showModelStatus(), 2600);
}

let needModel = false;
function showModelStatus() {
  if (modelState.status === 'downloading') {
    const pct = modelState.total ? Math.floor((modelState.loaded / modelState.total) * 100) : 0;
    setStatus(fmt(T.statusModel, pct));
  } else if (modelState.status === 'loading') {
    setStatus(T.modelLoading);
  } else if (needModel && modelState.status !== 'ready') {
    setStatus(T.modelMissing, `${T.modelDownload} (≈ 900 MB)`);
  } else {
    setStatus(null);
  }
}

// ---------------------------------------------------------------- Capture

async function startCapture() {
  if (stream) stream.getTracks().forEach((t) => t.stop());
  stream = null;
  if (!geom) return;
  const d = geom.display;
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({
      audio: false,
      video: {
        width: { ideal: Math.round(d.bounds.width * d.scaleFactor) },
        height: { ideal: Math.round(d.bounds.height * d.scaleFactor) },
        frameRate: { ideal: 5, max: 10 },
      },
    });
    video.srcObject = stream;
    await video.play();
    console.log('capture', video.videoWidth, video.videoHeight);
  } catch (e) {
    console.log('capture failed', e && e.message);
    setStatus(fmt(T.statusError, e.message || e));
  }
}

const work = document.createElement('canvas');
const wctx = work.getContext('2d', { willReadFrequently: true });
const ocrCanvas = document.createElement('canvas');
const octx = ocrCanvas.getContext('2d');
const sigCanvas = document.createElement('canvas');
sigCanvas.width = SIG_X;
sigCanvas.height = SIG_Y;
const sctx = sigCanvas.getContext('2d', { willReadFrequently: true });

/** The part of the screen inside the frame, in CSS pixels relative to the frame's inner area. */
function innerRect() {
  const f = $('frame');
  return { x: f.offsetLeft + INSET, y: f.offsetTop + INSET, w: f.clientWidth - 2 * INSET, h: f.clientHeight - 2 * INSET };
}

/** Copies the screen under the lens into the work canvas. Returns scale info, or null. */
function grab() {
  if (!geom || !video.videoWidth) return null;
  const d = geom.display;
  const r = innerRect();
  if (r.w < 20 || r.h < 20) return null;
  const vx = video.videoWidth / d.bounds.width; // video pixels per screen point
  const sx = (geom.bounds.x - d.bounds.x + r.x) * vx;
  const sy = (geom.bounds.y - d.bounds.y + r.y) * vx;
  const sw = r.w * vx;
  const sh = r.h * vx;
  // Small text reads much better when enlarged.
  const up = vx < 1.5 ? 2 : 1;
  const cw = Math.min(3000, Math.round(sw * up));
  const ch = Math.round((sh * cw) / Math.max(1, sw));
  if (work.width !== cw || work.height !== ch) {
    work.width = cw;
    work.height = ch;
  }
  wctx.drawImage(video, sx, sy, sw, sh, 0, 0, cw, ch);
  sctx.drawImage(work, 0, 0, SIG_X, SIG_Y);
  const px = sctx.getImageData(0, 0, SIG_X, SIG_Y).data;
  const sig = new Int16Array(SIG_X * SIG_Y);
  for (let i = 0; i < sig.length; i++) sig[i] = (px[i * 4] * 3 + px[i * 4 + 1] * 6 + px[i * 4 + 2]) / 10;
  return { sig, perCss: cw / r.w, inner: r };
}

/**
 * Text recognition reads dark text on a light background best: grey, inverted if the picture is mostly
 * dark (dark mode chats, light text on coloured bubbles), with a bit more contrast.
 */
function prepareForOcr(sig) {
  let sum = 0;
  for (let i = 0; i < sig.length; i++) sum += sig[i];
  const dark = sum / sig.length < 128;
  if (ocrCanvas.width !== work.width || ocrCanvas.height !== work.height) {
    ocrCanvas.width = work.width;
    ocrCanvas.height = work.height;
  }
  octx.filter = dark ? 'grayscale(1) invert(1) contrast(1.35)' : 'grayscale(1) contrast(1.35)';
  octx.drawImage(work, 0, 0);
  octx.filter = 'none';
  return ocrCanvas;
}

// ---------------------------------------------------------------- When to read

let prevSig = null;
let readSig = null; // fingerprint of the picture that was last read
let pendingSince = 0;
const heat = new Float32Array(SIG_X * SIG_Y);
const animUntil = new Float64Array(SIG_X * SIG_Y);

function resetReading() {
  readSig = null;
  prevSig = null;
  pendingSince = 0;
  clearBoxes();
}

/** Learns animated spots; returns true if the whole picture moved (scrolling). */
function learn(sig) {
  const prev = prevSig;
  if (!prev) return false;
  const now = performance.now();
  let valid = 0;
  let moved = 0;
  for (let i = 0; i < sig.length; i++) {
    if (animUntil[i] > now) continue;
    valid++;
    if (Math.abs(prev[i] - sig[i]) > CELL) moved++;
  }
  if ((valid && moved / valid >= 0.25) || verticalShift(prev, sig) !== 0) {
    heat.fill(0);
    animUntil.fill(0);
    return true;
  }
  for (let i = 0; i < sig.length; i++) {
    heat[i] *= 0.85;
    if (Math.abs(prev[i] - sig[i]) > CELL) heat[i] += 1;
    if (heat[i] >= 1.5) {
      const cx = i % SIG_X;
      const cy = (i / SIG_X) | 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const x = cx + dx;
          const y = cy + dy;
          if (x >= 0 && x < SIG_X && y >= 0 && y < SIG_Y) animUntil[y * SIG_X + x] = now + ANIM_HOLD_MS;
        }
      }
    }
  }
  return false;
}

function verticalShift(a, b) {
  const err = (dy) => {
    let sum = 0;
    let n = 0;
    for (let y = 0; y < SIG_Y; y++) {
      const py = y - dy;
      if (py < 0 || py >= SIG_Y) continue;
      for (let x = 0; x < SIG_X; x++) {
        sum += Math.abs(a[py * SIG_X + x] - b[y * SIG_X + x]);
        n++;
      }
    }
    return n < SIG_X * 3 ? Infinity : sum / n;
  };
  const e0 = err(0);
  if (e0 < 5) return 0;
  let best = 0;
  let bestErr = e0;
  for (let dy = -6; dy <= 6; dy++) {
    if (!dy) continue;
    const e = err(dy);
    if (e < bestErr) {
      bestErr = e;
      best = dy;
    }
  }
  return best && bestErr < e0 * 0.55 ? best : 0;
}

/** Cells (grid units) that differ between a and b, ignoring animated spots if asked. Returns a box or null. */
function changedArea(a, b, ignoreAnimated) {
  const now = performance.now();
  let count = 0;
  let x0 = SIG_X;
  let y0 = SIG_Y;
  let x1 = -1;
  let y1 = -1;
  for (let i = 0; i < a.length; i++) {
    if (ignoreAnimated && animUntil[i] > now) continue;
    if (Math.abs(a[i] - b[i]) <= CELL) continue;
    count++;
    const x = i % SIG_X;
    const y = (i / SIG_X) | 0;
    x0 = Math.min(x0, x);
    y0 = Math.min(y0, y);
    x1 = Math.max(x1, x);
    y1 = Math.max(y1, y);
  }
  return count >= 3 ? { x0, y0, x1, y1 } : null;
}

/** Without capture exclusion (Linux), our own translations would be read back – hide them for the moment of capture. */
async function grabClean() {
  if (info.platform !== 'linux' || !shown.size) return grab();
  boxesEl.style.visibility = 'hidden';
  await new Promise((r) => setTimeout(r, 260));
  const g = grab();
  boxesEl.style.visibility = '';
  return g;
}

async function tick() {
  if (busy || dragging || !settings) return;
  busy = true;
  let g;
  try {
    g = await grabClean();
  } finally {
    busy = false;
  }
  if (!g) return;
  const { sig } = g;
  const chat = settings.mode !== 'video';
  if (chat && learn(sig)) {
    // Scrolling: old translations would sit over the wrong text – remove them now, read once it is still.
    clearBoxes();
    readSig = null;
  }
  const area = readSig ? changedArea(readSig, sig, chat) : { x0: 0, y0: 0, x1: SIG_X - 1, y1: SIG_Y - 1 };
  if (!area) {
    prevSig = sig;
    pendingSince = 0;
    return;
  }
  if (readSig) hideBoxesIn(area, g.inner);
  const settled = prevSig && !changedArea(prevSig, sig, chat);
  const now = performance.now();
  prevSig = sig;
  if (!chat || settled || (pendingSince && now - pendingSince > SETTLE_MAX_MS)) {
    pendingSince = 0;
    await read(g);
  } else if (!pendingSince) {
    pendingSince = now;
  }
}

// ---------------------------------------------------------------- Reading and drawing

async function read(g) {
  busy = true;
  try {
    const blob = await new Promise((res) => prepareForOcr(g.sig).toBlob(res, 'image/png'));
    const png = new Uint8Array(await blob.arrayBuffer());
    const pixels = wctx.getImageData(0, 0, work.width, work.height);
    const res = await sublens.process(png);
    readSig = g.sig;
    if (!res.blocks.length) {
      clearBoxes();
      return;
    }
    needModel = !!res.needModel;
    showModelStatus();
    if (!res.translations) return;
    render(res, pixels, g);
  } catch (e) {
    console.log('read failed', e && e.stack);
    setStatus(fmt(T.statusError, (e && e.message) || e));
    readSig = g.sig;
  } finally {
    busy = false;
  }
}

const norm = (s) => String(s).toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
const measureCtx = document.createElement('canvas').getContext('2d');

function render(res, pixels, g) {
  const keep = new Set();
  const innerW = g.inner.w;
  res.blocks.forEach((b, i) => {
    const tr = res.translations[i] || b.text;
    if (norm(tr) === norm(b.text)) return; // already in the target language
    const k = g.perCss;
    const left = b.rect.x / k;
    const top = b.rect.y / k;
    const w = b.rect.w / k;
    const h = b.rect.h / k;
    const lineH = Math.max(8, b.lineHeight / k);
    const font = Math.max(10, (b.fontSize || b.lineHeight) / k);
    const key = `${Math.round(left / 6)}:${Math.round(top / 6)}:${tr}`;
    keep.add(key);
    if (shown.has(key)) {
      shown.get(key).style.visibility = '';
      return;
    }
    const [bg, fg] = sampleColors(pixels, b.rect);
    let size = font;
    const lines = Math.max(1, b.lineCount || Math.round(h / lineH));
    measureCtx.font = `${size}px system-ui, sans-serif`;
    const natural = (measureCtx.measureText(tr).width / lines) * 1.08 + 10;
    let bw = w + 8;
    let bl = left - 4;
    if (natural > bw) {
      const nw = Math.min(natural, innerW);
      const centred = Math.abs(left + w / 2 - innerW / 2) < innerW * 0.08;
      bl = centred ? left + w / 2 - nw / 2 : bl;
      bl = Math.max(0, Math.min(bl, innerW - nw));
      bw = nw;
    }
    // As tall as the original lines at this font size, centred on the original.
    const bh = Math.max(h + 6, lines * size * 1.22 + 2);
    const el = document.createElement('div');
    el.className = 'box';
    Object.assign(el.style, {
      left: `${bl}px`, top: `${top + h / 2 - bh / 2}px`, width: `${bw}px`, height: `${bh}px`,
      background: bg, color: fg, fontSize: `${size}px`,
    });
    const span = document.createElement('span');
    span.textContent = tr;
    el.appendChild(span);
    boxesEl.appendChild(el);
    // Shrink the text until it fits the original's area.
    while (span.scrollHeight > bh - 1 && size > 9) {
      size *= 0.92;
      el.style.fontSize = `${size}px`;
    }
    if (span.scrollHeight > bh - 1) el.style.height = `${span.scrollHeight + 4}px`;
    shown.set(key, el);
  });
  for (const [key, el] of shown) {
    if (!keep.has(key)) {
      el.remove();
      shown.delete(key);
    }
  }
}

function clearBoxes() {
  for (const el of shown.values()) el.remove();
  shown.clear();
}

/** Hides translations over a part that changed (they would cover the wrong thing until read again). */
function hideBoxesIn(area, inner) {
  const cw = inner.w / SIG_X;
  const ch = inner.h / SIG_Y;
  const ax0 = area.x0 * cw;
  const ay0 = area.y0 * ch;
  const ax1 = (area.x1 + 1) * cw;
  const ay1 = (area.y1 + 1) * ch;
  for (const el of shown.values()) {
    const l = parseFloat(el.style.left);
    const t = parseFloat(el.style.top);
    const r = l + parseFloat(el.style.width);
    const b = t + parseFloat(el.style.height);
    if (r > ax0 && l < ax1 && b > ay0 && t < ay1) el.style.visibility = 'hidden';
  }
}

/** Background = median colour around the text, text colour = the pixels standing out most from it. */
function sampleColors(img, r) {
  const { width, height, data } = img;
  const px = (x, y) => {
    const i = (Math.min(height - 1, Math.max(0, y | 0)) * width + Math.min(width - 1, Math.max(0, x | 0))) * 4;
    return [data[i], data[i + 1], data[i + 2]];
  };
  const pad = 3;
  const ring = [];
  const l = r.x - pad;
  const t = r.y - pad;
  const rr = r.x + r.w + pad;
  const bb = r.y + r.h + pad;
  for (let x = l; x <= rr; x += Math.max(1, (rr - l) / 24)) {
    ring.push(px(x, t), px(x, bb));
  }
  for (let y = t; y <= bb; y += Math.max(1, (bb - t) / 8)) {
    ring.push(px(l, y), px(rr, y));
  }
  const med = (k) => ring.map((c) => c[k]).sort((a, b) => a - b)[ring.length >> 1];
  const bg = [med(0), med(1), med(2)];
  const dist = (c) => Math.abs(c[0] - bg[0]) + Math.abs(c[1] - bg[1]) + Math.abs(c[2] - bg[2]);
  const inner = [];
  for (let y = r.y; y <= r.y + r.h; y += Math.max(1, r.h / 10)) {
    for (let x = r.x; x <= r.x + r.w; x += Math.max(1, r.w / 40)) inner.push(px(x, y));
  }
  inner.sort((a, b) => dist(b) - dist(a));
  const take = Math.max(1, inner.length >> 3);
  let fg = [0, 0, 0];
  for (let i = 0; i < take; i++) for (let k = 0; k < 3; k++) fg[k] += inner[i][k] / take;
  if (dist(fg) < 90) {
    const lum = (0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2]) / 255;
    fg = lum > 0.55 ? [0, 0, 0] : [255, 255, 255];
  } else {
    // The sampled text colour is softened by anti-aliasing – push it a little away from the background.
    fg = fg.map((v, k) => Math.max(0, Math.min(255, bg[k] + (v - bg[k]) * 1.2)));
  }
  const css = (c) => `rgb(${c.map((v) => Math.round(v)).join(',')})`;
  return [css(bg), css(fg)];
}

// ---------------------------------------------------------------- Reply line

function setupComposer() {
  const input = $('input');
  const copyBtn = $('copy');
  input.addEventListener('mousedown', () => sublens.focusLens());
  const send = async () => {
    const text = input.value.trim();
    if (!text) return;
    copyBtn.disabled = true;
    try {
      const res = await sublens.reply(text);
      sublens.copy(res.text);
      input.value = '';
      input.classList.add('done');
      input.placeholder = fmt(T.copied, langName(res.lang));
      setTimeout(() => {
        input.classList.remove('done');
        input.placeholder = fmt(T.replyPlaceholder, langName(settings.to));
      }, 3500);
    } catch (e) {
      flashStatus(fmt(T.statusError, (e && e.message) || e));
    } finally {
      copyBtn.disabled = false;
    }
  };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') send();
  });
  copyBtn.addEventListener('click', send);
  $('peek').addEventListener('click', () => {
    const on = boxesEl.classList.toggle('peek');
    $('peek').classList.toggle('on', on);
  });
}
