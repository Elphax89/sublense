'use strict';
/**
 * SubLens only uses Transformers.js for text translation, never for images. Transformers.js still
 * loads the native "sharp" image library at startup, which would tie each build to one CPU
 * architecture. Replace it with a tiny stub so the same node_modules work for x64 and arm64.
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', 'node_modules');
const sharp = path.join(root, 'sharp');
if (fs.existsSync(path.join(root, '@huggingface'))) {
  fs.rmSync(sharp, { recursive: true, force: true });
  fs.mkdirSync(sharp, { recursive: true });
  fs.writeFileSync(path.join(sharp, 'package.json'), JSON.stringify({ name: 'sharp', version: '0.0.0-sublens-stub', main: 'index.js' }, null, 2));
  fs.writeFileSync(
    path.join(sharp, 'index.js'),
    "'use strict';\nfunction sharp() { throw new Error('Image processing is not included in SubLens'); }\nmodule.exports = sharp;\nmodule.exports.default = sharp;\n",
  );
  fs.rmSync(path.join(root, '@img'), { recursive: true, force: true });
  console.log('sharp replaced by a stub (not needed for translation)');
}
