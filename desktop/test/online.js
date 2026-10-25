'use strict';
/**
 * Tests online translation (src/online.js) against a local mock of an OpenAI-compatible service:
 * prompt and answer parsing, model choice, key recognition, one request per screen, retries and errors.
 * No key and no internet needed: `npm run test:online`.
 */
const http = require('http');
const assert = require('assert');
const online = require('../src/online');

const log = [];

function mock() {
  return http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      const send = (code, obj, text) => {
        const data = text !== undefined ? text : JSON.stringify(obj);
        res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(data);
      };
      const auth = req.headers.authorization || '';
      if (req.method === 'GET' && req.url.endsWith('/models')) {
        if (auth === 'Bearer bad') return send(401, { error: { message: 'Invalid API key' } });
        return send(200, { data: ['glm-4.5-flash', 'glm-4.7-flash', 'glm-4-plus', 'embedding-3'].map((id) => ({ id })) });
      }
      const body = JSON.parse(raw || '{}');
      log.push({ auth, body });
      if (auth === 'Bearer bad') return send(401, { error: { message: 'Invalid API key' } });
      const items = JSON.parse(body.messages[1].content).items;
      const out = items.map((t) => `T(${t})`);
      let content = JSON.stringify({ translations: out });
      switch (body.model) {
        case 'limit': return send(429, { error: { message: 'Rate limit reached' } });
        case 'gone': return send(404, { error: { message: 'The model `gone` does not exist' } });
        case 'strict':
          if ('temperature' in body || 'thinking' in body) return send(400, { error: { message: 'Unrecognized request argument supplied: thinking' } });
          break;
        case 'merge':
          if (items.length >= 3) content = JSON.stringify({ translations: out.slice(0, -1) });
          break;
        case 'fenced':
          content = `<think>hmm {}</think>\n\`\`\`json\n${content}\n\`\`\``;
          break;
        case 'plain':
          if (items.length === 1) content = out[0];
          break;
        default:
      }
      return send(200, { choices: [{ index: 0, message: { role: 'assistant', content } }] });
    });
  });
}

let failures = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log(`  ✓ ${name}`);
  } catch (e) {
    failures++;
    console.log(`  ✗ ${name}\n    ${e.message}`);
  }
}

