'use strict';
/**
 * Languages SubLens desktop supports.
 * code  = our code (also used in settings), iso1 = what tinyld returns,
 * nllb  = NLLB-200 code for translation, tess = Tesseract traineddata for text recognition.
 */
const LANGS = [
  { code: 'en', iso1: 'en', nllb: 'eng_Latn', tess: 'eng', name: 'English' },
  { code: 'de', iso1: 'de', nllb: 'deu_Latn', tess: 'deu', name: 'German' },
  { code: 'fr', iso1: 'fr', nllb: 'fra_Latn', tess: 'fra', name: 'French' },
  { code: 'es', iso1: 'es', nllb: 'spa_Latn', tess: 'spa', name: 'Spanish' },
  { code: 'it', iso1: 'it', nllb: 'ita_Latn', tess: 'ita', name: 'Italian' },
  { code: 'pt', iso1: 'pt', nllb: 'por_Latn', tess: 'por', name: 'Portuguese' },
  { code: 'nl', iso1: 'nl', nllb: 'nld_Latn', tess: 'nld', name: 'Dutch' },
  { code: 'pl', iso1: 'pl', nllb: 'pol_Latn', tess: 'pol', name: 'Polish' },
  { code: 'cs', iso1: 'cs', nllb: 'ces_Latn', tess: 'ces', name: 'Czech' },
  { code: 'sk', iso1: 'sk', nllb: 'slk_Latn', tess: 'slk', name: 'Slovak' },
  { code: 'sl', iso1: 'sl', nllb: 'slv_Latn', tess: 'slv', name: 'Slovenian' },
  { code: 'hr', iso1: 'hr', nllb: 'hrv_Latn', tess: 'hrv', name: 'Croatian' },
  { code: 'sr', iso1: 'sr', nllb: 'srp_Cyrl', tess: 'srp', name: 'Serbian' },
  { code: 'hu', iso1: 'hu', nllb: 'hun_Latn', tess: 'hun', name: 'Hungarian' },
  { code: 'ro', iso1: 'ro', nllb: 'ron_Latn', tess: 'ron', name: 'Romanian' },
  { code: 'bg', iso1: 'bg', nllb: 'bul_Cyrl', tess: 'bul', name: 'Bulgarian' },
  { code: 'el', iso1: 'el', nllb: 'ell_Grek', tess: 'ell', name: 'Greek' },
  { code: 'ru', iso1: 'ru', nllb: 'rus_Cyrl', tess: 'rus', name: 'Russian' },
  { code: 'uk', iso1: 'uk', nllb: 'ukr_Cyrl', tess: 'ukr', name: 'Ukrainian' },
  { code: 'sv', iso1: 'sv', nllb: 'swe_Latn', tess: 'swe', name: 'Swedish' },
  { code: 'da', iso1: 'da', nllb: 'dan_Latn', tess: 'dan', name: 'Danish' },
  { code: 'no', iso1: 'no', nllb: 'nob_Latn', tess: 'nor', name: 'Norwegian' },
  { code: 'fi', iso1: 'fi', nllb: 'fin_Latn', tess: 'fin', name: 'Finnish' },
  { code: 'et', iso1: 'et', nllb: 'est_Latn', tess: 'est', name: 'Estonian' },
  { code: 'lv', iso1: 'lv', nllb: 'lvs_Latn', tess: 'lav', name: 'Latvian' },
  { code: 'lt', iso1: 'lt', nllb: 'lit_Latn', tess: 'lit', name: 'Lithuanian' },
  { code: 'tr', iso1: 'tr', nllb: 'tur_Latn', tess: 'tur', name: 'Turkish' },
  { code: 'ar', iso1: 'ar', nllb: 'arb_Arab', tess: 'ara', name: 'Arabic' },
  { code: 'he', iso1: 'he', nllb: 'heb_Hebr', tess: 'heb', name: 'Hebrew' },
  { code: 'fa', iso1: 'fa', nllb: 'pes_Arab', tess: 'fas', name: 'Persian' },
  { code: 'hi', iso1: 'hi', nllb: 'hin_Deva', tess: 'hin', name: 'Hindi' },
  { code: 'bn', iso1: 'bn', nllb: 'ben_Beng', tess: 'ben', name: 'Bengali' },
  { code: 'ur', iso1: 'ur', nllb: 'urd_Arab', tess: 'urd', name: 'Urdu' },
  { code: 'ta', iso1: 'ta', nllb: 'tam_Taml', tess: 'tam', name: 'Tamil' },
  { code: 'th', iso1: 'th', nllb: 'tha_Thai', tess: 'tha', name: 'Thai' },
  { code: 'vi', iso1: 'vi', nllb: 'vie_Latn', tess: 'vie', name: 'Vietnamese' },
  { code: 'id', iso1: 'id', nllb: 'ind_Latn', tess: 'ind', name: 'Indonesian' },
  { code: 'ms', iso1: 'ms', nllb: 'zsm_Latn', tess: 'msa', name: 'Malay' },
  { code: 'tl', iso1: 'tl', nllb: 'tgl_Latn', tess: 'tgl', name: 'Filipino' },
  { code: 'sw', iso1: 'sw', nllb: 'swh_Latn', tess: 'swa', name: 'Swahili' },
  { code: 'zh-Hant', iso1: 'zh', nllb: 'zho_Hant', tess: 'chi_tra', name: 'Chinese (Traditional)' },
  { code: 'zh-Hans', iso1: 'zh', nllb: 'zho_Hans', tess: 'chi_sim', name: 'Chinese (Simplified)' },
  { code: 'ja', iso1: 'ja', nllb: 'jpn_Jpan', tess: 'jpn', name: 'Japanese' },
  { code: 'ko', iso1: 'ko', nllb: 'kor_Hang', tess: 'kor', name: 'Korean' },
];

function byCode(code) {
  return LANGS.find((l) => l.code === code) || null;
}

/** Best match for a system locale like "de-AT", "zh-TW", "zh-CN". */
function fromLocale(locale) {
  const l = (locale || 'en').toLowerCase();
  if (l.startsWith('zh')) return /cn|sg|hans/.test(l) ? 'zh-Hans' : 'zh-Hant';
  const base = l.split(/[-_]/)[0];
  const hit = LANGS.find((x) => x.code === base || x.iso1 === base || (base === 'nb' && x.code === 'no'));
  return hit ? hit.code : 'en';
}

module.exports = { LANGS, byCode, fromLocale };
