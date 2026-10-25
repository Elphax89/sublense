'use strict';
/**
 * SubLens desktop engine core: text recognition (Tesseract), language detection (tinyld)
 * and offline translation (NLLB-200 via Transformers.js / ONNX Runtime).
 * Plain Node – no Electron – so it can be tested on its own (see test/smoke.js).
 */
const path = require('path');
const fs = require('fs');
const { LANGS, byCode } = require('./languages');

const MODEL_ID = 'Xenova/nllb-200-distilled-600M';
const MODEL_DTYPE = 'q8';

let dataDir = null;
let onProgress = () => {};

function init(opts) {
  dataDir = opts.dataDir;
  if (opts.onProgress) onProgress = opts.onProgress;
  fs.mkdirSync(path.join(dataDir, 'tessdata'), { recursive: true });
  fs.mkdirSync(path.join(dataDir, 'models'), { recursive: true });
}

// ------------------------------------------------------------------ OCR

let tesseract = null;
let worker = null;
let workerLangs = '';
let workerLoading = null;

async function getWorker(langs) {
  if (worker && workerLangs === langs) return worker;
  if (workerLoading) await workerLoading.catch(() => {});
  if (worker && workerLangs === langs) return worker;
  workerLoading = (async () => {
    if (!tesseract) tesseract = require('tesseract.js');
    if (worker) {
      await worker.terminate().catch(() => {});
      worker = null;
    }
    onProgress({ kind: 'ocr', status: 'loading', langs });
    const w = await tesseract.createWorker(langs.split('+'), 1, {
      cachePath: path.join(dataDir, 'tessdata'),
      // default langPath: jsDelivr CDN (@tesseract.js-data), downloaded once per language
      ...(process.env.SUBLENS_LANGPATH ? { langPath: process.env.SUBLENS_LANGPATH } : {}),
    });
    await w.setParameters({ preserve_interword_spaces: '1' });
    worker = w;
    workerLangs = langs;
    onProgress({ kind: 'ocr', status: 'ready', langs });
  })();
  try {
    await workerLoading;
  } finally {
    workerLoading = null;
  }
  return worker;
}

/**
 * Recognises text in a PNG and groups lines into blocks (one chat bubble / subtitle / paragraph each).
 * Returns [{ text, rect: {x, y, w, h}, lineHeight }] in image pixels.
 */
async function ocr(png, langs) {
  const w = await getWorker(langs);
  const res = await w.recognize(Buffer.from(png), {}, { blocks: true, text: false });
  const lines = [];
  for (const block of res.data.blocks || []) {
    for (const para of block.paragraphs || []) {
      for (const line of para.lines || []) {
        const words = (line.words || []).filter((wd) => wd.confidence >= 35 && wd.text.trim());
        const text = (words.length ? words.map((wd) => wd.text).join(isCJK(langs) ? '' : ' ') : line.text).trim();
        if (countLetters(text) < 2 || line.confidence < 40) continue;
        const b = line.bbox;
        lines.push({ text, x0: b.x0, y0: b.y0, x1: b.x1, y1: b.y1, h: b.y1 - b.y0 });
      }
    }
  }
  lines.sort((a, b) => a.y0 - b.y0 || a.x0 - b.x0);
  return groupLines(lines, isCJK(langs) ? '' : ' ');
}

/** Lines close above each other with horizontal overlap form one block. */
function groupLines(lines, sep) {
  const blocks = [];
  for (const ln of lines) {
    const last = blocks[blocks.length - 1];
    if (last) {
      const gap = ln.y0 - last.y1;
      const overlap = Math.min(ln.x1, last.x1) - Math.max(ln.x0, last.x0);
      const lh = Math.max(ln.h, last.lineHeight);
      if (gap < lh * 0.55 && overlap > -lh && Math.abs(ln.h - last.lineHeight) < lh * 0.5) {
        last.lines.push(ln.text);
        last.x0 = Math.min(last.x0, ln.x0);
        last.x1 = Math.max(last.x1, ln.x1);
        last.y1 = ln.y1;
        last.lineHeight = (last.lineHeight * (last.lines.length - 1) + ln.h) / last.lines.length;
        continue;
      }
    }
    blocks.push({ lines: [ln.text], x0: ln.x0, y0: ln.y0, x1: ln.x1, y1: ln.y1, lineHeight: ln.h });
  }
  return blocks.map((b) => ({
    text: b.lines.join(sep).replace(/\s+/g, ' ').trim(),
    rect: { x: b.x0, y: b.y0, w: b.x1 - b.x0, h: b.y1 - b.y0 },
    lineHeight: b.lineHeight,
    lineCount: b.lines.length,
  }));
}

