import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

const failures = [];
const required = [
  'LICENSE', 'NOTICE', 'CONTRIBUTING.md', 'SECURITY.md', 'CODE_OF_CONDUCT.md',
  'THIRD_PARTY_NOTICES.md',
  'GOVERNANCE.md', 'TRADEMARKS.md', 'OPEN_SOURCE_CHECKLIST.md', 'SUPPORT.md', 'CHANGELOG.md',
  'docs/product-structure.md', 'docs/pricing-and-metering.md', 'docs/closed-alpha.md',
  'docs/alpha-data-handling.md',
  'docs/README.md',
  'docs/runtime-code-map.md', 'docs/runtime-failure-semantics.md',
  'docs/design-principles.md', 'docs/endpoint-characterization.md',
  'docs/build-and-test.md', 'docs/public-release-audit.md',
  'docs/name-review.md',
  'docs/assets-and-redistribution.md',
  'scripts/audit-git-history.mjs',
  'evals/fixture.schema.json', 'evals/fixtures/unsupported_causal_bridge.json',
];

const git = spawnSync('git', ['ls-files', '-z'], { encoding: 'utf8' });
if (git.status !== 0) {
  console.error(git.stderr || 'Could not enumerate tracked files.');
  process.exit(1);
}
const files = git.stdout.split('\0').filter(Boolean);
const tracked = new Set(files);

for (const path of required) if (!tracked.has(path)) failures.push(`missing required public file: ${path}`);

const forbiddenFiles = [
  /(^|\/)\.env($|\.)/,
  /(^|\/)local\.properties$/,
  /\.(?:apk|aab|jks|keystore|p12|pfx|pem|key)$/i,
  /\.(?:log|db|sqlite|sqlite3|gguf)$/i,
  /(^|\/)(?:session-logs|session-exports)\//i,
  /(^|\/)atlas-session-export-/i,
  /(^|\/)(?:build|dist|coverage|\.next)\//,
  /\.tsbuildinfo$/,
];
for (const path of files) {
  if (path.endsWith('.env.example')) continue;
  if (forbiddenFiles.some((pattern) => pattern.test(path))) failures.push(`forbidden tracked artifact or secret file: ${path}`);
}

const packageFiles = files.filter((path) => path === 'package.json' || path.endsWith('/package.json'));
for (const path of packageFiles) {
  const manifest = JSON.parse(readFileSync(path, 'utf8'));
  if (path.includes('/node_modules/')) continue;
  if (manifest.license !== 'Apache-2.0') failures.push(`${path} must declare Apache-2.0`);
}

const textCandidates = files.filter((path) => /\.(?:kt|kts|java|js|mjs|cjs|ts|tsx|json|ya?ml|md|sql|properties|example)$/i.test(path));
const secretPatterns = [
  ['OpenAI project key', /sk-proj-[A-Za-z0-9_-]{20,}/g],
  ['OpenAI legacy key', /sk-[A-Za-z0-9]{32,}/g],
  ['GitHub token', /(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,})/g],
  ['Google API key', /AIza[0-9A-Za-z_-]{30,}/g],
  ['AWS access key', /AKIA[0-9A-Z]{16}/g],
  ['Stripe live key', /sk_live_[A-Za-z0-9]{16,}/g],
  ['Stripe webhook secret', /whsec_[A-Za-z0-9]{20,}/g],
  ['Supabase secret key', /sb_secret_[A-Za-z0-9_-]{20,}/g],
  ['private key block', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g],
  ['JWT', /eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/g],
];
for (const path of textCandidates) {
  let content;
  try { content = readFileSync(path, 'utf8'); } catch { continue; }
  for (const [name, pattern] of secretPatterns) {
    pattern.lastIndex = 0;
    if (pattern.test(content)) failures.push(`possible ${name} in ${path}`);
  }
}

if (failures.length) {
  console.error('Public-readiness check failed:\n' + failures.map((failure) => `- ${failure}`).join('\n'));
  process.exit(1);
}

console.log(`Public-readiness source check passed for ${files.length} tracked files.`);
console.log('This does not replace full Git history, artifact, dependency-license, or trademark review.');