(async () => {
  const server = mock();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/v1`;
  const client = online.createClient();
  const zai = online.byId('zai');
  const custom = online.byId('custom');
  const kind = async (p) => { try { await p; return null; } catch (e) { return e.kind; } };

  console.log('Parsing');
  await check('JSON object', () => assert.deepStrictEqual(online.parse('{"translations":["a","b"]}', 2), ['a', 'b']));
  await check('think + code fence', () => assert.deepStrictEqual(online.parse('<think>x {y}</think>\n```json\n{"translations":["a"]}\n```', 1), ['a']));
  await check('other key, chatter around', () => assert.deepStrictEqual(online.parse('Sure: {"result":["x","y"]} done', 2), ['x', 'y']));
  await check('bare array', () => assert.deepStrictEqual(online.parse('["x","y","z"]', 3), ['x', 'y', 'z']));
  await check('wrong count → null', () => assert.strictEqual(online.parse('{"translations":["a"]}', 2), null));
  await check('single item as plain text', () => assert.deepStrictEqual(online.parse('Hallo Welt', 1), ['Hallo Welt']));

  console.log('Prompt');
  await check('Traditional Chinese spelled out, no source when auto', () => {
    const s = online.systemPrompt(null, 'zh-Hant', 3, false);
    assert(s.includes('into Chinese written in Traditional characters (as in Taiwan and Hong Kong).\nRules'));
    assert(s.includes('exactly 3 strings'));
  });
  await check('reply prompt', () => assert(online.systemPrompt('en', 'de', 1, true).includes('chat message from English into German')));
  await check('context capped at 12', () => assert.strictEqual(JSON.parse(online.userPrompt(['a'], Array(20).fill('c'))).earlier_context.length, 12));

  console.log('Providers');
  await check('GLM: newest flash', () => assert.strictEqual(online.pickModel(zai, ['glm-4.5-flash', 'glm-4.7-flash', 'glm-4-plus']), 'glm-4.7-flash'));
  await check('Gemini: no preview models', () => assert.strictEqual(online.pickModel(online.byId('gemini'), ['models/gemini-2.5-flash', 'models/gemini-3.1-flash-lite', 'models/gemini-3.5-flash-lite-preview']), 'gemini-3.1-flash-lite'));
  await check('Groq: Qwen first', () => assert.strictEqual(online.pickModel(online.byId('groq'), ['llama-3.3-70b-versatile', 'qwen/qwen3.8-27b', 'whisper-large-v3']), 'qwen/qwen3.8-27b'));
  await check('picker order, embeddings hidden', () => assert.deepStrictEqual(online.usableModels(zai, ['glm-4-plus', 'embedding-3', 'glm-4.5-flash', 'glm-4.7-flash']), ['glm-4.7-flash', 'glm-4.5-flash', 'glm-4-plus']));
  await check('key recognition', () => {
    const ids = (k) => online.guess(k).map((p) => p.id);
    assert.deepStrictEqual(ids('0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMn'), ['zai']);
    assert.deepStrictEqual(ids('sk-0123456789abcdef0123456789abcdef'), ['deepseek', 'qwen']);
    assert.deepStrictEqual(ids('AIzaSyA1234567890abcdefghijklmnopqrstu'), ['gemini']);
    assert.deepStrictEqual(ids('gsk_abcdefghijklmnopqrstuvwxyz0123'), ['groq']);
    assert.deepStrictEqual(ids('sk-or-v1-0123456789abcdef0123456789abcdef'), ['openrouter']);
    assert.deepStrictEqual(ids('hello'), []);
  });
  await check('pasted key cleaned', () => assert.strictEqual(online.cleanKey('  Bearer "gsk_abc\n def"  '), 'gsk_abcdef'));

  console.log('Client (mock server)');
  await check('one request for the whole screen, in order', async () => {
    log.length = 0;
    const texts = ['你好', '今天吃什么？', '哈哈😂', '@lisa 好的'];
    const out = await client.translate({ provider: zai, url, key: 'k', model: 'glm-4.7-flash' }, texts, null, 'de', ['earlier']);
    assert.deepStrictEqual(out, texts.map((t) => `T(${t})`));
    assert.strictEqual(log.length, 1);
    assert.strictEqual(log[0].auth, 'Bearer k');
    assert.deepStrictEqual(log[0].body.thinking, { type: 'disabled' });
    assert.deepStrictEqual(JSON.parse(log[0].body.messages[1].content).earlier_context, ['earlier']);
  });
  await check('fenced answer and plain text', async () => {
    assert.deepStrictEqual(await client.translate({ provider: custom, url, model: 'fenced' }, ['a', 'b'], 'en', 'de'), ['T(a)', 'T(b)']);
    assert.deepStrictEqual(await client.translate({ provider: custom, url, model: 'plain' }, ['a'], 'en', 'de'), ['T(a)']);
  });
  await check('merged lines → asked again in two halves', async () => {
    log.length = 0;
    assert.deepStrictEqual(await client.translate({ provider: custom, url, model: 'merge' }, ['1', '2', '3', '4'], 'en', 'de'), ['T(1)', 'T(2)', 'T(3)', 'T(4)']);
    assert.strictEqual(log.length, 3);
  });
  await check('400 on extra settings → retried bare, remembered', async () => {
    log.length = 0;
    await client.translate({ provider: zai, url, key: 'k', model: 'strict' }, ['a'], 'en', 'de');
    await client.translate({ provider: zai, url, key: 'k', model: 'strict' }, ['b'], 'en', 'de');
    assert.strictEqual(log.length, 3);
    assert(!('thinking' in log[2].body));
  });
  await check('errors are classified', async () => {
    assert.strictEqual(await kind(client.translate({ provider: custom, url, model: 'limit' }, ['a'], 'en', 'de')), 'LIMIT');
    assert.strictEqual(await kind(client.translate({ provider: custom, url, model: 'gone' }, ['a'], 'en', 'de')), 'MODEL');
    assert.strictEqual(await kind(client.translate({ provider: custom, url, key: 'bad', model: 'x' }, ['a'], 'en', 'de')), 'AUTH');
    assert.strictEqual(await kind(client.listModels(url, 'bad')), 'AUTH');
    assert.strictEqual(await kind(client.translate({ provider: custom, url: 'http://127.0.0.1:9/v1', model: 'x' }, ['a'], 'en', 'de')), 'NETWORK');
    assert.strictEqual(online.shortError('429 {"error":{"message":"Rate limit reached"}}'), 'Rate limit reached');
  });
  await check('long screens in parts of 40, later parts get context', async () => {
    log.length = 0;
    const many = Array.from({ length: 95 }, (_, i) => `line ${i + 1}`);
    const out = await client.translate({ provider: custom, url, model: 'echo' }, many, 'en', 'de');
    assert.deepStrictEqual(out, many.map((t) => `T(${t})`));
    assert.strictEqual(log.length, 3);
    assert.strictEqual(JSON.parse(log[2].body.messages[1].content).earlier_context.length, 12);
  });
  await check('model list', async () => assert((await client.listModels(url, 'k')).includes('glm-4.7-flash')));

  server.close();
  console.log(failures ? `${failures} FAILED` : 'ALL PASSED');
  process.exit(failures ? 1 : 0);
})();
