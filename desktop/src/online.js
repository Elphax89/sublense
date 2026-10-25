'use strict';
/**
 * Online translation with an AI service the user has a key for. All services speak the OpenAI
 * "chat completions" protocol, so one client fits all. Same providers, prompt and rules as the Android app
 * (app/src/main/java/app/sublens/Providers.kt, Prompt.kt, OnlineClient.kt).
 * Plain Node – no Electron – so it can be tested against a mock server (see test/online.js).
 */

/** Model names change every few months: we keep preferences and pick from the provider's current list. */
const PROVIDERS = [
  {
    id: 'zai',
    name: 'GLM · Z.AI',
    baseUrl: 'https://api.z.ai/api/paas/v4',
    keyUrl: 'https://z.ai/manage-apikey/apikey-list',
    badges: ['badgeFree', 'badgeChinese'],
    defaultModel: 'glm-4.7-flash',
    prefer: [/^glm-[\d.]+-flash$/i],
    extra: { thinking: { type: 'disabled' } },
    keyPattern: /^[0-9a-f]{32}\.[A-Za-z0-9]{10,24}$/,
  },
  {
    id: 'deepseek',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    keyUrl: 'https://platform.deepseek.com/api_keys',
    badges: ['badgeCheap', 'badgeChinese'],
    defaultModel: 'deepseek-chat',
    // "deepseek-chat" always points to the current model without slow "thinking".
    prefer: [/^deepseek-chat$/, /^deepseek-v[\d.]+-flash$/],
    keyPattern: /^sk-[0-9a-f]{32}$/,
  },
  {
    id: 'qwen',
    name: 'Qwen · Alibaba Cloud',
    baseUrl: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
    keyUrl: 'https://modelstudio.console.alibabacloud.com/model/settings/api-key',
    badges: ['badgeFreeStart', 'badgeChinese'],
    defaultModel: 'qwen-plus',
    prefer: [/^qwen-flash$/, /^qwen-plus$/, /^qwen-turbo$/, /^qwen[\d.]*-plus/],
    exclude: /(mt-|vl|omni|audio|tts|coder|math|embed|image|ocr|deep-research)/i,
    extra: { enable_thinking: false },
    keyPattern: /^sk-[0-9a-f]{32}$/,
    editableUrl: true,
  },
  {
    id: 'gemini',
    name: 'Gemini · Google',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    keyUrl: 'https://aistudio.google.com/apikey',
    badges: ['badgeFree'],
    defaultModel: 'gemini-flash-lite-latest',
    prefer: [/^gemini-flash-lite-latest$/, /^gemini-[\d.]+-flash-lite$/, /^gemini-flash-latest$/, /^gemini-[\d.]+-flash$/],
    exclude: /(preview|exp|image|tts|audio|live|embedding|thinking|robotics|computer)/i,
    extra: { reasoning_effort: 'low' },
    keyPattern: /^AIza[0-9A-Za-z_-]{30,40}$/,
  },
  {
    id: 'groq',
    name: 'Groq',
    baseUrl: 'https://api.groq.com/openai/v1',
    keyUrl: 'https://console.groq.com/keys',
    badges: ['badgeFree', 'badgeFast'],
    defaultModel: 'llama-3.3-70b-versatile',
    prefer: [/^qwen\/qwen[\d.]+-\d+b$/, /kimi-k2/, /^llama-3\.3-70b-versatile$/, /^openai\/gpt-oss-120b$/],
    keyPattern: /^gsk_[A-Za-z0-9]{20,}$/,
  },
  {
    id: 'openrouter',
    name: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    keyUrl: 'https://openrouter.ai/settings/keys',
    badges: ['badgeFree', 'badgeMany'],
    defaultModel: 'openrouter/auto',
    prefer: [/^deepseek\/.*:free$/, /^qwen\/.*:free$/, /^z-ai\/glm.*:free$/, /^google\/gemini.*:free$/, /^meta-llama\/.*:free$/],
    keyPattern: /^sk-or-[A-Za-z0-9-]{20,}$/,
  },
  {
    id: 'custom',
    name: 'OpenAI-compatible',
    baseUrl: '',
    keyUrl: '',
    badges: [],
    defaultModel: '',
    prefer: [],
    editableUrl: true,
  },
];

