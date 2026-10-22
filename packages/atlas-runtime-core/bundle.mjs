import {readFileSync, writeFileSync} from 'node:fs';
import {createHash, sign} from 'node:crypto';
const source = readFileSync(new URL('./core.js', import.meta.url), 'utf8');
const sha256 = createHash('sha256').update(source).digest('hex');
const bundle = {format:'atlas.runtime.v1', version:'core-0.1.0', bridge:1, sha256, source};
const asset = new URL('../../apps/atlas-android/app/src/main/assets/runtime/builtin.json', import.meta.url);
export const signingPayload = b => JSON.stringify([b.format,b.version,b.bridge,b.sha256]);
if (process.argv.includes('--check')) {
  if (readFileSync(asset,'utf8') !== JSON.stringify(bundle)+'\n') throw Error('Stale runtime asset: run node packages/atlas-runtime-core/bundle.mjs');
} else if (process.env.ATLAS_RUNTIME_SIGNING_KEY) {
  const key = readFileSync(process.env.ATLAS_RUNTIME_SIGNING_KEY);
  bundle.signature = sign('sha256',Buffer.from(signingPayload(bundle)),key).toString('base64');
  writeFileSync(process.argv[2] || 'runtime-bundle.json',JSON.stringify(bundle)+'\n');
} else {
  writeFileSync(asset,JSON.stringify(bundle)+'\n');
}
