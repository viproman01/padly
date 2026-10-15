import { build } from 'esbuild';
import { mkdir, copyFile, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const dest = new URL('../mac/Sources/Padly/BrowserResources/', import.meta.url);
await mkdir(dest, { recursive: true });
await build({
  entryPoints: [fileURLToPath(new URL('src/ui.js', import.meta.url))],
  outfile: fileURLToPath(new URL('app.js', dest)),
  bundle: true,
  minify: true,
  format: 'iife',
  platform: 'browser',
  target: ['safari14', 'chrome90', 'firefox90'],
  legalComments: 'inline',
});
for (const name of ['index.html', 'styles.css']) {
  await copyFile(new URL(name, import.meta.url), new URL(name, dest));
}
// Preserve all dependency notices alongside the self-contained bundle.
const notices = [];
for (const name of ['@msgpack/msgpack', '@noble/ciphers', '@noble/curves', '@noble/hashes']) {
  notices.push(`${name}\n${await readFile(new URL(`node_modules/${name}/LICENSE`, import.meta.url), 'utf8')}`);
}
await writeFile(new URL('THIRD-PARTY-LICENSES.txt', dest), notices.join('\n\n'));
console.log('Browser resources built into mac/Sources/Padly/BrowserResources');