function isCJK(langs) {
  return /chi_|jpn/.test(langs);
}

function countLetters(s) {
  let n = 0;
  for (const ch of s) if (/\p{L}/u.test(ch)) n++;
  return n;
}

// ------------------------------------------------------------------ Language detection

let tinyld = null;

/** Returns one of our language codes (see languages.js) or null. */
function detect(text, ocrLangs) {
  if (!tinyld) tinyld = require('tinyld');
  const iso = tinyld.detect(text);
  if (!iso) return null;
  if (iso === 'zh') return /chi_sim/.test(ocrLangs || '') && !/chi_tra/.test(ocrLangs || '') ? 'zh-Hans' : 'zh-Hant';
  const hit = LANGS.find((l) => l.code === iso || l.iso1 === iso);
  return hit ? hit.code : null;
}

// ------------------------------------------------------------------ Translation

let transformers = null;
let translator = null;
let translatorLoading = null;
const cache = new Map();

function modelDir() {
  return path.join(dataDir, 'models');
}

async function getTranslator() {
  if (translator) return translator;
  if (translatorLoading) return translatorLoading;
  translatorLoading = (async () => {
    if (!transformers) transformers = await import('@huggingface/transformers');
    const { env, pipeline } = transformers;
    env.cacheDir = modelDir();
    env.allowLocalModels = false;
    const files = new Map();
    const t = await pipeline('translation', MODEL_ID, {
      dtype: MODEL_DTYPE,
      progress_callback: (p) => {
        if (p.status === 'progress' && p.file && p.total) {
          files.set(p.file, { loaded: p.loaded, total: p.total });
          let loaded = 0;
          let total = 0;
          for (const f of files.values()) {
            loaded += f.loaded;
            total += f.total;
          }
          onProgress({ kind: 'model', status: 'downloading', loaded, total });
        }
      },
    });
    translator = t;
    onProgress({ kind: 'model', status: 'ready' });
    return t;
  })();
  try {
    return await translatorLoading;
  } finally {
    translatorLoading = null;
  }
}

/** Translates several texts at once. Codes are ours (e.g. 'de', 'zh-Hant'). */
async function translate(texts, src, tgt) {
  const s = byCode(src);
  const t = byCode(tgt);
  if (!s || !t) throw new Error(`Unsupported language ${src} → ${tgt}`);
  if (s.code === t.code) return texts.slice();
  const out = new Array(texts.length);
  const todo = [];
  texts.forEach((txt, i) => {
    const hit = cache.get(`${s.code}>${t.code}|${txt}`);
    if (hit !== undefined) out[i] = hit;
    else todo.push(i);
  });
  if (todo.length) {
    const tr = await getTranslator();
    const inputs = todo.map((i) => texts[i]);
    const res = await tr(inputs, { src_lang: s.nllb, tgt_lang: t.nllb, max_new_tokens: 256 });
    const arr = Array.isArray(res) ? res : [res];
    todo.forEach((i, k) => {
      const value = (arr[k] && arr[k].translation_text) || texts[i];
      out[i] = value;
      cache.set(`${s.code}>${t.code}|${texts[i]}`, value);
    });
    if (cache.size > 2000) cache.delete(cache.keys().next().value);
  }
  return out;
}

/** Is the translation model already on disk? */
function modelStatus() {
  const dir = path.join(modelDir(), ...MODEL_ID.split('/'));
  let bytes = 0;
  const walk = (d) => {
    if (!fs.existsSync(d)) return;
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else bytes += fs.statSync(p).size;
    }
  };
  walk(dir);
  const ready = fs.existsSync(path.join(dir, 'onnx', 'decoder_model_merged_quantized.onnx')) &&
    fs.existsSync(path.join(dir, 'onnx', 'encoder_model_quantized.onnx'));
  return { ready, bytes };
}

async function deleteDownloads() {
  translator = null;
  if (worker) {
    await worker.terminate().catch(() => {});
    worker = null;
    workerLangs = '';
  }
  for (const sub of ['models', 'tessdata']) {
    fs.rmSync(path.join(dataDir, sub), { recursive: true, force: true });
    fs.mkdirSync(path.join(dataDir, sub), { recursive: true });
  }
  cache.clear();
}

module.exports = { init, ocr, detect, translate, getTranslator, modelStatus, deleteDownloads, groupLines, MODEL_ID };
