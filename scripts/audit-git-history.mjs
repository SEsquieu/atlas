import { spawnSync } from 'node:child_process';

function git(args, options = {}) {
  const result = spawnSync('git', args, {
    encoding: options.encoding ?? 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.status !== 0) {
    console.error(result.stderr || `git ${args.join(' ')} failed`);
    process.exit(2);
  }
  return result.stdout;
}

const objectLines = git(['rev-list', '--objects', '--all']).split('\n').filter(Boolean);
const blobPaths = new Map();

for (const line of objectLines) {
  const separator = line.indexOf(' ');
  if (separator < 0) continue;
  const sha = line.slice(0, separator);
  const path = line.slice(separator + 1);
  const paths = blobPaths.get(sha) ?? [];
  paths.push(path);
  blobPaths.set(sha, paths);
}

const forbiddenPaths = [
  /(^|\/)\.env($|\.)/,
  /(^|\/)local\.properties$/,
  /\.(?:apk|aab|jks|keystore|p12|pfx|pem|key)$/i,
  /\.(?:log|db|sqlite|sqlite3|gguf)$/i,
  /(^|\/)(?:session-logs|session-exports)\//i,
  /(^|\/)atlas-session-export-/i,
];

const secretPatterns = [
  ['OpenAI project key', /sk-proj-[A-Za-z0-9_-]{20,}/],
  ['OpenAI legacy key', /sk-[A-Za-z0-9]{32,}/],
  ['GitHub token', /(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,})/],
  ['Google API key', /AIza[0-9A-Za-z_-]{30,}/],
  ['AWS access key', /AKIA[0-9A-Z]{16}/],
  ['Stripe live key', /sk_live_[A-Za-z0-9]{16,}/],
  ['Stripe webhook secret', /whsec_[A-Za-z0-9]{20,}/],
  ['Supabase secret key', /sb_secret_[A-Za-z0-9_-]{20,}/],
  ['private key block', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['JWT', /eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/],
];

const findings = [];
let scannedTextBlobs = 0;

for (const [sha, paths] of blobPaths) {
  if (git(['cat-file', '-t', sha]).trim() !== 'blob') continue;

  for (const path of paths) {
    if (path.endsWith('.env.example')) continue;
    if (forbiddenPaths.some((pattern) => pattern.test(path))) {
      findings.push(`prohibited historical path at blob ${sha.slice(0, 12)}: ${path}`);
    }
  }

  const size = Number(git(['cat-file', '-s', sha]).trim());
  if (!Number.isFinite(size) || size > 2 * 1024 * 1024) continue;
  const content = git(['cat-file', '-p', sha], { encoding: 'buffer' });
  if (content.includes(0)) continue;

  scannedTextBlobs += 1;
  const text = content.toString('utf8');
  for (const [kind, pattern] of secretPatterns) {
    if (pattern.test(text)) {
      findings.push(`possible ${kind} at blob ${sha.slice(0, 12)} (${paths[0]})`);
    }
  }
}

if (findings.length) {
  console.error('Reachable-history audit failed. Values are intentionally redacted:');
  for (const finding of [...new Set(findings)]) console.error(`- ${finding}`);
  process.exit(1);
}

const commits = git(['rev-list', '--all', '--count']).trim();
const refs = git(['for-each-ref', '--format=%(refname)', 'refs/heads', 'refs/remotes', 'refs/tags'])
  .split('\n').filter(Boolean).length;
console.log(`Reachable-history audit passed: ${commits} commits, ${refs} refs, ${blobPaths.size} path-bearing objects, ${scannedTextBlobs} text blobs.`);
console.log('Secret values are never printed. Unreachable objects, deleted remote refs, forks, Actions logs, caches, and release assets require separate review.');
