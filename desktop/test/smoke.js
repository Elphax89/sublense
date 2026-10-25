'use strict';
/**
 * Smoke test for the desktop engine (runs in CI with plain Node):
 * renders text to an image, recognises it, detects the language and translates.
 * Results are printed as GitHub annotations.
 */
const path = require('path');
const os = require('os');
const core = require('../src/core');

const note = (m) => console.log(`::notice::${m}`);
const fail = (m) => { console.log(`::error::${m}`); process.exitCode = 1; };

function image() {
  // A chat-like picture rendered once with Chromium (German text, one line with a time stamp).
  return require('fs').readFileSync(path.join(__dirname, 'fixtures', 'chat-de.png'));
}

(async () => {
  core.init({ dataDir: path.join(os.tmpdir(), 'sublens-smoke'), onProgress: () => {} });
  let t = Date.now();
  const blocks = await core.ocr(image(), 'deu');
  note(`OCR ${Date.now() - t} ms (incl. first load): ${JSON.stringify(blocks.map((b) => [b.text, b.rect, Math.round(b.lineHeight)]))}`);
  if (blocks.length !== 3) fail(`expected 3 blocks, got ${blocks.length}`);

  const lang = core.detect(blocks.map((b) => b.text).join('\n'), 'deu');
  note(`detected: ${lang}`);
  if (lang !== 'de') fail('language detection');
  if (blocks.some((b) => /11:31/.test(b.text))) fail('time stamp was not removed');

  if (process.argv.includes('--ocr-only')) return core.shutdown();

  t = Date.now();
  await core.getTranslator();
  const st = core.modelStatus();
  note(`model load ${Date.now() - t} ms, on disk ${(st.bytes / 1048576).toFixed(0)} MB, ready=${st.ready}`);

  t = Date.now();
  const en = await core.translate(blocks.map((b) => b.text), 'de', 'en');
  note(`de→en ${Date.now() - t} ms: ${JSON.stringify(en)}`);
  t = Date.now();
  const again = await core.translate(['こんにちは！今週末は空いてる？', '土曜日の夜はどう？'], 'ja', 'en');
  note(`ja→en ${Date.now() - t} ms: ${JSON.stringify(again)}`);
  t = Date.now();
  const zh = await core.translate(['Sounds great! Saturday at 7?'], 'en', 'zh-Hant');
  note(`en→zh-Hant ${Date.now() - t} ms: ${JSON.stringify(zh)}`);
  if (!/weekend/i.test(en[0])) fail('de→en translation looks wrong');
  await core.shutdown();
})().catch((e) => fail(e.stack.replace(/\n/g, ' | ')));