const DEFAULT_EXCLUDE = /(embed|image|vision-only|tts|audio|speech|whisper|transcribe|guard|moderation|rerank|ocr|realtime|live|search|coder|math)/i;
const MAX_ITEMS = 40;
const MAX_CHARS = 5000;
const MAX_CONTEXT = 12;

const byId = (id) => PROVIDERS.find((p) => p.id === id) || null;

/** Which providers a pasted key could belong to (most likely first). */
function guess(key) {
  const k = String(key || '').trim();
  return PROVIDERS.filter((p) => p.keyPattern && p.keyPattern.test(k));
}

/** Keys never contain spaces – remove line breaks, quotes and a copied "Bearer ". */
function cleanKey(raw) {
  return String(raw || '').trim().replace(/^bearer\s+/i, '').replace(/^["']+|["']+$/g, '').replace(/\s+/g, '');
}

function versionKey(id) {
  const m = /(\d+)(?:\.(\d+))?/.exec(id);
  return m ? Number(m[1]) + Number(m[2] || 0) / 1000 : 0;
}

function usable(p, available) {
  const ids = [...new Set(available.map((m) => m.replace(/^models\//, '')))];
  return p.id === 'custom' ? ids : ids.filter((m) => !(p.exclude || DEFAULT_EXCLUDE).test(m));
}

/** First matching preference wins; among several matches the highest version number. */
function pickModel(p, available) {
  const list = usable(p, available);
  for (const pref of p.prefer) {
    const hits = list.filter((m) => pref.test(m));
    if (hits.length) return hits.sort((a, b) => versionKey(b) - versionKey(a) || a.length - b.length)[0];
  }
  return null;
}

/** Models to offer in the picker: recommended ones first (newest first), then the rest A–Z. */
function usableModels(p, available) {
  const list = usable(p, available);
  const preferred = [];
  for (const pref of p.prefer) {
    for (const m of list.filter((x) => pref.test(x)).sort((a, b) => versionKey(b) - versionKey(a))) {
      if (!preferred.includes(m)) preferred.push(m);
    }
  }
  return preferred.concat(list.filter((m) => !preferred.includes(m)).sort());
}

// ------------------------------------------------------------------ Prompt

/** Language names the model understands without doubt. */
function languageName(code) {
  if (code === 'zh-Hant') return 'Chinese written in Traditional characters (as in Taiwan and Hong Kong)';
  if (code === 'zh-Hans') return 'Chinese written in Simplified characters (as in mainland China)';
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) || code;
  } catch {
    return code;
  }
}

function systemPrompt(from, to, count, reply) {
  const target = languageName(to);
  const source = !from || from === 'auto' ? '' : ` from ${languageName(from)}`;
  if (reply) {
    return [
      `You translate the user's own chat message${source} into ${target}, ready to be sent.`,
      'Write it the way a native speaker would type it in a chat: natural, same tone, same emojis, no added politeness, no explanations.',
      'The "earlier_context" lines are the conversation so far – use them only to understand what is meant.',
      `Answer with JSON only, no markdown: {"translations": ["…"]} with exactly ${count} string(s) in the input order.`,
    ].join('\n');
  }
  return [
    `You translate text that was read from a screen – chat messages, subtitles, menus, signs, websites – into ${target}${source}.`,
    'Rules:',
    '- The items appear on the same screen in reading order. Use the others (and "earlier_context") as context, but translate each item on its own.',
    `- Write natural, everyday ${target} the way a native speaker would say it. Keep the tone, slang, emojis and punctuation style.`,
    '- Keep names, @usernames, URLs, numbers, prices, codes and emojis as they are.',
    '- The text was recognised by OCR and may contain small mistakes. Silently correct obvious ones.',
    `- If an item is already in ${target}, return it unchanged. Never add notes, quotes or explanations.`,
    `Answer with JSON only, no markdown: {"translations": ["…"]} with exactly ${count} strings in the input order.`,
  ].join('\n');
}

function userPrompt(texts, context) {
  const o = { items: texts };
  if (context && context.length) o.earlier_context = context.slice(-MAX_CONTEXT);
  return JSON.stringify(o);
}

function strings(arr) {
  return arr.map((v) => {
    if (typeof v === 'string') return v.trim();
    if (v && typeof v === 'object') return String(v.translation ?? v.text ?? JSON.stringify(v)).trim();
    return v == null ? '' : String(v).trim();
  });
}

/** Reads {"translations": [...]} (or a bare list) out of the answer; null if it doesn't fit. */
function parse(content, count) {
  let t = String(content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  t = t.replace(/^```(?:json|JSON)?/, '').replace(/```$/, '').trim();
  const o0 = t.indexOf('{');
  const o1 = t.lastIndexOf('}');
  if (o0 >= 0 && o1 > o0) {
    try {
      const o = JSON.parse(t.slice(o0, o1 + 1));
      const arr = Array.isArray(o.translations) ? o.translations : Object.values(o).find(Array.isArray);
      if (arr && arr.length === count) return strings(arr);
    } catch {
      // try the next shape
    }
  }
  const a0 = t.indexOf('[');
  const a1 = t.lastIndexOf(']');
  if (a0 >= 0 && a1 > a0) {
    try {
      const arr = JSON.parse(t.slice(a0, a1 + 1));
      if (Array.isArray(arr) && arr.length === count) return strings(arr);
    } catch {
      // not a list either
    }
  }
  // A single item may come back as plain text.
  if (count === 1 && t && !t.startsWith('{') && !t.startsWith('[')) return [t];
  return null;
}

// ------------------------------------------------------------------ Client

class OnlineError extends Error {
  /** kind: NO_KEY, AUTH, LIMIT, MODEL, NETWORK, FORMAT, SERVER */
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

/** "provider|model" that reject our optional settings (temperature, thinking off …). */
const bareModels = new Set();

function looksLikeModelError(text) {
  const t = String(text).toLowerCase();
  return t.includes('model') && ['not found', 'does not exist', 'not exist', 'invalid model', 'unknown model', 'not supported', 'no such model'].some((s) => t.includes(s));
}

function failure(code, text) {
  const short = String(text).slice(0, 300);
  if (code === 401 || code === 403) return new OnlineError('AUTH', short);
  if (code === 402 || code === 429) return new OnlineError('LIMIT', short);
  if (code === 404 || looksLikeModelError(text)) return new OnlineError('MODEL', short);
  return new OnlineError('SERVER', `${code} ${short}`);
}

/** The readable part of an error answer (most services send {"error": {"message": …}}). */
function shortError(raw) {
  const text = String(raw || '?');
  const i = text.indexOf('{');
  if (i >= 0) {
    try {
      const o = JSON.parse(text.slice(i));
      const m = typeof o.error === 'string' ? o.error : (o.error && o.error.message) || o.message;
      if (m) return String(m).slice(0, 160);
    } catch {
      // plain text
    }
  }
  return text.slice(0, 160);
}

function createClient(fetchImpl = globalThis.fetch) {
  async function http(method, url, key, body, provider) {
    const headers = { Accept: 'application/json' };
    if (key) headers.Authorization = `Bearer ${key}`;
    if (provider === 'openrouter') {
      headers['HTTP-Referer'] = 'https://github.com/Elphax89/sublense';
      headers['X-Title'] = 'SubLens';
    }
    if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
    let res;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 45000);
    try {
      res = await fetchImpl(url, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: ctrl.signal });
      return { code: res.status, text: await res.text() };
    } catch (e) {
      throw new OnlineError('NETWORK', String((e && e.message) || e));
    } finally {
      clearTimeout(timer);
    }
  }

  async function chat(p, url, key, model, system, user, maxTokens) {
    const id = `${p.id}|${model}`;
    const bare = bareModels.has(id);
    const body = { model, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] };
    if (!bare) Object.assign(body, { temperature: 0.2, max_tokens: maxTokens }, p.extra || {});
    const { code, text } = await http('POST', `${url}/chat/completions`, key, body, p.id);
    if (code === 400 && !bare && !looksLikeModelError(text)) {
      // The model doesn't accept one of our settings – send only model and messages from now on.
      bareModels.add(id);
      return chat(p, url, key, model, system, user, maxTokens);
    }
    if (code < 200 || code > 299) throw failure(code, text);
    try {
      const c = JSON.parse(text).choices[0].message.content;
      return typeof c === 'string' ? c : '';
    } catch {
      throw new OnlineError('FORMAT', `bad response: ${text.slice(0, 200)}`);
    }
  }

  async function request(p, url, key, model, texts, from, to, context, reply) {
    const chars = texts.reduce((n, t) => n + t.length, 0);
    // Generous: some models "think" first, and those tokens count too. Only what is generated is billed.
    const maxTokens = Math.min(8192, Math.max(1024, chars * 4 + 1024));
    const content = await chat(p, url, key, model, systemPrompt(from, to, texts.length, reply), userPrompt(texts, context), maxTokens);
    const out = parse(content, texts.length);
    if (!out) throw new OnlineError('FORMAT', `unexpected answer: ${String(content).slice(0, 200)}`);
    return out;
  }

  /** If the answer doesn't have one translation per item (lines merged), ask again in two halves. */
  async function requestSplitting(p, url, key, model, texts, from, to, context, reply) {
    try {
      return await request(p, url, key, model, texts, from, to, context, reply);
    } catch (e) {
      if (e.kind !== 'FORMAT' || texts.length < 2) throw e;
      const half = Math.floor(texts.length / 2);
      const first = texts.slice(0, half);
      const a = await request(p, url, key, model, first, from, to, context, reply);
      const b = await request(p, url, key, model, texts.slice(half), from, to, context.concat(first).slice(-MAX_CONTEXT), reply);
      return a.concat(b);
    }
  }

  /**
   * Translates `texts` (one screen, in reading order). `from` null/'auto' = the model detects it.
   * conn = { provider, url, key, model }. Throws OnlineError.
   */
  async function translate(conn, texts, from, to, context = [], reply = false) {
    const p = typeof conn.provider === 'string' ? byId(conn.provider) : conn.provider;
    const out = [];
    let start = 0;
    while (start < texts.length) {
      let end = start;
      let chars = 0;
      while (end < texts.length && end - start < MAX_ITEMS && (chars + texts[end].length < MAX_CHARS || end === start)) {
        chars += texts[end].length;
        end++;
      }
      const earlier = context.concat(texts.slice(0, start)).slice(-MAX_CONTEXT);
      out.push(...await requestSplitting(p, conn.url, conn.key || '', conn.model, texts.slice(start, end), from, to, earlier, reply));
      start = end;
    }
    return out;
  }

  async function listModels(url, key) {
    const { code, text } = await http('GET', `${url}/models`, key, null, null);
    if (code < 200 || code > 299) throw failure(code, text);
    try {
      const data = JSON.parse(text).data || [];
      return data.map((m) => m && m.id).filter(Boolean).map((m) => String(m).replace(/^models\//, ''));
    } catch {
      return [];
    }
  }

  return { translate, listModels };
}

module.exports = {
  PROVIDERS, byId, guess, cleanKey, pickModel, usableModels, languageName, systemPrompt, userPrompt, parse,
  OnlineError, shortError, createClient, MAX_CONTEXT,
};
